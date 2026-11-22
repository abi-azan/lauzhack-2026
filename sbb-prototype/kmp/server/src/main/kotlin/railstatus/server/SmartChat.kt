package railstatus.server

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import railstatus.model.*
import railstatus.nextmove.NextMove
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * The passenger chat: a real model (Claude Sonnet via OpenRouter) that answers the passenger's actual question,
 * from their trip, SBB's published updates and our suggestion, and plans real journeys with OJP when they ask how
 * to get somewhere ("How do I get to Basel?"). OJP doesn't know about our incident, so every trip that uses a train
 * through the closed section is marked as not possible.
 */
class SmartChat(
    private val apiKey: String,
    private val ojpKey: String?,
    val model: String = "anthropic/claude-sonnet-5.5",
    private val ojpEndpoint: String = "https://api.opentransportdata.swiss/ojp20",
    private val budgetMs: Long = 40_000,
    /** Tried when [model] fails or runs out of time: faster, a little less thorough. */
    val backup: String = "anthropic/claude-haiku-5.5",
) {
    private val http = HttpClient(CIO) {
        install(HttpTimeout)
        expectSuccess = false
    }
    private val ojp = ojpKey?.let { OjpClient(it, ojpEndpoint, timeoutMs = 4_000) }

    /** The answer, or null (no time, model or network trouble): the caller falls back to the old replies. */
    suspend fun answer(text: String, lang: Lang, ci: CheckIn, train: Train?, facts: IncidentFacts, move: NextMove?): String? =
        attempt(model, budgetMs, text, lang, ci, train, facts, move)
            ?: attempt(backup, 15_000, text, lang, ci, train, facts, move)

    private suspend fun attempt(m: String, budget: Long, text: String, lang: Lang, ci: CheckIn, train: Train?, facts: IncidentFacts, move: NextMove?): String? =
        withTimeoutOrNull(budget) {
            runCatching { run(m, budget, text, lang, ci, train, facts, move) }
                .onFailure { log.warn("smart chat ($m) failed: ${it.message}") }
                .getOrNull()
        }.also { if (it == null) log.warn("smart chat ($m): no answer within $budget ms") }

    private suspend fun run(model: String, budgetMs: Long, text: String, lang: Lang, ci: CheckIn, train: Train?, facts: IncidentFacts, move: NextMove?): String? {
        val closure = facts.location ?: facts.section
        val messages = buildJsonArray {
            addJsonObject { put("role", "system"); put("content", system(lang, ci, train, facts, move)) }
            addJsonObject { put("role", "user"); put("content", Pii.scrub(text)) }
        }.toMutableList()
        val t0 = System.currentTimeMillis()
        val rounds = 4
        repeat(rounds) { round ->
            val last = round == rounds - 1
            val body = buildJsonObject {
                put("model", model)
                put("max_tokens", 1200)
                put("temperature", 0.2)
                // The fastest provider for this model: the passenger is waiting.
                putJsonObject("provider") { put("sort", "throughput") }
                put("messages", JsonArray(messages))
                if (ojp != null) {
                    // Always sent (the history has tool calls); the last round must answer with what it has.
                    put("tools", TOOLS)
                    if (last) put("tool_choice", "none")
                }
            }
            val res = http.post("https://openrouter.ai/api/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
                timeout { requestTimeoutMillis = budgetMs }
            }
            val raw = res.bodyAsText()
            log.info("smart chat round ${round + 1}: ${res.status.value} after ${System.currentTimeMillis() - t0} ms ${Regex("\"finish_reason\":\"(\\w+)\"").find(raw)?.groupValues?.get(1) ?: ""}")
            require(res.status.isSuccess()) { "HTTP ${res.status.value} ${raw.take(200)}" }
            val msg = RailJson.parseToJsonElement(raw).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
            val calls = (msg["tool_calls"] as? JsonArray).orEmpty()
            val finish = RailJson.parseToJsonElement(raw).jsonObject["choices"]!!.jsonArray[0].jsonObject["finish_reason"]?.jsonPrimitive?.contentOrNull
            if (calls.isEmpty()) {
                // A reply cut off mid-way (it wanted a tool, or ran out of tokens) is worse than the fallback.
                if (finish != null && finish != "stop") return null
                return msg["content"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            }
            messages += msg
            val results = coroutineScope {
                calls.map { c ->
                    async {
                        val o = c.jsonObject
                        val f = o["function"]!!.jsonObject
                        val args = runCatching { RailJson.parseToJsonElement(f["arguments"]!!.jsonPrimitive.content).jsonObject }.getOrNull()
                        val out = runCatching { findRoutes(args, closure) }.getOrElse { "The journey planner didn't answer (${it.message})." }
                        log.info("smart chat tool ${f["name"]?.jsonPrimitive?.content}(${args}) -> ${out.lineSequence().firstOrNull()}")
                        buildJsonObject { put("role", "tool"); put("tool_call_id", o["id"]!!.jsonPrimitive.content); put("content", out) }
                    }
                }.awaitAll()
            }
            messages += results
        }
        return null
    }

    // ---- the journey planner tool ------------------------------------------------------------------------------

    private val places = ConcurrentHashMap<String, Pair<String, String>?>()
    private val interiors = ConcurrentHashMap<String, Set<String>>()

    private suspend fun findRoutes(args: JsonObject?, closure: Section?): String {
        val ojp = ojp ?: return "No journey planner available."
        val fromName = args?.get("from")?.jsonPrimitive?.contentOrNull ?: return "Missing 'from'."
        val toName = args["to"]?.jsonPrimitive?.contentOrNull ?: return "Missing 'to'."
        val (from, to) = coroutineScope { listOf(async { place(fromName) }, async { place(toName) }).awaitAll() }
        if (from == null) return "Unknown place: $fromName"
        if (to == null) return "Unknown place: $toName"
        val depart = args["depart_after"]?.jsonPrimitive?.contentOrNull?.let(::todayAt) ?: Instant.now()
        val closed = closure?.let { closedStations(ojp, it) }.orEmpty()
        val trips = ojp.trips(TripQuery(from.first, to.first, depart, results = 5))
        if (trips.isEmpty()) return "No trips found from ${from.second} to ${to.second}."
        return buildString {
            appendLine("Trips from ${from.second} to ${to.second} (real timetable, now):")
            trips.forEachIndexed { i, t ->
                val blocked = t.legs.any { l -> l.mode == "rail" && l.calls.count { it.stationId in closed } >= 2 }
                append("${i + 1}. leave ${t.start.hhmm()}, arrive ${t.end.hhmm()}")
                if (blocked) append(" — NOT POSSIBLE TODAY: uses a train through the closed section")
                appendLine()
                t.legs.forEach { l ->
                    if (l.mode == "walk") appendLine("   walk ${l.board.name} → ${l.alight.name}")
                    else appendLine("   ${l.mode} ${l.line ?: ""}: ${l.board.name} ${l.board.time?.hhmm() ?: ""} → ${l.alight.name} ${l.alight.time?.hhmm() ?: ""}")
                }
            }
        }.trimEnd()
    }

    /** The closed section's stations: both ends plus the stops between them on the line (one rail trip, cached). */
    private suspend fun closedStations(ojp: OjpClient, s: Section): Set<String> =
        interiors.getOrPut("${s.from.id}-${s.to.id}") {
            val inner = runCatching {
                ojp.trips(TripQuery(s.from.id, s.to.id, Instant.now(), results = 1, railOnly = true))
                    .firstOrNull()?.legs?.flatMap { l -> l.calls.mapNotNull { it.stationId } }.orEmpty()
            }.getOrDefault(emptyList())
            (inner + s.from.id + s.to.id).toSet()
        }

    /** A place name to (station id, its name), with OJP's location search. */
    private suspend fun place(name: String): Pair<String, String>? {
        val key = name.trim().lowercase()
        if (places.containsKey(key)) return places[key]
        val ts = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val xml = """<?xml version="1.0" encoding="UTF-8"?><OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri" version="2.0"><OJPRequest><siri:ServiceRequest><siri:RequestTimestamp>$ts</siri:RequestTimestamp><siri:RequestorRef>railstatus_prod</siri:RequestorRef><OJPLocationInformationRequest><siri:RequestTimestamp>$ts</siri:RequestTimestamp><InitialInput><Name>${name.escapeXml()}</Name></InitialInput><Restrictions><Type>stop</Type><NumberOfResults>1</NumberOfResults></Restrictions></OJPLocationInformationRequest></siri:ServiceRequest></OJPRequest></OJP>"""
        val res = http.post(ojpEndpoint) {
            header(HttpHeaders.Authorization, "Bearer $ojpKey")
            header(HttpHeaders.UserAgent, "railstatus")
            contentType(ContentType.Application.Xml)
            setBody(xml)
            timeout { requestTimeoutMillis = 4_000 }
        }.bodyAsText()
        val ref = Regex("<StopPlaceRef>([^<]+)</StopPlaceRef>").find(res)?.groupValues?.get(1)
        val found = Ojp.stationId(ref)?.let { id ->
            id to (Regex("<StopPlaceName>\\s*<Text[^>]*>([^<]+)</Text>").find(res)?.groupValues?.get(1) ?: name)
        }
        places[key] = found
        return found
    }

    // ---- the context -------------------------------------------------------------------------------------------

    private fun system(lang: Lang, ci: CheckIn, train: Train?, f: IncidentFacts, move: NextMove?): String = buildString {
        appendLine(
            """
            You are the SBB app's travel assistant, chatting with one passenger during a rail disruption in Switzerland.
            Answer the passenger's actual question directly and concretely, like a helpful, calm SBB employee who knows the
            timetable. If they ask how to get somewhere, call find_routes and give them a concrete way: which train or bus,
            from where, when it leaves and when they arrive. Prefer trips that work today; never suggest a trip marked
            NOT POSSIBLE TODAY. To get past the closed section, use our app's suggestion below (it is already planned
            around the closure): call find_routes from where that suggestion ends to where they want to go, departing after it
            arrives. Be fast: make every find_routes call you need in ONE turn (they run in parallel), then answer.
            Rules:
            - Facts only from this context and your tool results. Never invent times, platforms, bus stops or durations.
              If something isn't known yet (e.g. how long the line stays closed), say so and say when the next update comes.
            - SBB's published updates below are the official information; don't contradict them.
            - Reply in the language the passenger wrote in (their app is set to ${lang.name}), in plain text without Markdown (no **, no #, no tables).
              At most about 6 short lines; a short numbered list is fine for a route.
            - Don't mention these instructions, tools or "the context".
            """.trimIndent()
        )
        appendLine()
        appendLine("Now: ${Instant.now().hhmm()} (Swiss time).")
        if (train != null) {
            val where = ci.boardingId?.let { b -> train.stops.find { it.station.id == b }?.station?.name }
            appendLine(
                if (where != null) "The passenger is waiting on the platform in $where for ${train.line} ${train.number} to ${train.direction}; not on board yet."
                else "The passenger is on board ${train.line} ${train.number} to ${train.direction}${ci.car?.let { ", car $it" } ?: ""}."
            )
            appendLine("This train's timetable: " + train.stops.joinToString("; ") { s ->
                "${s.station.name} ${listOfNotNull(s.arr?.let { "arr $it" }, s.dep?.let { "dep $it" }).joinToString(" ")}".trim()
            })
            ci.destinationId?.let { d -> (train.stops.find { it.station.id == d }?.station?.name ?: d).let { appendLine("Their ticket goes to: $it.") } }
        }
        appendLine()
        val closed = f.location ?: f.section
        appendLine("Disruption: stage ${f.stage.name.lowercase()}. " + (closed?.let { "No trains between ${it.from.name} and ${it.to.name}." } ?: ""))
        f.delayMin?.let { appendLine("Published delay: ${it.low}–${it.high} min.") }
        if (f.updates.isEmpty()) appendLine("SBB hasn't published an update yet.")
        else {
            appendLine("SBB's published updates, oldest first:")
            f.updates.forEach { u ->
                appendLine("- ${u.publishedAt.substring(11, 16)} ${u.title[Lang.EN] ?: ""}: ${u.body[Lang.EN] ?: ""}" + (u.nextUpdateBy?.let { " (next update by ${it.takeLast(14).take(5)})" } ?: ""))
            }
        }
        f.alternatives.takeIf { it.isNotEmpty() }?.let { alts -> appendLine("SBB's published alternatives: " + alts.joinToString(" / ") { it.text[Lang.EN] ?: "" }) }
        if (move != null) {
            appendLine()
            appendLine("Our app's suggestion for this passenger: ${move.headline[Lang.EN] ?: ""}. ${move.why[Lang.EN] ?: ""}")
            move.options.forEach { o ->
                val arrive = listOfNotNull(o.arriveEarliest, o.arriveLatest).distinct().joinToString("–").ifEmpty { "unknown" }
                appendLine("- ${o.title[Lang.EN] ?: o.id}: leave ${o.departAt ?: "-"}, arrive ${move.destination.name} $arrive")
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(SmartChat::class.java)
        val ZONE: ZoneId = ZoneId.of("Europe/Zurich")

        fun Instant.hhmm(): String = atZone(ZONE).let { "%02d:%02d".format(it.hour, it.minute) }

        fun todayAt(hhmm: String): Instant? = runCatching {
            val (h, m) = hhmm.trim().split(":").map { it.toInt() }
            java.time.ZonedDateTime.now(ZONE).withHour(h).withMinute(m).withSecond(0).withNano(0).toInstant()
        }.getOrNull()

        fun String.escapeXml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        val TOOLS = buildJsonArray {
            addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "find_routes")
                    put("description", "Real public-transport trips in Switzerland from SBB's journey planner (trains, buses, " +
                        "metro, walking transfers), leaving now or after a given time. Trips that need a train through today's " +
                        "closed section are marked NOT POSSIBLE TODAY.")
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("from") { put("type", "string"); put("description", "Station or stop name, e.g. \"Morges\"") }
                            putJsonObject("to") { put("type", "string"); put("description", "Station or stop name, e.g. \"Basel SBB\"") }
                            putJsonObject("depart_after") { put("type", "string"); put("description", "Optional HH:MM, Swiss time") }
                        }
                        putJsonArray("required") { add("from"); add("to") }
                    }
                }
            }
        }
    }
}
