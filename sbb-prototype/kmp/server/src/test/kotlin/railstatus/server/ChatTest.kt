package railstatus.server

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import railstatus.model.*
import kotlin.test.*

/** The passenger chat at `/api/chat`, in process (it was the Node service in sbb-prototype/chat). */
class ChatTest {
    private fun ApplicationTestBuilder.setup(): HttpClient {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = MemoryBus()
        val hub = Hub()
        val service = IncidentService(bus, hub, scope, autoAckSeconds = 0)
        service.start()
        application { railModule(service, hub, bus, null) }
        return createClient {
            install(ContentNegotiation) { json(RailJson) }
            install(WebSockets)
        }
    }

    private suspend fun HttpClient.checkIn(lang: Lang = Lang.EN): CheckInResponse =
        post("/api/checkins") {
            contentType(ContentType.Application.Json)
            setBody(CheckInRequest("IR90-1734", lang = lang, method = CheckInMethod.LINK))
        }.body()

    /** Opens an incident on the IR 90 and publishes "investigating". */
    private suspend fun HttpClient.publishIncident(): String {
        val inc: AdminIncident = post("/api/admin/incidents") {
            contentType(ContentType.Application.Json)
            setBody(CreateIncidentRequest(line = Section(Fixtures.lausanne, Fixtures.geneva), location = Section(Fixtures.renens, Fixtures.allaman), lines = listOf("IR90")))
        }.body()
        val draft: PublishUpdateRequest = post("/api/admin/incidents/${inc.facts.id}/draft") {
            contentType(ContentType.Application.Json)
            setBody(DraftRequest("investigating-unknown"))
        }.body()
        val r = post("/api/admin/incidents/${inc.facts.id}/updates") { contentType(ContentType.Application.Json); setBody(draft) }
        assertEquals(HttpStatusCode.Created, r.status)
        return inc.facts.id
    }

    private suspend fun HttpClient.ask(token: String, body: String): HttpResponse =
        post("/api/chat/$token") { contentType(ContentType.Text.Plain); setBody(body) }

    private fun error(r: String) = Json.parseToJsonElement(r).jsonObject["error"]?.jsonPrimitive?.content

    @Test
    fun answersAndGroupsTheQuestions() = testApplication {
        val client = setup()
        val incidentId = client.publishIncident()
        val en = client.checkIn(Lang.EN)
        val de = client.checkIn(Lang.DE)

        client.webSocket("/ws/admin") {
            val r = client.ask(en.checkIn.token, """{"text":"  Why did we stop?  "}""")
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals("application/json; charset=utf-8", r.headers[HttpHeaders.ContentType])
            val raw = r.bodyAsText()
            // engine.ts's key order, as the Node service sent it.
            assertTrue(raw.startsWith("""{"questionId":"q-1","reply":{"text":"""), raw)
            val reply = Json.parseToJsonElement(raw).jsonObject.getValue("reply").jsonObject
            assertEquals(
                listOf("text", "source", "citedUpdateIds", "lang", "topic", "urgent", "cluster", "classifiedBy"),
                reply.keys.toList(),
            )
            assertEquals("cause", reply["topic"]?.jsonPrimitive?.content)
            assertEquals("not_known", reply["source"]?.jsonPrimitive?.content)
            assertEquals("rules", reply["classifiedBy"]?.jsonPrimitive?.content)
            assertEquals("cause", Json.parseToJsonElement(raw).jsonObject["clusterId"]?.jsonPrimitive?.content)

            // The same question in German from a second passenger lands in the same group.
            val r2 = client.ask(de.checkIn.token, """{"text":"Warum stehen wir?"}""")
            assertEquals("de", Json.parseToJsonElement(r2.bodyAsText()).jsonObject["reply"]!!.jsonObject["lang"]?.jsonPrimitive?.content)

            // The dashboards get question.new and questions.changed over /ws/admin.
            val seen = mutableListOf<WsEvent>()
            withTimeout(5000) {
                while (seen.filterIsInstance<QuestionsChanged>().none { c -> c.clusters.any { it.count == 2 } }) {
                    val frame = incoming.receive() as Frame.Text
                    seen += RailJson.decodeFromString(WsEvent.serializer(), frame.readText())
                }
            }
            assertEquals(2, seen.filterIsInstance<QuestionNew>().size)
            val last = seen.filterIsInstance<QuestionsChanged>().last()
            assertEquals(incidentId, last.incidentId)
            assertEquals("cause", last.clusters.single().id)
            assertEquals("Why did we stop?", last.clusters.single().sample)
        }

        // The admin's REST view and the chat's own view agree.
        val stored: List<QuestionsChanged> = client.get("/api/admin/questions").body()
        assertEquals(2, stored.single().clusters.single().count)
        val groups = Json.parseToJsonElement(client.get("/api/chat/incidents/$incidentId/questions").bodyAsText()).jsonObject
        assertEquals(listOf("ts", "type", "incidentId", "clusters"), groups.keys.toList())
        val g = groups.getValue("clusters").jsonArray.single().jsonObject
        assertEquals("""{"en":1,"de":1}""", g.getValue("langs").toString())
        assertEquals(2, g.getValue("questions").jsonPrimitive.int)

        val health = Json.parseToJsonElement(client.get("/api/chat/health").bodyAsText()).jsonObject
        assertEquals(JsonNull, health["model"])
        assertEquals(2, health.getValue("usage").jsonObject.getValue("not_known").jsonPrimitive.int)
    }

    @Test
    fun refusesLikeTheNodeService() = testApplication {
        val client = setup()
        val p = client.checkIn()

        assertEquals("no-open-incident", error(client.ask(p.checkIn.token, """{"text":"Hello"}""").also { assertEquals(HttpStatusCode.Forbidden, it.status) }.bodyAsText()))
        assertEquals("unknown-token", error(client.ask("nope", """{"text":"Hello"}""").also { assertEquals(HttpStatusCode.NotFound, it.status) }.bodyAsText()))
        assertEquals("empty", error(client.ask("nope", """{"text":"   "}""").also { assertEquals(HttpStatusCode.BadRequest, it.status) }.bodyAsText()))
        assertEquals("bad-json", error(client.ask("nope", "{text").also { assertEquals(HttpStatusCode.BadRequest, it.status) }.bodyAsText()))
        assertEquals("too-large", error(client.ask("nope", "x".repeat(9000)).also { assertEquals(HttpStatusCode.PayloadTooLarge, it.status) }.bodyAsText()))
        assertEquals("not-found", error(client.get("/api/chat/${p.checkIn.token}").also { assertEquals(HttpStatusCode.NotFound, it.status) }.bodyAsText()))

        client.publishIncident()
        assertEquals(HttpStatusCode.OK, client.ask(p.checkIn.token, """{"text":"Hello"}""").status)
        val again = client.ask(p.checkIn.token, """{"text":"Hello again"}""")
        assertEquals(HttpStatusCode.TooManyRequests, again.status)
        assertEquals("slow-down", error(again.bodyAsText()))
    }
}
