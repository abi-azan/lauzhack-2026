@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.incident.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import railstatus.mobile.incident.ChatPost
import railstatus.mobile.incident.CheckInPhase
import railstatus.mobile.incident.Incident
import railstatus.mobile.incident.KeyValueStore
import railstatus.mobile.incident.MemoryKeyValueStore
import railstatus.mobile.incident.PassengerBackend
import railstatus.mobile.incident.PassengerStore
import railstatus.mobile.incident.STORE_JSON
import railstatus.mobile.incident.TrainView
import railstatus.mobile.incident.fakeTrain
import railstatus.mobile.incident.hhmm
import railstatus.mobile.incident.incidentFrom
import railstatus.chat.OPEN_AFTER_RESOLVED_MIN
import railstatus.chat.Question
import railstatus.chat.Source
import railstatus.chat.Topic
import railstatus.chat.answer
import railstatus.chat.chatOpen
import railstatus.chat.jsDateParse
import railstatus.chat.jsIsoString
import railstatus.chat.jsTrim
import railstatus.model.Alternative
import railstatus.model.IncidentFacts
import railstatus.model.Lang
import railstatus.model.IncidentUpdate
import railstatus.model.Station
import railstatus.model.Stop
import railstatus.model.Stage
import railstatus.model.Text
import railstatus.model.Train
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Port of mobile/src/incident/chat/client.ts: the passenger's side of the support chat.
// - live: the question goes to the chat service (through the backend), which answers it and
//   counts it in the admin's question groups.
// - offline (no backend, or the chat service didn't answer): the same rules (Engine.kt) run here, on the
//   facts the phone already has. Those replies don't claim the team saw the question.

@Serializable
enum class ChatFrom {
    @SerialName("me") ME,
    @SerialName("sbb") SBB,
}

@Serializable
data class ChatMsg(
    val id: String,
    val from: ChatFrom,
    val text: String,
    /** Epoch ms. */
    val at: Long,
    val source: Source? = null,
    val topic: Topic? = null,
    /** Answered on the phone: the question didn't reach the team. */
    val offline: Boolean? = null,
    val pending: Boolean? = null,
    /** Epoch ms of the published update the answer cites ("From the 17:35 update"), if it cites one. */
    val updateAt: Long? = null,
)

@Serializable
enum class ChatNotice {
    @SerialName("slow") SLOW,
    @SerialName("closed") CLOSED,
}

data class ChatState(
    val incidentId: String? = null,
    val msgs: List<ChatMsg> = emptyList(),
    val busy: Boolean = false,
    val notice: ChatNotice? = null,
)

@Serializable
private data class SavedChat(val incidentId: String? = null, val msgs: List<ChatMsg> = emptyList())

/** The incident and train as the chat rules need them. */
data class ChatContext(val facts: IncidentFacts?, val train: Train?)

/**
 * Whether the chat takes questions, for the sheet's input and the "Ask" entry points.
 * - [open]: questions are welcome (a published incident, or one resolved less than OPEN_AFTER_RESOLVED_MIN ago).
 * - [resolved]: the incident is over; while [open], the chat closes at [closesAt] (spec P5).
 * - not [open]: the sheet shows a calm closed state instead of the input, never an error.
 */
data class ChatAvailability(val open: Boolean, val resolved: Boolean = false, val closesAt: Instant? = null)

/** When a resolved incident's chat closes: OPEN_AFTER_RESOLVED_MIN after "Resolved". Null while it's not resolved. */
fun chatClosesAt(f: IncidentFacts?): Instant? {
    if (f == null || f.stage != Stage.RESOLVED) return null
    val end = jsDateParse(f.resolvedAt ?: f.updates.lastOrNull()?.publishedAt ?: return null) ?: return null
    return Instant.fromEpochMilliseconds(end + OPEN_AFTER_RESOLVED_MIN * 60_000L)
}

/** Epoch ms of the newest update among [cited] (the reply's `citedUpdateIds`), for "From the 17:35 update". */
fun citedUpdateAt(f: IncidentFacts?, cited: List<String>): Long? =
    f?.updates?.filter { it.id in cited }?.mapNotNull { jsDateParse(it.publishedAt) }?.maxOrNull()

