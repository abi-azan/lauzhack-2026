package railstatus.server

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import railstatus.model.*

/**
 * Turns the control centre's internal ticker (mostly French and German staff shorthand) into a
 * passenger-facing status update in four languages, via one structured-output call on OpenRouter.
 * The admin still reviews and publishes it: the result is a draft, never sent on its own.
 */
class AiDrafter(
    private val apiKey: String,
    private val model: String,
    /** The chat's open question groups for an incident, as "label: N passengers" lines (never passengers' own words). */
    private val questions: suspend (incidentId: String) -> List<String> = { emptyList() },
) : Drafter {
    private val http = HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 45_000 }
        expectSuccess = false
    }

    override suspend fun draft(inc: AdminIncident, trains: List<Train>): PublishUpdateRequest {
        val asked = runCatching { questions(inc.facts.id) }.getOrDefault(emptyList())
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            put("max_tokens", 2000)
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "status_update")
                    put("strict", true)
                    put("schema", SCHEMA)
                }
            }
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", SYSTEM) }
                addJsonObject { put("role", "user"); put("content", context(inc, trains, asked)) }
            }
        }
        val res = http.post("https://openrouter.ai/api/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = res.bodyAsText()
        require(res.status.isSuccess()) { "AI draft failed: HTTP ${res.status.value} ${text.take(200)}" }
        val content = RailJson.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0]
            .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        val out = RailJson.parseToJsonElement(content.substringAfter("```json").substringBefore("```").trim()).jsonObject
        log.info("AI draft for ${inc.facts.id} from ${inc.ticker.size} ticker entries (${model})")

        val stage = when (out["stage"]?.jsonPrimitive?.content) {
            "identified" -> Stage.IDENTIFIED
            "monitoring" -> Stage.MONITORING
            "resolved" -> Stage.RESOLVED
            else -> Stage.INVESTIGATING
        }
        val next = out["nextUpdateInMin"]?.jsonPrimitive?.intOrNull?.coerceIn(10, 30) ?: 15
        val vars = mapOf("next" to now().plusMinutes(next.toLong()).hhmm())
        val cause = inc.ticker.firstNotNullOfOrNull { it.reasonText }
        return guard(PublishUpdateRequest(
            stage = stage,
            title = text4(out["title"]).fill(vars),
            body = text4(out["body"]).fill(vars),
            nextUpdateInMin = if (stage == Stage.RESOLVED) null else next,
            cause = if (stage == Stage.INVESTIGATING) null else cause,
            alternatives = out["alternatives"]?.jsonArray?.mapIndexed { i, a -> Alternative("ai-${i + 1}", text4(a)) },
            templateId = "ai",
            draftedBy = DraftInfo("single-call", model = model),
        ), placesOf(inc, trains))
    }

    /**
     * The output guard: anything in the draft that looks like personal data (a phone number, an e-mail address,
     * a staff ID, a titled name) is replaced by a placeholder the admin sees before publishing. The prompt
     * already went out scrubbed, so this only fires if the model invents one.
     */
    internal fun guard(req: PublishUpdateRequest, places: Collection<String>): PublishUpdateRequest {
        val hits = mutableListOf<Pii.Kind>()
        fun clean(t: Text): Text = t.mapValues { (_, s) ->
            val found = Pii.find(s, keep = places)
            if (found.isEmpty()) s else { hits += found.map { it.kind }; Pii.scrub(s, keep = places) }
        }
        val out = req.copy(
            title = clean(req.title),
            body = clean(req.body),
            alternatives = req.alternatives?.map { it.copy(text = clean(it.text)) },
        )
        if (hits.isNotEmpty()) log.warn("AI draft: removed personal data from the model's output: ${hits.groupingBy { it }.eachCount()}")
        return out
    }

    private fun text4(e: JsonElement?): Text {
        val o = e?.jsonObject ?: return emptyMap()
        return Lang.entries.mapNotNull { l -> o[l.name.lowercase()]?.jsonPrimitive?.content?.let { l to it } }.toMap()
    }

    /** Station names stay in the text even where they'd look like a name ("Kontakt Renens VD"). */
    private fun placesOf(inc: AdminIncident, trains: List<Train>): Set<String> =
        (trains.flatMap { t -> t.stops.map { it.station.name } } +
            listOfNotNull(inc.facts.section, inc.location).flatMap { listOf(it.from.name, it.to.name) }).toSet()

    /** The user message: the incident's facts and its internal ticker, with personal data scrubbed ([Pii]). */
    internal fun context(inc: AdminIncident, trains: List<Train>, asked: List<String> = emptyList()): String = buildString {
        val places = placesOf(inc, trains)
        val f = inc.facts
        appendLine("Now: ${now().hhmm()}")
        appendLine("Affected line: ${f.section?.let { "${it.from.name} – ${it.to.name}" } ?: "unknown"}")
        inc.location?.let { appendLine("Location: between ${it.from.name} and ${it.to.name}") }
        appendLine("Incident detected: ${inc.detectedAt.substring(11, 16)}")
        inc.ticker.firstNotNullOfOrNull { it.reasonText?.get(Lang.EN) }?.let { appendLine("Cause reported by the source system: $it") }
        appendLine("Affected trains: " + f.trainIds.mapNotNull { id -> trains.find { it.id == id } }
            .joinToString("; ") { "${it.line} ${it.number} to ${it.direction}" })
        appendLine("Current published stage: ${f.stage.name.lowercase()}")
        if (f.updates.isNotEmpty()) {
            appendLine("Already published to passengers:")
            f.updates.forEach { appendLine("- ${it.publishedAt.substring(11, 16)} ${it.title[Lang.EN]}: ${it.body[Lang.EN]}") }
        }
        appendLine("Internal control-centre ticker, oldest first (staff shorthand, not for passengers as-is):")
        inc.ticker.forEach { appendLine("- ${it.ts.substring(11, 16)} [${it.source.name}] ${Pii.scrub(it.text, keep = places).replace('\n', ' ')}") }
        if (asked.isNotEmpty()) {
            appendLine("What passengers are asking in the support chat (question groups, most asked first):")
            asked.forEach { appendLine("- $it") }
            appendLine("Answer the most asked ones in this update where the data above states the answer; otherwise say plainly it isn't known yet.")
        }
    }

    // internal, not private: the Koog draft agent (T17, kmp/agent) reuses SYSTEM.
    internal companion object {
        private val log = LoggerFactory.getLogger(AiDrafter::class.java)

        val SYSTEM = """
            You write the next status update for passengers on trains hit by a rail disruption in Switzerland,
            from the control centre's internal ticker. A human checks your draft before it is published.

            Rules:
            - Use only facts from the incident data and the ticker. Never invent times, platforms, bus stops,
              durations or numbers. If something isn't known yet, say so plainly.
            - The ticker is staff shorthand ("18474 rebrousse à Lausanne", "COLGEN"). Translate it into what it
              means for passengers. Don't list internal train numbers or staff names.
            - Never write personal data: no names of people, phone numbers, e-mail addresses, staff IDs or radio
              call signs, even if the ticker has them. Placeholders like [name] or [tel.] mark data that was
              removed; leave them out too.
            - If replacement buses are ordered but no departure time or stop is known, say buses are being
              organised, without a time.
            - Stage: "investigating" while the cause is unclear; "identified" when the cause is known;
              "monitoring" when a working alternative (detour, buses running) exists; "resolved" only if the
              ticker says traffic is running again.
            - title: at most 60 characters. body: 2 to 3 short, calm sentences, then the next-update sentence in
              that body's language, with the literal placeholder {next} (it's filled in later):
              de "Weitere Informationen erhalten Sie um zirka {next}.", fr "Vous recevrez d’autres informations vers {next}.",
              it "Ulteriori informazioni verso le {next}.", en "Further information at approximately {next}."
              (the industry's standard wording, BS-KI)
              For "resolved", leave out the next-update sentence.
            - alternatives: concrete things passengers can do, only if the ticker supports them; else [].
            - Write the same content in German (de), French (fr), Italian (it) and English (en), naturally, the
              way a Swiss railway would write it. Don't add anything that isn't in the other languages.
            - nextUpdateInMin: 10 to 30, shorter while the situation is moving fast.
        """.trimIndent()

        private fun langs() = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { listOf("de", "fr", "it", "en").forEach { add(it) } }
            putJsonObject("properties") { listOf("de", "fr", "it", "en").forEach { putJsonObject(it) { put("type", "string") } } }
        }

        val SCHEMA = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { listOf("stage", "title", "body", "alternatives", "nextUpdateInMin").forEach { add(it) } }
            putJsonObject("properties") {
                putJsonObject("stage") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("investigating", "identified", "monitoring", "resolved").forEach { add(it) } }
                }
                put("title", langs())
                put("body", langs())
                putJsonObject("alternatives") { put("type", "array"); put("items", langs()) }
                putJsonObject("nextUpdateInMin") { put("type", "integer") }
            }
        }
    }
}
