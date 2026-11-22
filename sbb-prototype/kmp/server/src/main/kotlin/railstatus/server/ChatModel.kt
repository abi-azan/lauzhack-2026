package railstatus.server

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import railstatus.chat.LlmPrompt
import railstatus.chat.Place
import railstatus.chat.Topic
import railstatus.chat.norm
import railstatus.model.IncidentFacts
import railstatus.model.Station
import railstatus.model.Train
import java.util.concurrent.ConcurrentHashMap

/**
 * The chat's two optional OpenRouter steps (port of the old Node service's classify.ts and its model answer).
 * Only built when OPENROUTER_API_KEY is set; without it the chat answers from the rules alone.
 *
 * 1. [classify]: for questions the keyword rules call `other`. It only picks a topic and a station; the answer
 *    still comes from the engine's templates over the published facts. Research: docs/chat-plan.md §7.0–7.2.
 * 2. [answer]: only if the classifier also says `other`. One JSON call over the published facts, grounded by
 *    the caller (parseLlm + groundCheck) or dropped.
 */
class ChatModel(
    private val apiKey: String,
    /** Picked in docs/chat-plan.md §7.2–7.3: glm-5.3-flash classified 104/104 at ~$0.05 per 1k. */
    val classifyModel: String = "z-ai/glm-5.3-flash",
    /** Luna gave the tightest grounded answers. */
    val answerModel: String = "openai/gpt-6-luna",
    private val http: HttpClient = HttpClient(CIO) {
        install(HttpTimeout)
        expectSuccess = false
    },
) {
    data class Classified(val topic: Topic, val station: Place?)

    /** Per train and question; an "other" answer is cached too (as null), a failed call isn't. */
    private val cache = ConcurrentHashMap<String, Cached>()

    /** ConcurrentHashMap takes no null values. */
    private class Cached(val hit: Classified?)

    /**
     * One structured-output call. Null when it fails, times out or answers "other", so the caller keeps the
     * rules' reply. A wrong topic can only pick the wrong template, which still answers from the published
     * facts, and the rules have already ruled out the safety topics they know.
     */
    suspend fun classify(text: String, train: Train?, facts: IncidentFacts?, onCall: () -> Unit = {}): Classified? {
        val stations = stationsOf(train, facts)
        val cacheKey = "${train?.id ?: ""}|${norm(text)}"
        cache[cacheKey]?.let { return it.hit }
        var hit: Classified? = null
        var answered = false
        try {
            onCall()
            val system = """You classify one message from a passenger on a stopped train during a disruption. Return JSON only.
train: ${if (train != null) "${train.line} ${train.number} to ${train.direction}" else "unknown"}
topic, one of:
$GUIDE
station: the id of the station the message is about, from this list only (match other languages, abbreviations and airport codes, e.g. GVA or Cointrin = Genève-Aéroport); "" if none:
${stations.joinToString("; ") { "${it.id}=${it.name}" }}"""
            val body = buildJsonObject {
                put("model", classifyModel)
                put("temperature", 0)
                put("max_tokens", 400)
                putJsonArray("messages") {
                    addJsonObject { put("role", "system"); put("content", system) }
                    addJsonObject { put("role", "user"); put("content", text) }
                }
                putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", "classified")
                        put("strict", true)
                        putJsonObject("schema") {
                            put("type", "object")
                            put("additionalProperties", false)
                            putJsonArray("required") { add("topic"); add("station") }
                            putJsonObject("properties") {
                                putJsonObject("topic") {
                                    put("type", "string")
                                    putJsonArray("enum") { TOPICS.forEach { add(it.wire) } }
                                }
                                putJsonObject("station") {
                                    put("type", "string")
                                    putJsonArray("enum") { add(""); stations.forEach { add(it.id) } }
                                }
                            }
                        }
                    }
                }
            }
            val content = complete(body, timeoutMs = 4000)
            val out = Json.parseToJsonElement(content).jsonObject
            val topicWire = (out["topic"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val stationId = (out["station"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val topic = TOPICS.firstOrNull { it.wire == topicWire }
            if (topic != null && topic != Topic.OTHER) hit = Classified(topic, stations.find { it.id == stationId }?.let { Place(it.id, it.name) })
            answered = topic != null
        } catch (e: Exception) {
            log.warn("classifier failed: ${e.message}")
        }
        // A failed or timed-out call isn't cached (review C1): the next passenger asking it gets another try.
        if (answered) cache[cacheKey] = Cached(hit)
        return hit
    }

    /** The model's raw answer to an "other" question (`choices[0].message.content`, "" if missing). Throws on failure. */
    suspend fun answer(prompt: LlmPrompt): String {
        val body = buildJsonObject {
            put("model", answerModel)
            put("max_tokens", 300)
            putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", prompt.system) }
                addJsonObject { put("role", "user"); put("content", prompt.user) }
            }
        }
        return complete(body, timeoutMs = 8000)
    }

    /** POST to OpenRouter; returns `choices[0].message.content`, or "" when the reply has none. */
    private suspend fun complete(body: JsonObject, timeoutMs: Long): String {
        val res = http.post("https://openrouter.ai/api/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
            timeout { requestTimeoutMillis = timeoutMs }
        }
        val j = Json.parseToJsonElement(res.bodyAsText())
        val content = ((((j as? JsonObject)?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("message") as? JsonObject)?.get("content") as? JsonPrimitive
        return content?.takeIf { it.isString }?.content ?: ""
    }

    /** The stations the model may name: this train's stops and the incident's section. */
    private fun stationsOf(train: Train?, facts: IncidentFacts?): List<Station> {
        val all = (train?.stops?.map { it.station } ?: emptyList()) +
            (facts?.section?.let { listOf(it.from, it.to) } ?: emptyList())
        // A JavaScript Map: the first occurrence keeps its place, the last one's name wins.
        val byId = LinkedHashMap<String, Station>()
        for (s in all) byId[s.id] = s
        return byId.values.toList()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChatModel::class.java)

        val TOPICS = listOf(
            Topic.EMERGENCY, Topic.ACCESSIBILITY, Topic.LEAVE_TRAIN, Topic.REFUND, Topic.CONNECTION, Topic.ALTERNATIVE,
            Topic.NEXT_UPDATE, Topic.DELAY, Topic.CAUSE, Topic.ONBOARD, Topic.GREETING, Topic.OTHER,
        )

        private val GUIDE = """- emergency: someone ill or hurt, smoke, fire, burning smell, danger
- accessibility: wheelchair, reduced mobility, blind or visually impaired, needs assistance
- leave_train: getting off here, opening doors, walking along the tracks or to a station
- refund: money back, compensation, ticket validity, taxi or hotel costs
- connection: catching an onward train, bus or flight
- alternative: other routes, replacement buses, detours
- next_update: when the next information comes
- delay: how long, when the train continues, arrival time at a stop
- cause: why the train stopped, what happened
- onboard: water, heating or air conditioning, toilets, food, wifi, comfort on this train
- greeting: hello, thanks, ok
- other: anything else"""
    }
}
