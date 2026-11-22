package railstatus.server

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.application.*
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("railstatus.server.ChatRoutes")

private val JSON_UTF8 = ContentType.Application.Json.withParameter("charset", "utf-8")

/** The passenger chat's HTTP API, under `/api/chat`. The JSON is what the old Node service sent. */
fun Route.chatRoutes(chat: ChatService) {
    route("/chat") {
        get("/health") { call.chatJson(HttpStatusCode.OK) { chat.healthJson() } }
        get("/incidents/{id}/questions") { call.chatJson(HttpStatusCode.OK) { chat.questionsJson(call.parameters["id"]!!) } }
        post("/{token}") {
            call.chatJson(HttpStatusCode.OK) {
                // Any content type: read the bytes as UTF-8, like the Node service did.
                chat.ask(call.parameters["token"]!!, call.receive<ByteArray>().decodeToString())
            }
        }
        options("{...}") { call.respond(HttpStatusCode.NoContent) }
        route("{...}") { handle { call.chatJson(HttpStatusCode.NotFound) { """{"error":"not-found"}""" } } }
    }
}

private suspend fun ApplicationCall.chatJson(status: HttpStatusCode, body: suspend () -> String) {
    val (code, text) = try {
        status to body()
    } catch (e: ChatError) {
        HttpStatusCode.fromValue(e.status) to """{"error":"${e.message}"}"""
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("chat failed", e)
        HttpStatusCode.InternalServerError to """{"error":"internal"}"""
    }
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(text, JSON_UTF8, code)
}
