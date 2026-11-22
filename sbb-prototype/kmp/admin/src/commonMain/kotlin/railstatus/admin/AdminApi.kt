package railstatus.admin

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import railstatus.model.*

/** REST + WebSocket client for the incident service. Same code on web and desktop. */
/** [adminKey]: sent as `X-Admin-Key` on every call and as `?key=` on the admin WebSocket (R3). */
class AdminApi(baseUrl: String, private val adminKey: String? = null) {
    val base = baseUrl.trimEnd('/')
    private val wsBase = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")

    private val client = HttpClient {
        install(ContentNegotiation) { json(RailJson) }
        install(WebSockets)
        expectSuccess = true
        defaultRequest { adminKey?.let { header("X-Admin-Key", it) } }
    }

    suspend fun incidents(): List<AdminIncident> = client.get("$base/api/admin/incidents").body()
    suspend fun templates(): List<Template> = client.get("$base/api/templates").body()
    suspend fun trains(): List<Train> = client.get("$base/api/trains").body()

    suspend fun draft(incidentId: String, req: DraftRequest): PublishUpdateRequest =
        client.post("$base/api/admin/incidents/$incidentId/draft") { json(req) }.body()

    suspend fun aiDraft(incidentId: String): PublishUpdateRequest =
        client.post("$base/api/admin/incidents/$incidentId/draft/ai").body()

    suspend fun publish(incidentId: String, req: PublishUpdateRequest): IncidentUpdate =
        client.post("$base/api/admin/incidents/$incidentId/updates") { json(req) }.body()

    suspend fun stations(): List<Station> = client.get("$base/api/stations").body()
    suspend fun questions(): List<QuestionsChanged> = client.get("$base/api/admin/questions").body()

    suspend fun createIncident(req: CreateIncidentRequest): AdminIncident =
        client.post("$base/api/admin/incidents") { json(req) }.body()

    suspend fun demo(): DemoStatus = client.get("$base/api/control/demo").body()
    suspend fun demoBeat(n: Int): DemoStatus = client.post("$base/api/control/demo/beat/$n").body()
    suspend fun reset() { client.post("$base/api/control/reset") }

    /**
     * A plain client for other origins (SBB's font CDN): no admin key, so the browser sends a simple GET. The key header
     * would force a CORS preflight that the CDN rejects, and the font would never load.
     */
    private val plain = HttpClient { expectSuccess = true }

    /** Raw bytes from any URL (used for the web font). */
    suspend fun bytes(url: String): ByteArray = plain.get(url).body()

    /**
     * Admin events, forever: reconnects after a drop. `onConnected(true/false)` reports the socket state,
     * so the UI can show "Live" and refetch after a reconnect.
     */
    fun events(onConnected: suspend (Boolean) -> Unit): Flow<WsEvent> = flow {
        while (true) {
            try {
                client.webSocket("$wsBase/ws/admin" + (adminKey?.let { "?key=" + it.encodeURLParameter() } ?: "")) {
                    onConnected(true)
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            runCatching { RailJson.decodeFromString(WsEvent.serializer(), frame.readText()) }
                                .onSuccess { emit(it) }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
            onConnected(false)
            delay(2000)
        }
    }

    private inline fun <reified T> HttpRequestBuilder.json(body: T) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}