class ChatStore(
    private val scope: CoroutineScope,
    private val passenger: PassengerStore,
    private val backend: PassengerBackend,
    private val storage: KeyValueStore = MemoryKeyValueStore(),
) {
    private val _state = MutableStateFlow(load())

    /** `useChat`. */
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private fun load(): ChatState = try {
        val s = storage.get(KEY)?.let { STORE_JSON.decodeFromString(SavedChat.serializer(), it) }
        if (s == null) ChatState() else ChatState(incidentId = s.incidentId, msgs = s.msgs.filter { it.pending != true })
    } catch (_: Exception) {
        ChatState()
    }

    private fun set(f: ChatState.() -> ChatState) {
        val s = _state.updateAndGet(f)
        try { storage.put(KEY, STORE_JSON.encodeToString(SavedChat.serializer(), SavedChat(s.incidentId, s.msgs))) } catch (_: Exception) { }
    }

    // ---------- what the phone knows: the same shapes the backend sends ----------

    /** The incident and train as the chat rules need them, in either mode. */
    fun currentFacts(): ChatContext {
        val p = passenger.state.value
        if (p.phase != CheckInPhase.CHECKED_IN) return ChatContext(null, null)
        if (p.api != null) return ChatContext(p.live?.incident, p.live?.train)
        val inc = incidentFrom(p.sent)
        val at = p.checkedInAt?.let(Instant::fromEpochMilliseconds) ?: passenger.clock()
        return ChatContext(inc?.let(::fakeFacts), chatTrain(fakeTrain(at), passenger.zone))
    }

    /** Whether the chat takes questions now (only on a train with a published, open incident). */
    fun isChatOpen(): Boolean = chatOpen(currentFacts().facts, passenger.clock())

    /**
     * [isChatOpen], plus whether the incident is resolved and when the chat closes. A 403 from the server
     * ([ChatNotice.CLOSED]) closes it too, even if the phone's clock still thinks it's open.
     */
    fun availability(): ChatAvailability {
        val f = currentFacts().facts
        val st = _state.value
        // A 403 closes only the incident it answered for (askNow syncs incidentId first); a "closed" from asking
        // before there was an incident doesn't close the next one.
        val refused = st.notice == ChatNotice.CLOSED && f != null && st.incidentId == f.id
        val open = chatOpen(f, passenger.clock()) && !refused
        val resolved = f?.stage == Stage.RESOLVED
        return ChatAvailability(open, resolved, if (resolved && open) chatClosesAt(f) else null)
    }

    // ---------- asking ----------

    private var seq = 0
    private fun newId() = "${passenger.clock().toEpochMilliseconds().toString(36)}-${++seq}"

    /** Start a fresh conversation when the incident changes (a new incident, or a demo reset). */
    fun syncIncident(incidentId: String?) {
        if (incidentId != _state.value.incidentId) set { copy(incidentId = incidentId, msgs = emptyList(), notice = null) }
    }

    /** Sends a question; [dest] is where the passenger gets off (`usePerson().dest`). */
    fun ask(text: String, dest: String?): Job = scope.launch { askNow(text, dest) }

    suspend fun askNow(text: String, dest: String?) {
        val q = jsTrim(text)
        if (q.isEmpty() || _state.value.busy) return
        val p = passenger.state.value
        val (facts, train) = currentFacts()
        if (facts == null || !chatOpen(facts, passenger.clock())) { set { copy(notice = ChatNotice.CLOSED) }; return }
        syncIncident(facts.id)

        val now = passenger.clock().toEpochMilliseconds()
        val mine = ChatMsg(id = newId(), from = ChatFrom.ME, text = q, at = now)
        val wait = ChatMsg(id = newId(), from = ChatFrom.SBB, text = "", at = now, pending = true)
        set { copy(msgs = msgs + mine + wait, busy = true, notice = null) }

        fun reply(text: String, source: Source?, topic: Topic?, offline: Boolean, cited: List<String>) = ChatMsg(
            id = wait.id, from = ChatFrom.SBB, text = text, at = passenger.clock().toEpochMilliseconds(),
            source = source, topic = topic, offline = offline, updateAt = citedUpdateAt(facts, cited),
        )
        fun local(): ChatMsg {
            val r = answer(Question(
                text = q, lang = p.lang, facts = facts, train = train, destination = dest,
                // The scripted demo plays the whole story; live, an offline answer reached no one.
                forwarded = p.api == null,
                timeZone = passenger.zone.id,
            ))
            return reply(r.text, r.source, r.topic, offline = p.api != null, cited = r.citedUpdateIds)
        }

        var out: ChatMsg? = null
        val live = p.live
        if (p.api != null && live != null) {
            when (val res = backend.postChat(p.api, live.token, q, p.lang)) {
                is ChatPost.Answered -> {
                    val cited = (res.reply.json["citedUpdateIds"] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    out = reply(res.reply.text, res.reply.source, res.reply.topic, offline = false, cited = cited)
                }
                is ChatPost.Failed -> when (res.status) {
                    429 -> {
                        set { copy(msgs = msgs.filter { it.id != wait.id && it.id != mine.id }, busy = false, notice = ChatNotice.SLOW) }
                        return
                    }
                    // The chat closed on the server (the incident was resolved a while ago): no error, the sheet
                    // shows its calm closed state. The question stays, so the passenger sees what they asked.
                    403 -> {
                        set { copy(msgs = msgs.filter { it.id != wait.id }, busy = false, notice = ChatNotice.CLOSED) }
                        return
                    }
                    // Live, a failed call never falls back to the rules on the phone: their "not known yet" would be
                    // a wrong answer. Say it plainly instead.
                    else -> out = reply(chatFailed(p.lang), null, null, offline = false, cited = emptyList())
                }
            }
        }
        // Fake mode, or the chat service didn't answer: the same rules, on the phone.
        val answer = out ?: local()
        set { copy(msgs = msgs.map { if (it.id == wait.id) answer else it }, busy = false) }
    }

    private companion object {
        const val KEY = "sbb-chat-v1"

        fun chatFailed(lang: Lang): String = when (lang) {
            Lang.DE -> "Entschuldigung, ich konnte gerade keine Antwort finden. Bitte fragen Sie noch einmal."
            Lang.FR -> "Désolé, je n'ai pas pu trouver de réponse pour le moment. Veuillez reposer votre question."
            Lang.IT -> "Mi dispiace, al momento non ho trovato una risposta. La preghiamo di chiedere di nuovo."
            else -> "Sorry, I couldn't get an answer just now. Please ask again."
        }
    }
}

/** client.ts `fakeFacts`: the scripted incident in the backend's `IncidentFacts` shape. */
fun fakeFacts(inc: Incident): IncidentFacts {
    val latest = inc.latest
    // Facts the admin published so far: each scripted update may add or replace some.
    var cause: Text? = null
    var alternatives: List<Alternative>? = null
    var onboard: Text? = null
    for (u in inc.updates) {
        cause = u.cause ?: cause
        alternatives = u.alternatives ?: alternatives
        onboard = u.onboard ?: onboard
    }
    fun iso(t: Instant) = jsIsoString(t.toEpochMilliseconds())
    return IncidentFacts(
        id = inc.id,
        version = inc.updates.size,
        stage = inc.stage,
        trainIds = listOf(inc.trainId),
        cause = cause,
        alternatives = alternatives ?: emptyList(),
        onboard = onboard,
        delayMin = latest.delay,
        nextUpdateBy = latest.nextBy?.let(::iso),
        resolvedAt = if (inc.stage == railstatus.model.Stage.RESOLVED) iso(latest.at) else null,
        updates = inc.updates.map { u ->
            IncidentUpdate(
                id = "u${u.index}", incidentId = inc.id, seq = u.index, stage = u.stage, title = u.title, body = u.body,
                publishedAt = iso(u.at), nextUpdateBy = u.nextBy?.let(::iso),
            )
        },
    )
}

/** client.ts `chatTrain`: the screens' train in the backend's `Train` shape (fake stops get their name as id). */
fun chatTrain(t: TrainView, zone: kotlinx.datetime.TimeZone = railstatus.mobile.incident.SWISS_ZONE): Train = Train(
    id = t.id, product = t.product, line = "${t.product} ${t.line}", number = t.number, direction = t.direction,
    stops = t.stops.map { Stop(station = Station(id = it.stationId ?: it.station, name = it.station), arr = hhmm(it.planned, zone)) },
)
