@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.incident

import io.ktor.client.HttpClient
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import railstatus.chat.Source
import railstatus.chat.Topic
import railstatus.chat.jsDateParse
import railstatus.chat.jsTrim
import railstatus.model.CheckInRequest
import railstatus.model.CheckInResponse
import railstatus.model.IncidentFacts
import railstatus.model.Lang
import railstatus.model.RailJson
import railstatus.model.Text
import railstatus.model.Train
import railstatus.model.WsEvent
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Port of mobile/src/incident/live.ts: the passenger side of the backend (sbb-prototype/kmp). The JSON shapes
// are the backend's own classes (railstatus.model). This file only talks to the network; PassengerStore decides
// what to do with it. Web Push isn't ported: natively, notifications come from the WebSocket.

/** The WebSocket's state, as the demo controls show it. */
enum class Conn { OFF, CONNECTING, OPEN, RETRYING }

/** Layer.tsx `CONN_LABEL`: the connection pill of the demo controls. */
fun connLabel(c: Conn): String = when (c) {
    Conn.OFF -> "○ live: not checked in"
    Conn.CONNECTING -> "◌ connecting"
    Conn.OPEN -> "● live"
    Conn.RETRYING -> "◌ reconnecting"
}

/** A REST call answered with a non-2xx status. */
class BackendException(val status: Int, message: String) : Exception(message)

/** What the chat service answered (`reply` of `POST /api/chat/{token}`). Unknown topics or sources come back null. */
data class ServiceReply(
    val text: String,
    val source: Source?,
    val topic: Topic?,
    val lang: Lang?,
    /** "rules", or "llm" when the chat service's model classifier picked the topic. */
    val classifiedBy: String?,
    /** The whole reply, for anything else it carries (station, cluster, citedUpdateIds, urgent). */
    val json: JsonObject,
)

sealed interface ChatPost {
    data class Answered(val reply: ServiceReply) : ChatPost
    /** HTTP status, or 0 when the request didn't get through (network, timeout, bad JSON). */
    data class Failed(val status: Int) : ChatPost
}

/** Callbacks of the passenger WebSocket. They run in the scope the socket was opened with. */
interface PassengerSocketListener {
    /** `hello`, `incident.update` or `reset`. */
    fun event(e: WsEvent)
    /** [Conn.OPEN] or [Conn.RETRYING]. */
    fun state(s: Conn)
    /** Close code 1008: the backend no longer knows the token (a demo reset or a restart). */
    fun rejected()
}

/** The backend calls the passenger app makes. [KtorPassengerBackend] is the real one. */
interface PassengerBackend {
    /** live.ts `probeSameOrigin`: does [api] answer `GET /api/trains` with JSON? */
    suspend fun probe(api: String): Boolean
    suspend fun getTrain(api: String, id: String): Train
    /** `GET /api/trains`: every train the backend knows (the station screen lists departures from them). */
    suspend fun getTrains(api: String): List<Train> = emptyList()
    suspend fun postCheckIn(api: String, body: CheckInRequest): CheckInResponse
    /** Best effort: never throws. */
    suspend fun deleteCheckIn(api: String, token: String)
    /** `POST /api/chat/{token}`: the backend forwards it to the chat service (sbb-prototype/chat). Never throws. */
    suspend fun postChat(api: String, token: String, text: String, lang: Lang): ChatPost
    /**
     * `/ws/passenger?token=…`, reconnecting with backoff (1 s, 2 s, 4 s … 10 s). A reconnect is harmless: the
     * server's `hello` resends the current state. Returns the function that closes it for good.
     */
    fun openPassengerSocket(scope: CoroutineScope, api: String, token: String, on: PassengerSocketListener): () -> Unit
}

/** [PassengerBackend] over a Ktor [HttpClient]; each platform supplies the engine. WebSockets is installed if missing. */
class KtorPassengerBackend(http: HttpClient) : PassengerBackend {
    private val http: HttpClient = if (http.pluginOrNull(WebSockets) != null) http else http.config { install(WebSockets) }

    private suspend fun check(res: HttpResponse, method: String, url: String): HttpResponse {
        if (!res.status.isSuccess()) throw BackendException(res.status.value, "$method $url: ${res.status.value}")
        return res
    }

