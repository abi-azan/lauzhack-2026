@file:OptIn(ExperimentalTime::class)

package railstatus.chat

import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import railstatus.model.IncidentFacts
import railstatus.model.Lang
import railstatus.model.RailJson
import railstatus.model.Train
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The Kotlin engine must answer exactly like mobile/src/incident/chat/engine.ts. GoldenData holds what the
 * TypeScript engine answered (run under TZ=Europe/Zurich) for ~440 questions (chat/eval.ts, the research set
 * in chat/research/data.mjs and answer.mjs, the app's suggestions, and extra edge cases) against 13 incident
 * moments, 2 trains, 4 languages, several destinations, time zones and the forwarded flag; plus the helpers.
 *
 * After a change to engine.ts, port it, then regenerate GoldenData.kt with gen.ts in
 * native/composeApp/src/commonTest/kotlin/railstatus/mobile/incident/golden (the commands are at its top). Generated from the TypeScript at commit 2242767.
 */
class EngineGoldenTest {
    private companion object {
        /** [alreadyPassed]'s wording, in the four languages. */
        val PASSED = listOf("had already passed", "war schon an", "avait déjà passé", "aveva già superato")
    }

    private val golden: JsonObject = Json.parseToJsonElement(GoldenAmendments.apply(GoldenData.json)).jsonObject
    private val out = Json { explicitNulls = false; encodeDefaults = true }

    private val facts: Map<String, IncidentFacts> = golden.getValue("facts").jsonObject.mapValues {
        RailJson.decodeFromJsonElement(IncidentFacts.serializer(), it.value)
    }
    private val trains: Map<String, Train> = golden.getValue("trains").jsonObject.mapValues {
        RailJson.decodeFromJsonElement(Train.serializer(), it.value)
    }

    @BeforeTest
    fun localTime() {
        // JavaScript's local time when the golden data was made.
        JsLocal.zone = TimeZone.of("Europe/Zurich")
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun lang(s: String?): Lang? = s?.let { v -> Lang.entries.first { it.name.lowercase() == v } }

    private fun question(q: JsonObject) = Question(
        text = q.str("text")!!,
        lang = lang(q.str("lang")),
        facts = facts.getValue(q.str("facts")!!),
        train = q.str("train")?.let(trains::getValue),
        destination = q.str("destination"),
        forwarded = (q["forwarded"] as? JsonPrimitive)?.booleanOrNull,
        timeZone = q.str("timeZone"),
        topic = Topic.fromWire(q.str("topic")),
        station = (q["station"] as? JsonObject)?.let { Place(it.str("id"), it.str("name")!!) },
    )

    /**
     * Where the Kotlin engine answers differently from the TypeScript on purpose, since the review of 22 Nov
     * (docs/review-2026-11-22.md). Each change has its own tests in EngineReviewTest; the golden data still
     * covers everything else.
     * - C1/C2: emergency or not. New emergencies ("I cannot breathe"), and questions about the incident's fire or
     *   injured ("Is the fire out?") are the cause, not the 112 text.
     * - C4: no delay at a stop the train had already passed.
     * - C5: after "resolved", the resolved update answers the delay, connection and options questions.
     * - Delay phrasings the TypeScript didn't know ("Wann fährt der Zug weiter?"): `other` became `delay`.
     */
    private fun changedOnPurpose(q: Question, expected: JsonObject, actual: Reply): Boolean {
        val was = Topic.fromWire(expected.str("topic"))
        val topicChange = was != actual.topic && (Topic.EMERGENCY in setOf(was, actual.topic) || Topic.CAUSE in setOf(was, actual.topic))
        val resolved = q.facts.stage == railstatus.model.Stage.RESOLVED && actual.topic in setOf(Topic.DELAY, Topic.CONNECTION, Topic.ALTERNATIVE, Topic.OTHER)
        val passed = PASSED.any { it in actual.text }
        val newDelay = was == Topic.OTHER && actual.topic == Topic.DELAY
        return topicChange || resolved || passed || newDelay
    }

    @Test
    fun answersLikeTheTypeScriptEngine() {
        val cases = golden.getValue("cases").jsonArray
        val failures = mutableListOf<String>()
        var onPurpose = 0
        for (c in cases) {
            val q = c.jsonObject.getValue("q").jsonObject
            val expected = c.jsonObject.getValue("reply")
            val question = question(q)
            val reply = answer(question)
            val actual = out.encodeToJsonElement(Reply.serializer(), reply)
            if (actual != expected && changedOnPurpose(question, expected.jsonObject, reply)) { onPurpose++; continue }
            if (actual != expected) failures.add("$q\n  expected $expected\n  actual   $actual")
            c.jsonObject["ground"]?.let { g ->
                val ground = JsonArray(groundCheck(reply.text, question).map(::JsonPrimitive))
                if (ground != g) failures.add("groundCheck $q\n  expected $g\n  actual   $ground")
            }
        }
        assertEquals(emptyList(), failures.take(20), "${failures.size} of ${cases.size} answers differ")
        // The review's changes touch a minority of the cases; if this grows, something else changed too.
        assertTrue(onPurpose < cases.size / 8, "$onPurpose of ${cases.size} answers changed on purpose")
        println("golden: $onPurpose of ${cases.size} answers differ on purpose (review C1, C2, C4, C5)")
    }

    @Test
    fun helpersLikeTheTypeScriptEngine() {
        val failures = mutableListOf<String>()
        val evalTrain = trains.getValue("eval")
        val identified = facts.getValue("identified")
        fun place(p: Place?): JsonElement = if (p == null) JsonNull else out.encodeToJsonElement(Place.serializer(), p)
        for (h in golden.getValue("helpers").jsonArray.map { it.jsonObject }) {
            val text = h.str("text")!!
            val n = norm(text)
            val got = mapOf(
                "n" to JsonPrimitive(n),
                "langEn" to JsonPrimitive(detectLang(text, Lang.EN).name.lowercase()),
                "langIt" to JsonPrimitive(detectLang(text, Lang.IT).name.lowercase()),
                "langDe" to JsonPrimitive(detectLang(text).name.lowercase()),
                "topic" to JsonPrimitive(classify(n).wire),
                "topicAt" to JsonPrimitive(classify(n, Place(name = "X")).wire),
                "station" to place(findStation(n, evalTrain, identified)),
                "stationNoTrain" to place(findStation(n)),
            )
            for ((k, v) in got) {
                if (h[k] == v) continue
                // C1/C2, as in changedOnPurpose.
                val pair = h.str(k) to (v as? JsonPrimitive)?.content
                if (k.startsWith("topic") && (pair.toList().any { it == "emergency" || it == "cause" } || pair == ("other" to "delay"))) continue
                failures.add("$k of \"$text\": expected ${h[k]}, got $v")
            }
        }
        assertEquals(emptyList(), failures.take(20), "${failures.size} helper results differ")
    }

    @Test
    fun clocks() {
        for (c in golden.getValue("clocks").jsonArray.map { it.jsonObject }) {
            assertEquals(c.str("out"), clockOf(c.str("iso")!!, c.str("tz")), "clockOf($c)")
        }
        for (c in golden.getValue("adds").jsonArray.map { it.jsonObject }) {
            assertEquals(c.str("out"), addToClock(c.str("hhmm")!!, c.getValue("min").jsonPrimitive.content.toInt()), "addToClock($c)")
        }
    }

    @Test
    fun chatOpenLikeTheTypeScriptEngine() {
        for (c in golden.getValue("opens").jsonArray.map { it.jsonObject }) {
            val f = c.str("facts")?.let(facts::getValue)
            val now = Instant.fromEpochMilliseconds(c.getValue("now").jsonPrimitive.long)
            assertEquals(c.getValue("out").jsonPrimitive.booleanOrNull, chatOpen(f, now), "chatOpen($c)")
        }
    }

    @Test
    fun clustersLikeTheTypeScriptEngine() {
        for (c in golden.getValue("clusters").jsonArray.map { it.jsonObject }) {
            val station = (c["station"] as? JsonObject)?.let { Place(it.str("id"), it.str("name")!!) }
            val got = clusterOf(Topic.fromWire(c.str("topic"))!!, station)
            val actual = if (got == null) JsonNull else out.encodeToJsonElement(Cluster.serializer(), got)
            assertEquals(c.getValue("out"), actual, "clusterOf($c)")
        }
    }

    @Test
    fun modelPathLikeTheTypeScriptEngine() {
        val withAlternatives = facts.getValue("withAlternatives")
        for (c in golden.getValue("llm").jsonArray.map { it.jsonObject }) {
            val got = parseLlm(c.str("raw")!!, withAlternatives)
            val actual = if (got == null) JsonNull else JsonObject(mapOf(
                "answer" to JsonPrimitive(got.answer), "cited" to JsonArray(got.cited.map(::JsonPrimitive)),
            ))
            assertEquals(c.getValue("out"), actual, "parseLlm(${c.str("raw")})")
        }
        for (c in golden.getValue("prompts").jsonArray.map { it.jsonObject }) {
            val q = Question(
                text = c.str("text")!!, facts = facts.getValue(c.str("facts")!!),
                train = c.str("train")?.let(trains::getValue), timeZone = c.str("timeZone"),
            )
            val got = llmPrompt(q, lang(c.str("lang"))!!)
            val want = c.getValue("out").jsonObject
            assertEquals(want.str("system"), got.system)
            assertEquals(want.str("user"), got.user)
        }
        for (c in golden.getValue("grounds").jsonArray.map { it.jsonObject }) {
            val q = Question(text = "", facts = facts.getValue(c.str("facts")!!), train = c.str("train")?.let(trains::getValue), timeZone = c.str("timeZone"))
            assertEquals(c.getValue("out"), JsonArray(groundCheck(c.str("text")!!, q).map(::JsonPrimitive)), "groundCheck($c)")
        }
    }
}

/**
 * Reviewed wording changes made after the golden data was generated (engine.ts is gone, so it can't be
 * regenerated). Each pair is applied to the golden JSON before comparing.
 *
 * 22 Nov: the chat no longer says a question was "passed to the team". No human answers it; what isn't known yet
 * comes with the next update, which is pushed to the passenger.
 */
internal object GoldenAmendments {
    val pairs = listOf(
        "We’ve passed your question to the team." to "We’ll notify you as soon as SBB knows more.",
        "Wir haben Ihre Frage an das Team weitergegeben." to "Wir benachrichtigen Sie, sobald die SBB mehr weiss.",
        "Nous avons transmis votre question à l’équipe." to "Nous vous avertirons dès que les CFF en sauront plus.",
        "Abbiamo inoltrato la vostra domanda al team." to "Vi avviseremo non appena le FFS ne sapranno di più.",
        "We can’t see your connecting train yet, so we’ve passed your question to the team." to "Please check your connecting train in the timetable.",
        "Ihren Anschlusszug sehen wir noch nicht, deshalb haben wir Ihre Frage an das Team weitergegeben." to "Bitte prüfen Sie Ihren Anschlusszug im Fahrplan.",
        "Nous ne voyons pas encore votre train de correspondance : nous avons transmis votre question à l’équipe." to "Vérifiez votre train de correspondance dans l’horaire.",
        "Non vediamo ancora il vostro treno in coincidenza: abbiamo inoltrato la domanda al team." to "Verificate il treno in coincidenza nell’orario.",
    )
    // Longest first, so the connection sentences go before the short "passed to the team" ones they contain.
    fun apply(json: String): String = pairs.sortedByDescending { it.first.length }.fold(json) { s, (a, b) -> s.replace(a, b) }
}
