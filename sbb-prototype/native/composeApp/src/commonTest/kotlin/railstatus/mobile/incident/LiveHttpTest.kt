package railstatus.mobile.incident

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import railstatus.chat.Source
import railstatus.chat.Topic
import railstatus.model.CheckInMethod
import railstatus.model.CheckInRequest
import railstatus.model.Lang
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LiveHttpTest {
    private val requests = mutableListOf<Pair<HttpRequestData, String>>()

    private fun backend(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        KtorPassengerBackend(HttpClient(MockEngine { req ->
            requests.add(req to req.body.toByteArray().decodeToString())
            handler(req)
        }))

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val trainJson = """{"id":"IR90-1734","product":"IR","line":"IR 90","number":"1734","direction":"Brig","stops":[{"station":{"id":"8501120","name":"Lausanne"},"arr":"17:53"}],"extra":1}"""

    @Test
    fun getTrainAndProbe() = runTest {
        val b = backend { respond(trainJson, HttpStatusCode.OK, json) }
        val t = b.getTrain("https://rail.example", "IR90 1734/x")
        assertEquals("Lausanne", t.stops.single().station.name)
        assertEquals("https://rail.example/api/trains/IR90%201734%2Fx", requests.single().first.url.toString())
        assertTrue(b.probe("https://rail.example"))

        assertFalse(backend { respond("<html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }.probe("https://x"))
        assertFalse(backend { respond("", HttpStatusCode.NotFound, json) }.probe("https://x"))
        assertFalse(backend { throw IllegalStateException("offline") }.probe("https://x"))
        val e = assertFailsWith<BackendException> { backend { respond("", HttpStatusCode.NotFound) }.getTrain("https://x", "a") }
        assertEquals(404, e.status)
    }

    @Test
    fun checkInAndOut() = runTest {
        val b = backend { req ->
            if (req.method == HttpMethod.Delete) throw IllegalStateException("offline")
            respond("""{"checkIn":{"passengerId":"p1","token":"tok","trainId":"IR90-1734","at":"2026-09-17T15:00:00Z"},"train":$trainJson}""", HttpStatusCode.OK, json)
        }
        val res = b.postCheckIn("https://rail.example", CheckInRequest(trainId = "IR90-1734", lang = Lang.EN, method = CheckInMethod.QR))
        assertEquals("tok", res.checkIn.token)
        val (req, body) = requests.single()
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("https://rail.example/api/checkins", req.url.toString())
        assertEquals("application/json", req.body.contentType?.toString())
        // Nulls are left out, like the web's `undefined`.
        assertEquals(Json.parseToJsonElement("""{"trainId":"IR90-1734","lang":"en","method":"qr"}"""), Json.parseToJsonElement(body))
        b.deleteCheckIn("https://rail.example", "tok") // best effort: doesn't throw
        assertEquals(HttpMethod.Delete, requests.last().first.method)
    }

    // Off the test dispatcher: postChat's 10 s timeout would otherwise run on virtual time and fire at once.
    @Test
    fun chat() = runTest { withContext(Dispatchers.Default) { chatOnRealTime() } }

    private suspend fun chatOnRealTime() {
        val reply = """{"questionId":"q-1","reply":{"text":"Cause: x.","lang":"en","source":"template","topic":"cause","citedUpdateIds":["u2"],"urgent":false,"classifiedBy":"rules","novel":1}}"""
        val ok = backend { respond(reply, HttpStatusCode.OK, json) }.postChat("https://rail.example", "a b", "Why?", Lang.DE)
        val answered = assertIs<ChatPost.Answered>(ok)
        assertEquals("Cause: x.", answered.reply.text)
        assertEquals(Source.TEMPLATE, answered.reply.source)
        assertEquals(Topic.CAUSE, answered.reply.topic)
        assertEquals("rules", answered.reply.classifiedBy)
        val (req, body) = requests.single()
        assertEquals("https://rail.example/api/chat/a%20b", req.url.toString())
        assertEquals("text/plain; charset=UTF-8", req.body.contentType?.toString())
        assertEquals(Json.parseToJsonElement("""{"text":"Why?","lang":"de"}"""), Json.parseToJsonElement(body))

        // Unknown topics don't break the answer.
        val odd = backend { respond("""{"reply":{"text":"x","topic":"weather","source":"oracle"}}""", HttpStatusCode.OK, json) }.postChat("h", "t", "q", Lang.EN)
        with(assertIs<ChatPost.Answered>(odd).reply) { assertEquals(null, topic); assertEquals(null, source); assertEquals("x", json.jsonObject["text"].toString().trim('"')) }

        assertEquals(ChatPost.Failed(429), backend { respond("", HttpStatusCode.TooManyRequests) }.postChat("h", "t", "q", Lang.EN))
        assertEquals(ChatPost.Failed(403), backend { respond("", HttpStatusCode.Forbidden) }.postChat("h", "t", "q", Lang.EN))
        assertEquals(ChatPost.Failed(502), backend { respond("""{"reply":{"text":""}}""", HttpStatusCode.OK, json) }.postChat("h", "t", "q", Lang.EN))
        assertEquals(ChatPost.Failed(502), backend { respond("[]", HttpStatusCode.OK, json) }.postChat("h", "t", "q", Lang.EN))
        assertEquals(ChatPost.Failed(0), backend { respond("{nope", HttpStatusCode.OK, json) }.postChat("h", "t", "q", Lang.EN))
        assertEquals(ChatPost.Failed(0), backend { throw IllegalStateException("offline") }.postChat("h", "t", "q", Lang.EN))
    }

    @Test
    fun uriComponents() {
        assertEquals("IR90-1734", encodeURIComponent("IR90-1734"))
        assertEquals("a%20b%2Fc%3Fd%26e%C3%A9!~*'()_.", encodeURIComponent("a b/c?d&eé!~*'()_."))
    }
}
