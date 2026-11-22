package railstatus.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import org.slf4j.event.Level
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import railstatus.model.*
import java.io.File
import kotlin.time.Duration.Companion.seconds

fun Application.railModule(
    service: IncidentService,
    hub: Hub,
    bus: Bus,
    push: PushSender?,
    staticMounts: Map<String, File> = emptyMap(),
    chat: ChatConfig = ChatConfig(),
    drafts: DraftDesk? = null,
    /** Translates an operator-edited English update into the other languages at publish time. */
    translator: Translator? = null,
    /** When set, admin, control and ingest calls need it (R3); passenger routes stay open. */
    adminKey: String? = null,
) {
    val chatService = ChatService(service, chat)
    val demo = DemoControl(bus, this)
    install(ContentNegotiation) { json(RailJson) }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
    }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Delete)
    }
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path().startsWith("/api") }
    }
    install(StatusPages) {
        exception<NotFound> { call, e -> call.respond(HttpStatusCode.NotFound, ApiError(e.message ?: "not found")) }
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "bad request")) }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError(e.cause?.message ?: e.message ?: "bad request"))
        }
    }

    routing {
        get("/health") { call.respondText("ok") }

        // Built frontends, so one HTTPS address (the tunnel) serves the apps and the API.
        // Longest path first, so "/admin" wins over "/".
        staticMounts.entries.sortedByDescending { it.key.length }.forEach { (path, dir) ->
            staticFiles(path, dir) { default("index.html") }
        }

        route("/api") {
            // ---- passengers -------------------------------------------------------------------------
            get("/trains") { call.respond(service.trains()) }
            get("/trains/{id}") { call.respond(service.train(call.parameters["id"]!!)) }

            post("/checkins") {
                call.respond(HttpStatusCode.Created, service.checkIn(call.receive<CheckInRequest>()))
            }
            get("/checkins/{token}") { call.respond(service.checkInView(call.parameters["token"]!!)) }
            delete("/checkins/{token}") {
                service.checkOut(call.parameters["token"]!!)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/checkins/{token}/push") {
                if (push == null) return@post call.respond(HttpStatusCode.NotImplemented, ApiError("push not configured"))
                service.setPush(call.parameters["token"]!!, call.receive<PushSubscriptionJson>())
                call.respond(HttpStatusCode.NoContent)
            }
            get("/push/key") {
                if (push == null) return@get call.respond(HttpStatusCode.NotImplemented, ApiError("push not configured"))
                call.respond(mapOf("publicKey" to push.publicKey))
            }

            // ---- public status page -----------------------------------------------------------------
            get("/status") { call.respond(service.publicIncidents()) }
            get("/incidents/{id}") { call.respond(service.publicIncident(call.parameters["id"]!!)) }

            // ---- admin ------------------------------------------------------------------------------
            get("/templates") { call.respond(service.templates()) }
            get("/stations") { call.respond(service.stations()) }
            get("/admin/questions") { if (call.adminDenied(adminKey)) return@get; call.respond(service.questions()) }
            route("/admin/incidents") {
                get { if (call.adminDenied(adminKey)) return@get; call.respond(service.adminIncidents()) }
                post {
                    if (call.adminDenied(adminKey)) return@post
                    call.respond(HttpStatusCode.Created, service.createManual(call.receive<CreateIncidentRequest>()))
                }
                get("/{id}") { if (call.adminDenied(adminKey)) return@get; call.respond(service.adminIncident(call.parameters["id"]!!)) }
                post("/{id}/draft") {
                    if (call.adminDenied(adminKey)) return@post
                    call.respond(service.draft(call.parameters["id"]!!, call.receive<DraftRequest>()))
                }
                post("/{id}/draft/ai") {
                    if (call.adminDenied(adminKey)) return@post
                    if (drafts == null) return@post call.respond(HttpStatusCode.NotImplemented, ApiError("AI drafting needs OPENROUTER_API_KEY"))
                    call.respond(drafts.draft(call.parameters["id"]!!))
                }
                post("/{id}/updates") {
                    if (call.adminDenied(adminKey)) return@post
                    val req = call.receive<PublishUpdateRequest>().let { translator?.complete(it) ?: it }
                    val update = service.publish(call.parameters["id"]!!, req)
                    call.respond(HttpStatusCode.Created, update)
                }
            }

            // ---- ingest: the HTTP way into the ticker queue, for producers without Redis access ----------
            post("/ingest/ticker") {
                if (call.adminDenied(adminKey)) return@post
                val ev = call.receive<TickerInput>().toEvent()
                bus.publish(Streams.TICKER, RailJson.encodeToString(TickerEvent.serializer(), ev))
                call.respond(HttpStatusCode.Accepted, ev)
            }

            // ---- chat ---------------------------------------------------------------------------------------
            // Question groups from an external chat service (the old Node one). The chat below pushes its own in process.
            post("/internal/questions") {
                if (chat.key == null || call.request.headers["X-Chat-Key"] != chat.key) {
                    return@post call.respond(HttpStatusCode.Forbidden, ApiError("bad or missing X-Chat-Key"))
                }
                // `ts` is optional for the sender.
                val body = RailJson.parseToJsonElement(call.receiveText()).jsonObject
                val withTs = if ("ts" in body) body else JsonObject(body + ("ts" to JsonPrimitive(now().iso())))
                service.onQuestions(RailJson.decodeFromJsonElement(WsEvent.serializer(), withTs))
                call.respond(HttpStatusCode.NoContent)
            }
            chatRoutes(chatService)

            // ---- demo -------------------------------------------------------------------------------------
            // The AI draft engine, switched without a restart: {"engine": "koog" | "single-call"}.
            get("/control/draft-engine") {
                if (call.adminDenied(adminKey)) return@get
                call.respond(mapOf("engine" to (drafts?.engine ?: "none")))
            }
            post("/control/draft-engine") {
                if (call.adminDenied(adminKey)) return@post
                if (drafts == null) return@post call.respond(HttpStatusCode.NotImplemented, ApiError("AI drafting needs OPENROUTER_API_KEY"))
                val want = RailJson.parseToJsonElement(call.receiveText()).jsonObject["engine"]?.toString()?.trim('"')
                call.respond(mapOf("engine" to drafts.setEngine(want == "koog")))
            }
            // The dashboard's demo strip: the 4 beats of 17 Sep, one click each, strictly in order.
            get("/control/demo") {
                if (call.adminDenied(adminKey)) return@get
                call.respond(demo.status())
            }
            post("/control/demo/beat/{n}") {
                if (call.adminDenied(adminKey)) return@post
                call.respond(demo.send(call.parameters["n"]?.toIntOrNull() ?: 0))
            }
            post("/control/reset") {
                if (call.adminDenied(adminKey)) return@post
                demo.reset()
                service.reset()
                call.respond(HttpStatusCode.NoContent)
            }
        }

        // ---- WebSockets -----------------------------------------------------------------------------
        webSocket("/ws/passenger") {
            val token = call.request.queryParameters["token"]
            val ci = token?.let { service.checkInByToken(it) }
            if (ci == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unknown token"))
                return@webSocket
            }
            hub.addPassenger(ci.token, this)
            try {
                val open = service.openIncidentFor(ci.trainId)
                val hello = Hello(now().iso(), ci, service.train(ci.trainId), open, open?.let { service.nextMoveFor(ci, it) })
                send(Frame.Text(RailJson.encodeToString(WsEvent.serializer(), hello)))
                for (frame in incoming) { /* passengers only listen */ }
            } finally {
                hub.removePassenger(ci.token, this)
            }
        }
        webSocket("/ws/admin") {
            if (adminKey != null && call.request.queryParameters["key"] != adminKey) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "admin key required"))
                return@webSocket
            }
            hub.addAdmin(this)
            try {
                for (frame in incoming) { /* listen only; actions go through REST */ }
            } finally {
                hub.removeAdmin(this)
            }
        }
        webSocket("/ws/status") {
            hub.addStatus(this)
            try {
                for (frame in incoming) { }
            } finally {
                hub.removeStatus(this)
            }
        }
    }
}

fun TickerInput.toEvent() = TickerEvent(
    id = newId("tk"), sourceEventId = sourceEventId, kind = kind, source = source, ts = ts ?: now().iso(),
    reasonCode = reasonCode, reasonText = reasonText, line = line, location = location, lines = lines, text = text,
)

/** R3: true (and a 403 sent) when an admin key is configured and the call doesn't carry it. */
private suspend fun ApplicationCall.adminDenied(key: String?): Boolean {
    if (key == null) return false
    val given = request.headers["X-Admin-Key"] ?: request.queryParameters["key"]
    if (given == key) return false
    respond(HttpStatusCode.Forbidden, ApiError("admin key required: open the dashboard with ?key=..."))
    return true
}
