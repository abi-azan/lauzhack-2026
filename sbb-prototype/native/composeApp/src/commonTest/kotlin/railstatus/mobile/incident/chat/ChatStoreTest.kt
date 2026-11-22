@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package railstatus.mobile.incident.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import railstatus.mobile.incident.ChatPost
import railstatus.mobile.incident.FakeBackend
import railstatus.mobile.incident.KeyValueStore
import railstatus.mobile.incident.MemoryKeyValueStore
import railstatus.mobile.incident.PassengerConfig
import railstatus.mobile.incident.PassengerStore
import railstatus.mobile.incident.ServiceReply
import railstatus.mobile.incident.facts
import railstatus.mobile.incident.passengerStore
import railstatus.mobile.incident.zurich
import railstatus.chat.JsLocal
import railstatus.chat.Question
import railstatus.chat.Source
import railstatus.chat.Topic
import railstatus.chat.answer
import railstatus.model.DelayRange
import railstatus.model.Lang
import railstatus.model.Stage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

class ChatStoreTest {
    @BeforeTest
    fun localTime() { JsLocal.zone = zurich }

    private fun TestScope.chat(p: PassengerStore, backend: FakeBackend, storage: KeyValueStore = MemoryKeyValueStore()) =
        ChatStore(backgroundScope, p, backend, storage)

    @Test
    fun closedUntilTheTrainHasAPublishedIncident() = runTest {
        val backend = FakeBackend()
        val p = passengerStore(backend)
        val c = chat(p, backend)
        assertFalse(c.isChatOpen())
        c.ask("Why did we stop?", null); runCurrent()
        assertEquals(ChatNotice.CLOSED, c.state.value.notice)
        assertTrue(c.state.value.msgs.isEmpty())

        p.quickCheckIn(); runCurrent()
        assertFalse(c.isChatOpen()) // checked in, nothing published yet
        p.publishNext()
        assertTrue(c.isChatOpen())

        assertEquals(ChatAvailability(open = true), c.availability())

        // Resolved: open for 15 more minutes, and it says until when.
        repeat(4) { p.publishNext() }
        assertTrue(c.isChatOpen())
        val closesAt = c.availability().closesAt!!
        assertEquals(ChatAvailability(open = true, resolved = true, closesAt = closesAt), c.availability())
        assertEquals(p.clock() + kotlin.time.Duration.parse("15m"), closesAt)
        advanceTimeBy(15 * 60_000L - 1); runCurrent()
        assertTrue(c.isChatOpen())
        advanceTimeBy(1); runCurrent()
        assertFalse(c.isChatOpen())
        assertEquals(ChatAvailability(open = false, resolved = true), c.availability())
        // Closed, not an error: asking now only marks it closed, and the conversation stays.
        c.ask("Thanks", null); runCurrent()
        assertEquals(ChatNotice.CLOSED, c.state.value.notice)
    }

    @Test
    fun fakeModeAnswersOnThePhoneLikeTheEngine() = runTest {
        val backend = FakeBackend()
        val p = passengerStore(backend)
        val c = chat(p, backend)
        p.quickCheckIn(); runCurrent()
        p.publishNext(); p.publishNext()
        val dest = p.currentPerson().dest
        c.ask("  Will I make my connection in Lausanne?  ", dest); runCurrent()

        val (facts, train) = c.currentFacts()
        val expected = answer(Question(
            text = "Will I make my connection in Lausanne?", lang = Lang.EN, facts = facts!!, train = train,
            destination = dest, forwarded = true, timeZone = "Europe/Zurich",
        ))
        val msgs = c.state.value.msgs
        assertEquals(listOf(ChatFrom.ME, ChatFrom.SBB), msgs.map { it.from })
        assertEquals("Will I make my connection in Lausanne?", msgs[0].text)
        with(msgs[1]) {
            assertEquals(expected.text, text)
            assertEquals(Source.TEMPLATE, source)
            assertEquals(Topic.CONNECTION, topic)
            assertEquals(false, offline)
            assertNull(pending)
            // "From the 17:1x update": the update the engine cited.
            assertEquals(facts!!.updates.last().publishedAt.let { railstatus.chat.jsDateParse(it) }, updateAt)
            assertEquals(SourceKind.UPDATE, sourceKind(this))
        }
        assertTrue(expected.text.startsWith("This train is expected in Lausanne between about 18:17 and 18:27 instead of 17:52 (+25–35 min)."), expected.text)
        assertTrue(backend.chats.isEmpty())
        assertEquals(c.state.value.incidentId, p.currentIncident()!!.id)
        assertFalse(c.state.value.busy)
    }

