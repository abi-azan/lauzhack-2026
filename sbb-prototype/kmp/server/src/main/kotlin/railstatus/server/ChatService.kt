@file:OptIn(ExperimentalTime::class)

package railstatus.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import railstatus.chat.Question
import railstatus.chat.Reply
import railstatus.chat.Source
import railstatus.chat.Topic
import railstatus.chat.answer
import railstatus.chat.chatOpen
import railstatus.chat.groundCheck
import railstatus.chat.jsNumberString
import railstatus.chat.jsTrim
import railstatus.chat.llmPrompt
import railstatus.chat.norm
import railstatus.chat.parseLlm
import railstatus.model.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Chat settings.
 * [key] guards `POST /api/internal/questions` (X-Chat-Key), the way an external chat service can still push
 * question groups; the chat in this server doesn't need it. [llmKey] turns on the optional model steps.
 */
data class ChatConfig(
    val key: String? = null,
    val llmKey: String? = null,
    val classifyModel: String = "z-ai/glm-5.3-flash",
    val model: String = "openai/gpt-6-luna",
    /** IANA zone for the clock times in answers ("next update by 14:15"). */
    val timeZone: String = "Europe/Zurich",
    /** The passenger chat's real model (answers anything, plans journeys). The rules below are only its fallback. */
    val smart: SmartChat? = null,
)

/** A refused question, answered as `{"error": message}` with [status]. */
class ChatError(val status: Int, message: String) : RuntimeException(message)

/**
 * The passengers' support chat, in process (it was a Node service, sbb-prototype/chat, before). It answers only
 * from what the admin published (the incident's facts and updates) or from fixed, reviewed texts, with the rules
 * in kmp/shared (railstatus.chat), and groups the questions for the admin's questions panel.
 *
 * The JSON it returns is byte-for-byte what the Node service returned, so the web and native clients don't change.
 */
