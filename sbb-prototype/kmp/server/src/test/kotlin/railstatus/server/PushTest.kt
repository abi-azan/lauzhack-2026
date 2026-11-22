package railstatus.server

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import nl.martijndwars.webpush.Utils
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import railstatus.model.*
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.*

class PushTest {
    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    @Test
    fun sendsEncryptedVapidSignedPush() = runBlocking {
        // A fake push service that records what it receives.
        var headers: Map<String, String> = emptyMap()
        var bodySize = 0
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/push/abc") { ex ->
            headers = ex.requestHeaders.mapValues { it.value.first() }.mapKeys { it.key.lowercase() }
            bodySize = ex.requestBody.readBytes().size
            ex.sendResponseHeaders(201, -1)
            ex.close()
        }
        http.start()
        try {
            val keyFile = File.createTempFile("vapid", ".json").apply { delete() }
            val keys = WebPushSender.loadOrCreateKeys(emptyMap(), keyFile)
            assertTrue(keyFile.exists())
            assertEquals(keys, WebPushSender.loadOrCreateKeys(emptyMap(), keyFile), "keys must be stable across restarts")

            // The browser's side of the subscription: a P-256 key pair and a 16-byte auth secret.
            val gen = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME)
            gen.initialize(ECNamedCurveTable.getParameterSpec("prime256v1"))
            val browserKey = b64(Utils.encode(gen.generateKeyPair().public as ECPublicKey))
            val auth = b64(ByteArray(16).also { SecureRandom().nextBytes(it) })

            val sender = WebPushSender(keys, "mailto:test@example.com")
            val ci = CheckIn("p-1", "tok", "IC8-1234", lang = Lang.DE, at = "2026-11-21T22:00:00+01:00", hasPush = true)
            sender.register("tok", PushSubscriptionJson("http://127.0.0.1:${http.address.port}/push/abc", PushKeys(browserKey, auth)))

            val update = IncidentUpdate(
                "upd-1", "inc-1", 1, Stage.INVESTIGATING,
                title = mapOf(Lang.DE to "Zug angehalten", Lang.EN to "Train stopped"),
                body = mapOf(Lang.DE to "Bitte im Zug bleiben.", Lang.EN to "Please stay on board."),
                publishedAt = "2026-11-21T22:00:00+01:00",
            )
            val facts = IncidentFacts("inc-1", 1, Stage.INVESTIGATING, listOf("IC8-1234"), updates = listOf(update))

            assertTrue(sender.send(ci, facts, update))
            assertEquals("aes128gcm", headers["content-encoding"])
            assertTrue(headers["authorization"]!!.startsWith("vapid t="), headers["authorization"])
            assertTrue(headers["authorization"]!!.contains("k=${keys.first}"))
            assertTrue(bodySize > 50, "encrypted body expected, got $bodySize bytes")
        } finally {
            http.stop(0)
        }
    }
}