    @Test
    fun liveAsksTheChatService() = runTest {
        val backend = FakeBackend()
        backend.incident = facts(Stage.INVESTIGATING, Stage.IDENTIFIED, delay = DelayRange(5, 10))
        val p = passengerStore(backend, config = PassengerConfig(api = "https://rail.example"))
        val c = chat(p, backend)
        p.quickCheckIn(); runCurrent()
        assertTrue(c.isChatOpen())

        backend.chatResult = ChatPost.Answered(ServiceReply("From the team.", Source.LLM, Topic.OTHER, Lang.EN, "llm",
            JsonObject(mapOf("citedUpdateIds" to JsonArray(listOf(JsonPrimitive("u1")))))))
        c.ask("hello?", null); runCurrent()
        assertEquals(listOf(Triple("t1", "hello?", Lang.EN)), backend.chats)
        with(c.state.value.msgs.last()) {
            assertEquals("From the team.", text); assertEquals(Source.LLM, source); assertEquals(false, offline)
            assertEquals(Instant.parse("2026-09-17T15:10:00Z").toEpochMilliseconds(), updateAt) // u1, not the newer u2
        }

        // 429: drop the question, ask them to wait.
        backend.chatResult = ChatPost.Failed(429)
        c.ask("again", null); runCurrent()
        assertEquals(2, c.state.value.msgs.size)
        assertEquals(ChatNotice.SLOW, c.state.value.notice)

        // 403: keep the question, the chat is closed (the sheet shows its closed state, not an error).
        backend.chatResult = ChatPost.Failed(403)
        c.ask("closed?", null); runCurrent()
        assertEquals(listOf("hello?", "From the team.", "closed?"), c.state.value.msgs.map { it.text })
        assertEquals(ChatNotice.CLOSED, c.state.value.notice)
        assertFalse(c.availability().open)

        // Anything else (timeout, network, 5xx): say it plainly. The rules' "not known yet" would be a wrong answer.
        backend.chatResult = ChatPost.Failed(0)
        c.ask("Why did we stop?", null); runCurrent()
        with(c.state.value.msgs.last()) {
            assertEquals("Sorry, I couldn't get an answer just now. Please ask again.", text)
            assertEquals(false, offline)
            assertNull(pending)
        }
        assertNull(c.state.value.notice)
    }

    @Test
    fun aNewIncidentStartsAFreshChatAndPendingMessagesDontSurviveARestart() = runTest {
        val backend = FakeBackend()
        val storage = MemoryKeyValueStore()
        val p = passengerStore(backend)
        val c = chat(p, backend, storage)
        p.quickCheckIn(); runCurrent()
        p.publishNext()
        c.ask("Hello", null); runCurrent()
        assertEquals(2, c.state.value.msgs.size)
        assertEquals(c.state.value, chat(p, backend, storage).state.value)

        storage.put("sbb-chat-v1", """{"incidentId":"x","msgs":[{"id":"1","from":"me","text":"q","at":1},{"id":"2","from":"sbb","text":"","at":1,"pending":true}]}""")
        assertEquals(listOf("q"), chat(p, backend, storage).state.value.msgs.map { it.text })

        c.syncIncident("other")
        assertTrue(c.state.value.msgs.isEmpty())
        assertEquals("other", c.state.value.incidentId)
    }

    @Test
    fun liveResolvedKeepsAnsweringThenClosesCalmly() = runTest {
        val backend = FakeBackend()
        backend.incident = facts(Stage.INVESTIGATING, Stage.RESOLVED)
        val p = passengerStore(backend, config = PassengerConfig(api = "https://rail.example"))
        val c = chat(p, backend)
        p.quickCheckIn(); runCurrent()
        // Resolved at 15:11Z, the test clock is 15:16Z: open until 15:26Z.
        assertEquals(ChatAvailability(true, true, Instant.parse("2026-09-17T15:26:00Z")), c.availability())
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals(ChatAvailability(open = false, resolved = true), c.availability())
    }

    @Test
    fun quickRepliesReachTheRightTopics() {
        val f = facts(Stage.INVESTIGATING, Stage.IDENTIFIED, delay = DelayRange(20, 30))
        fun topic(q: String) = answer(Question(text = q, lang = Lang.EN, facts = f)).topic
        // strings_chat.xml, en/de/fr/it: "Will I make my connection?", "Is there a bus?", "What happened?"
        listOf("Will I make my connection?", "Erreiche ich meinen Anschluss?", "Aurai-je ma correspondance ?", "Prenderò la coincidenza?")
            .forEach { assertEquals(Topic.CONNECTION, topic(it), it) }
        listOf("Is there a bus?", "Gibt es einen Bus?", "Y a-t-il un bus ?", "C’è un autobus?")
            .forEach { assertEquals(Topic.ALTERNATIVE, topic(it), it) }
        listOf("What happened?", "Was ist passiert?", "Qu’est-ce qui s’est passé ?", "Cosa è successo?")
            .forEach { assertEquals(Topic.CAUSE, topic(it), it) }
    }

    @Test
    fun sources() {
        fun m(source: Source?, topic: Topic? = null, offline: Boolean? = null, updateAt: Long? = null) =
            ChatMsg("1", ChatFrom.SBB, "x", 0, source, topic, offline, updateAt = updateAt)
        assertEquals(SourceKind.OFFLINE, sourceKind(m(Source.SAFETY, offline = true)))
        assertEquals(SourceKind.SAFETY, sourceKind(m(Source.SAFETY)))
        assertEquals(SourceKind.UPDATE, sourceKind(m(Source.TEMPLATE, updateAt = 1)))
        assertEquals(SourceKind.UPDATES, sourceKind(m(Source.LLM)))
        assertEquals(SourceKind.PASSED_ON, sourceKind(m(Source.NOT_KNOWN)))
        assertEquals(SourceKind.GENERAL, sourceKind(m(Source.FIXED, Topic.REFUND)))
        assertNull(sourceKind(m(Source.FIXED, Topic.GREETING)))
        assertNull(sourceKind(ChatMsg("1", ChatFrom.ME, "q", 0)))
        assertNull(sourceKind(ChatMsg("1", ChatFrom.SBB, "", 0, pending = true)))
    }
}