class ChatService(
    private val incidents: IncidentService,
    private val config: ChatConfig = ChatConfig(),
    private val model: ChatModel? = config.llmKey?.takeIf(String::isNotBlank)
        ?.let { ChatModel(it, config.classifyModel, config.model) },
) {
    private val log = LoggerFactory.getLogger(ChatService::class.java)

    // ---------- question groups ----------

    private class Group(
        val id: String, val label: String, val topic: Topic, val stationId: String?,
        val passengers: MutableSet<String> = LinkedHashSet(), var questions: Int = 0,
        val langs: LinkedHashMap<Lang, Int> = LinkedHashMap(), var sample: String, var lastAt: String = "", var urgent: Boolean,
    )

    /** incident id → cluster id → group, in insertion order. */
    private val groups = LinkedHashMap<String, LinkedHashMap<String, Group>>()

    /** Serialises recording and pushing, so the dashboards get the question groups in order. */
    private val groupsLock = Mutex()

    private fun record(incidentId: String, passengerId: String, text: String, reply: Reply) {
        val cluster = reply.cluster ?: return
        val byId = groups.getOrPut(incidentId) { LinkedHashMap() }
        val c = byId.getOrPut(cluster.id) {
            Group(cluster.id, cluster.label, reply.topic, reply.station?.id, sample = text, urgent = reply.urgent)
        }
        c.passengers.add(passengerId)
        c.questions++
        c.langs[reply.lang] = (c.langs[reply.lang] ?: 0) + 1
        c.lastAt = isoNow()
        c.urgent = c.urgent || reply.urgent
        if (reply.urgent) c.sample = text
    }

    /** Urgent groups first, then the most-asked, then the most recent. */
    private fun sorted(incidentId: String): List<Group> =
        groups[incidentId]?.values.orEmpty().sortedWith(
            compareByDescending<Group> { it.urgent }.thenByDescending { it.passengers.size }.thenByDescending { it.lastAt },
        )

    /** The `questions.changed` event for the dashboards (`/ws/admin`, `GET /api/admin/questions`). */
    private fun clustersEvent(incidentId: String, ts: String) = QuestionsChanged(
        ts, incidentId,
        sorted(incidentId).map { QuestionCluster(it.id, it.label, it.passengers.size, it.sample, it.lastAt, it.urgent) },
    )

    /** `GET /api/chat/incidents/{id}/questions`: the same event with each group's topic, station, question count and languages. */
    suspend fun questionsJson(incidentId: String): String = groupsLock.withLock {
        buildJsonObject {
            put("ts", isoNow())
            put("type", "questions.changed")
            put("incidentId", incidentId)
            putJsonArray("clusters") {
                for (c in sorted(incidentId)) addJsonObject {
                    put("id", c.id); put("label", c.label); put("count", c.passengers.size); put("sample", c.sample)
                    put("lastAt", c.lastAt); put("urgent", c.urgent); put("topic", c.topic.wire)
                    c.stationId?.let { put("stationId", it) }
                    put("questions", c.questions)
                    putJsonObject("langs") { c.langs.forEach { (l, n) -> put(l.wire, n) } }
                }
            }
        }.toString()
    }

    // ---------- rate limit ----------

    private val lastAt = ConcurrentHashMap<String, Long>()
    private val perIncident = ConcurrentHashMap<String, Int>()

    @Synchronized
    private fun limit(token: String, incidentId: String) {
        val now = System.currentTimeMillis()
        if (now - (lastAt[token] ?: 0L) < MIN_GAP_MS) throw ChatError(429, "slow-down")
        val key = "$incidentId|$token"
        val n = perIncident[key] ?: 0
        if (n >= MAX_PER_INCIDENT) throw ChatError(429, "too-many-questions")
        lastAt[token] = now
        perIncident[key] = n + 1
    }

    // ---------- usage, for GET /api/chat/health ----------

    private val usage = linkedMapOf(
        "questions" to 0, "template" to 0, "not_known" to 0, "safety" to 0, "fixed" to 0, "llm" to 0,
        "llmCalls" to 0, "llmRejected" to 0, "classifyCalls" to 0, "classifiedByModel" to 0,
    )

    private fun count(what: String) = synchronized(usage) { usage[what] = usage.getValue(what) + 1 }

    fun healthJson(): String = buildJsonObject {
        put("ok", true)
        put("model", if (model != null) JsonPrimitive(model.answerModel) else JsonNull)
        put("classifyModel", if (model != null) JsonPrimitive(model.classifyModel) else JsonNull)
        putJsonObject("usage") { synchronized(usage) { usage.forEach { (k, v) -> put(k, v) } } }
    }.toString()

    // ---------- the model path: "other" questions only, grounded or dropped ----------

    private data class LlmHit(val text: String, val cited: List<String>)

    private class CachedLlm(val hit: LlmHit?)

    /** Per incident version, train, destination, language and question: fourteen people asking the same thing is one call. */
    private val llmCache = ConcurrentHashMap<String, CachedLlm>()

    /** Station names a model answer may use even where they'd look like a name. */
    private fun places(q: Question): List<String> =
        q.train?.stops?.map { it.station.name }.orEmpty() + listOfNotNull(q.facts.section?.from?.name, q.facts.section?.to?.name)

    private suspend fun viaModel(q: Question, base: Reply): Reply? {
        if (model == null || base.topic != Topic.OTHER) return null
        // Per train and destination too (review C7): the facts hold every train's stops, the answer only this one's.
        val key = "${q.facts.id}|${q.facts.version}|${q.train?.id}|${q.destination}|${base.lang.wire}|${norm(q.text)}"
        val cached = llmCache[key]
        val hit = if (cached != null) cached.hit else {
            var found: LlmHit? = null
            try {
                count("llmCalls")
                // The passenger's own phone number or e-mail doesn't need to go to OpenRouter.
                val raw = model.answer(llmPrompt(q.copy(text = Pii.scrub(q.text)), base.lang))
                val parsed = parseLlm(raw, q.facts)
                val bad = if (parsed != null) {
                    groundCheck(parsed.answer, q) + Pii.find(parsed.answer, keep = places(q)).map { "personal data (${it.kind})" }
                } else listOf("invalid")
                if (parsed != null && bad.isEmpty()) found = LlmHit(parsed.answer, parsed.cited)
                else {
                    count("llmRejected")
                    log.warn("model answer dropped (${bad.joinToString(", ")}): ${Pii.scrub(q.text)}")
                }
            } catch (e: Exception) {
                log.warn("model call failed: ${e.message}")
            }
            llmCache[key] = CachedLlm(found)
            found
        }
        return hit?.let { base.copy(text = it.text, source = Source.LLM, citedUpdateIds = it.cited) }
    }

    private fun sorry(lang: Lang): String = when (lang) {
        Lang.DE -> "Entschuldigung, ich konnte gerade keine Antwort finden. Bitte fragen Sie noch einmal."
        Lang.FR -> "Désolé, je n'ai pas pu trouver de réponse pour le moment. Veuillez reposer votre question."
        Lang.IT -> "Mi dispiace, al momento non ho trovato una risposta. La preghiamo di chiedere di nuovo."
        else -> "Sorry, I couldn't get an answer just now. Please ask again."
    }

    // ---------- the question ----------

    private var seq = 0

    /**
     * `POST /api/chat/{token}`. [body] is the raw request body (any content type), [token] the check-in token.
     * Returns the response JSON, or throws [ChatError].
     */
    suspend fun ask(token: String, body: String): String {
        if (body.length > MAX_BODY) throw ChatError(413, "too-large")
        val json = try {
            if (body.isEmpty()) JsonObject(emptyMap()) else Json.parseToJsonElement(body)
        } catch (_: Exception) {
            throw ChatError(400, "bad-json")
        }
        // `body.text` on JSON null was a TypeError in the Node service.
        if (json is JsonNull) throw ChatError(500, "internal")
        val fields = json as? JsonObject ?: JsonObject(emptyMap())

        val text = jsTrim(jsString(fields["text"])).take(MAX_TEXT)
        if (text.isEmpty()) throw ChatError(400, "empty")
        val view = try {
            incidents.checkInView(token)
        } catch (_: NotFound) {
            throw ChatError(404, "unknown-token")
        }
        val checkIn = view.checkIn
        val train = view.train
        val incident = view.incident
        if (incident == null || !chatOpen(incident, Clock.System.now())) throw ChatError(403, "no-open-incident")
        limit(token, incident.id)

        val asked = (fields["lang"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val lang = CHAT_LANGS.firstOrNull { it.wire == asked } ?: checkIn.lang
        val q = Question(
            text = text, lang = lang, facts = incident, train = train, destination = checkIn.destinationId,
            forwarded = true, timeZone = config.timeZone,
            nextMove = runCatching { incidents.nextMoveFor(checkIn, incident) }.getOrNull(),
        )
        var base = answer(q)
        var classifiedBy = "rules"
        // The real model answers first; the rules only group the question for the admin, and answer if it fails.
        val smart = config.smart?.answer(text, lang, checkIn, train, incident, q.nextMove)
        if (smart == null && base.topic == Topic.OTHER && model != null) {
            val c = model.classify(Pii.scrub(text), train, incident) { count("classifyCalls") }
            if (c != null) {
                base = answer(q.copy(topic = c.topic, station = c.station))
                classifiedBy = "llm"
                count("classifiedByModel")
            }
        }
        val reply = if (smart != null) base.copy(text = smart, source = Source.LLM, citedUpdateIds = emptyList(), classifiedBy = "llm")
        // The model is on but couldn't answer: say so. A rule's "not known yet" here would be a wrong answer.
        else if (config.smart != null) base.copy(text = sorry(lang), source = Source.LLM, citedUpdateIds = emptyList(), classifiedBy = "llm")
        else (viaModel(q, base) ?: base).copy(classifiedBy = classifiedBy)

        count("questions")
        count(reply.source.wire)
        val questionId = "q-${synchronized(this) { ++seq }}"
        groupsLock.withLock {
            // The dashboard may be on a projector: a passenger's phone number or e-mail stays out of it.
            val shown = Pii.scrub(text)
            record(incident.id, checkIn.passengerId, shown, reply)
            val cluster = reply.cluster
            if (cluster != null) {
                toAdmins(QuestionNew(isoNow(), incident.id, checkIn.passengerId, reply.lang, shown, cluster.id))
                toAdmins(clustersEvent(incident.id, isoNow()))
            }
        }
        log.info("${incident.id} ${reply.topic.wire}/${reply.source.wire}/${reply.lang.wire}${if (classifiedBy == "llm") " (model topic)" else ""}: ${Pii.scrub(text)}")
        return buildJsonObject {
            put("questionId", questionId)
            put("reply", replyJson(reply))
            reply.cluster?.let { put("clusterId", it.id) }
        }.toString()
    }

    /** To the admin's questions panel (`/ws/admin`), in process. Best effort, like the Node service's POST was. */
    private suspend fun toAdmins(ev: WsEvent) {
        try {
            incidents.onQuestions(ev)
        } catch (e: Exception) {
            log.warn("questions → dashboards: ${e.message}")
        }
    }

    companion object {
        const val MAX_TEXT = 500
        const val MAX_BODY = 8192
        const val MIN_GAP_MS = 1500L
        const val MAX_PER_INCIDENT = 30

        private val CHAT_LANGS = listOf(Lang.EN, Lang.DE, Lang.FR, Lang.IT)
        private val Lang.wire: String get() = name.lowercase()

        private val REPLY_JSON = Json { explicitNulls = false; encodeDefaults = true }

        /** engine.ts's key order (`{...p, lang, topic, station, urgent, cluster}`, then `classifiedBy`). */
        private val REPLY_KEYS = listOf("text", "source", "citedUpdateIds", "lang", "topic", "station", "urgent", "cluster", "classifiedBy")

        fun replyJson(reply: Reply): JsonObject {
            val o = REPLY_JSON.encodeToJsonElement(Reply.serializer(), reply).jsonObject
            return JsonObject(LinkedHashMap<String, JsonElement>().apply {
                for (k in REPLY_KEYS) o[k]?.let { put(k, it) }
                for ((k, v) in o) if (k !in this) put(k, v)
            })
        }

        /** JavaScript's `String(x ?? '')` for a JSON value. */
        private fun jsString(e: JsonElement?): String = when (e) {
            null, JsonNull -> ""
            is JsonPrimitive -> when {
                e.isString -> e.content
                e.content == "true" || e.content == "false" -> e.content
                else -> e.content.toDoubleOrNull()?.let(::jsNumberString) ?: e.content
            }
            is JsonArray -> e.joinToString(",") { if (it == JsonNull) "" else jsString(it) }
            is JsonObject -> "[object Object]"
        }

        private val ISO_MS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** JavaScript's `new Date().toISOString()`. */
        fun isoNow(): String = ISO_MS.format(Instant.now())
    }
}
