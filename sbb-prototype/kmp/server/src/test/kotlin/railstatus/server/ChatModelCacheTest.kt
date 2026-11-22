package railstatus.server

import io.ktor.client.plugins.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.*
import railstatus.model.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** The chat's model caches (review C1 and C7), against a fake OpenRouter. */
class ChatModelCacheTest {
    private fun content(s: String) = buildJsonObject {
        putJsonArray("choices") { addJsonObject { putJsonObject("message") { put("content", s) } } }
    }.toString()

    @Test
    fun aFailedClassifierCallIsNotCached() = testApplication {
        val calls = AtomicInteger()
        externalServices {
            hosts("https://openrouter.ai") {
                routing {
                    post("/api/v1/chat/completions") {
                        // The first call fails, like a timeout or a 429; the second answers.
                        if (calls.incrementAndGet() == 1) call.respond(HttpStatusCode.TooManyRequests, """{"error":"rate"}""")
                        else call.respondText(content("""{"topic":"delay","station":""}"""), ContentType.Application.Json)
                    }
                }
            }
        }
        val model = ChatModel("k", http = createClient { install(HttpTimeout) })
        val train = Fixtures.trains.first()
        assertNull(model.classify("blah blah", train, null))
        assertEquals(railstatus.chat.Topic.DELAY, model.classify("blah blah", train, null)?.topic)
        assertEquals(2, calls.get())
        // A successful answer is cached.
        model.classify("blah blah", train, null)
        assertEquals(2, calls.get())
    }

    @Test
    fun theModelAnswerIsCachedPerTrainAndDestination() = testApplication {
        val answers = AtomicInteger()
        externalServices {
            hosts("https://openrouter.ai") {
                routing {
                    post("/api/v1/chat/completions") {
                        val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                        val classify = body["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "json_schema"
                        val out = if (classify) """{"topic":"other","station":""}""" else {
                            answers.incrementAndGet()
                            """{"answer":"We only know what the updates say.","cited_update_ids":[],"known":true}"""
                        }
                        call.respondText(content(out), ContentType.Application.Json)
                    }
                }
            }
        }
        application { }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = IncidentService(MemoryBus(), Hub(), scope, autoAckSeconds = 0)
        val chat = ChatService(service, ChatConfig(), ChatModel("k", http = createClient { install(HttpTimeout) }))
        val inc = service.createManual(
            CreateIncidentRequest(line = Section(Fixtures.lausanne, Fixtures.geneva), location = Section(Fixtures.renens, Fixtures.allaman)),
        )
        val trains = inc.facts.trainIds
        assertTrue(trains.size >= 2, "the incident hits at least two trains: $trains")
        service.publish(inc.facts.id, service.draft(inc.facts.id, DraftRequest("investigating-unknown")))

        suspend fun ask(trainId: String, dest: String?) {
            val ci = service.checkIn(CheckInRequest(trainId, destinationId = dest)).checkIn
            val r = Json.parseToJsonElement(chat.ask(ci.token, """{"text":"Tell me a story about penguins"}""")).jsonObject
            assertEquals("llm", r["reply"]!!.jsonObject["source"]!!.jsonPrimitive.content)
        }
        val dest0 = service.train(trains[0]).stops.last().station.id
        ask(trains[0], dest0)
        ask(trains[0], dest0) // same train and destination: from the cache
        assertEquals(1, answers.get())
        ask(trains[1], dest0) // another train (C7)
        assertEquals(2, answers.get())
        ask(trains[0], service.train(trains[0]).stops[1].station.id) // another destination
        assertEquals(3, answers.get())
    }
}