    override suspend fun probe(api: String): Boolean = try {
        val res = http.get("$api/api/trains") { header(HttpHeaders.Accept, "application/json") }
        res.status.isSuccess() && (res.headers[HttpHeaders.ContentType]?.contains("json") == true)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    override suspend fun getTrain(api: String, id: String): Train {
        val url = "$api/api/trains/${encodeURIComponent(id)}"
        val res = check(http.get(url) { header(HttpHeaders.Accept, "application/json") }, "GET", url)
        return RailJson.decodeFromString(Train.serializer(), res.bodyAsText())
    }

    override suspend fun getTrains(api: String): List<Train> {
        val url = "$api/api/trains"
        val res = check(http.get(url) { header(HttpHeaders.Accept, "application/json") }, "GET", url)
        return RailJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Train.serializer()), res.bodyAsText())
    }

    override suspend fun postCheckIn(api: String, body: CheckInRequest): CheckInResponse {
        val url = "$api/api/checkins"
        val res = check(http.post(url) {
            header(HttpHeaders.Accept, "application/json")
            setBody(TextContent(RailJson.encodeToString(CheckInRequest.serializer(), body), ContentType.Application.Json))
        }, "POST", url)
        return RailJson.decodeFromString(CheckInResponse.serializer(), res.bodyAsText())
    }

    override suspend fun deleteCheckIn(api: String, token: String) {
        try {
            http.delete("$api/api/checkins/${encodeURIComponent(token)}")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // best effort
        }
    }

    override suspend fun postChat(api: String, token: String, text: String, lang: Lang): ChatPost = try {
        withTimeoutOrNull(60_000) { // the chat model plans real journeys: usually 5-10 s, at worst about 45 s
            // Sent as text/plain, like the web app (a "simple" request there, so browsers skip the CORS preflight).
            val body = buildJsonObject { put("text", text); put("lang", RailJson.encodeToJsonElement(Lang.serializer(), lang)) }
            val res = http.post("$api/api/chat/${encodeURIComponent(token)}") {
                setBody(TextContent(body.toString(), ContentType.parse("text/plain;charset=UTF-8")))
            }
            if (!res.status.isSuccess()) return@withTimeoutOrNull ChatPost.Failed(res.status.value)
            val reply = (RailJson.parseToJsonElement(res.bodyAsText()) as? JsonObject)?.get("reply") as? JsonObject
            val replyText = (reply?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (reply == null || replyText.isNullOrEmpty()) ChatPost.Failed(502)
            else ChatPost.Answered(serviceReply(replyText, reply))
        } ?: ChatPost.Failed(0)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ChatPost.Failed(0)
    }

    private fun serviceReply(text: String, j: JsonObject): ServiceReply {
        fun str(key: String) = (j[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return ServiceReply(
            text = text,
            source = Source.fromWire(str("source")),
            topic = Topic.fromWire(str("topic")),
            lang = Lang.entries.firstOrNull { it.name.lowercase() == str("lang") },
            classifiedBy = str("classifiedBy"),
            json = j,
        )
    }

    override fun openPassengerSocket(scope: CoroutineScope, api: String, token: String, on: PassengerSocketListener): () -> Unit {
        val url = "${api.replaceFirst(Regex("^http"), "ws")}/ws/passenger?token=${encodeURIComponent(token)}"
        val job = scope.launch {
            var tries = 0
            while (true) {
                var code: Short? = null
                var session: DefaultClientWebSocketSession? = null
                try {
                    session = http.webSocketSession(url)
                    tries = 0
                    on.state(Conn.OPEN)
                    for (frame in session.incoming) {
                        if (frame !is Frame.Text) continue
                        // Ignore malformed frames and event types the passenger app doesn't know.
                        val e = try { RailJson.decodeFromString(WsEvent.serializer(), frame.readText()) } catch (_: Exception) { null }
                        if (e != null) on.event(e)
                    }
                    code = session.closeReason.await()?.code
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Couldn't connect, or the connection dropped: retry.
                } finally {
                    session?.let { s -> withContext(NonCancellable) { try { s.close() } catch (_: Exception) { } } }
                }
                if (code == CLOSE_POLICY_VIOLATION) { on.rejected(); return@launch }
                on.state(Conn.RETRYING)
                delay(min(10_000.0, 1000.0 * 2.0.pow(tries++)).toLong())
            }
        }
        return { job.cancel() }
    }

    private companion object {
        const val CLOSE_POLICY_VIOLATION: Short = 1008
    }
}

/** `encodeURIComponent`. */
internal fun encodeURIComponent(s: String): String {
    val keep = "-_.!~*'()"
    val out = StringBuilder()
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (c < 0x80 && (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in keep)) out.append(ch)
        else out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
    }
    return out.toString()
}

// ---------- backend JSON → screen types ----------

/** The backend's train as the screens use it; planned times are on [now]'s day in [zone]. */
fun mapTrain(t: Train, now: Instant, zone: TimeZone = SWISS_ZONE): TrainView {
    val line = if (t.line.startsWith(t.product)) jsTrim(t.line.substring(t.product.length)) else t.line
    return TrainView(
        id = t.id,
        product = t.product,
        line = line,
        number = t.number,
        direction = t.direction,
        live = true,
        stops = t.stops.map { StopView(station = it.station.name, stationId = it.station.id, planned = todayAt(it.arr ?: it.dep ?: "00:00", now, zone)) },
    )
}

private fun fillText(t: Text?): Text {
    val first = t?.get(Lang.EN) ?: t?.get(Lang.DE) ?: t?.get(Lang.FR) ?: t?.get(Lang.IT) ?: ""
    return text(en = t?.get(Lang.EN) ?: first, de = t?.get(Lang.DE) ?: first, fr = t?.get(Lang.FR) ?: first, it = t?.get(Lang.IT) ?: first)
}

/** `new Date(iso)`; an unparsable time becomes the epoch (JavaScript's Invalid Date has no Kotlin equivalent). */
private fun instantOf(iso: String): Instant = Instant.fromEpochMilliseconds(jsDateParse(iso) ?: 0L)

/** The backend's published facts as the screens' incident; null until a public update exists. */
fun mapIncident(f: IncidentFacts?): Incident? {
    val ups = f?.updates?.filter { it.stage in STAGES } ?: emptyList()
    if (f == null || ups.isEmpty()) return null
    val updates = ups.mapIndexed { i, u ->
        PublishedUpdate(
            stage = u.stage,
            title = fillText(u.title),
            body = fillText(u.body),
            index = u.seq,
            at = instantOf(u.publishedAt),
            nextBy = u.nextUpdateBy?.takeIf { it.isNotEmpty() }?.let(::instantOf),
            // The backend keeps one delay for the incident; it belongs to the latest update.
            delay = if (i == ups.size - 1) f.delayMin else null,
        )
    }
    val latest = updates.last()
    // Where it happened exactly (the closed section, as SBB's text names it) before the whole line it is on.
    val section = (f.location ?: f.section)?.let { "${it.from.name} – ${it.to.name}" } ?: ""
    return Incident(id = f.id, trainId = f.trainIds.firstOrNull() ?: "", section = section, stage = latest.stage, updates = updates, latest = latest)
}
