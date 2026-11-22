package railstatus.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import nl.martijndwars.webpush.Encoding
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.PushService
import nl.martijndwars.webpush.Utils
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.interfaces.ECPrivateKey
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.slf4j.LoggerFactory
import railstatus.model.*
import java.io.File
import java.security.KeyPairGenerator
import java.security.Security
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Web Push to locked phones. */
interface PushSender {
    val publicKey: String

    fun register(token: String, sub: PushSubscriptionJson)

    /** Returns true if the push service accepted the message. */
    suspend fun send(ci: CheckIn, facts: IncidentFacts, update: IncidentUpdate): Boolean

    fun clear()
}

/**
 * The JSON payload of every push message. The passenger app's service worker shows it with
 * `registration.showNotification(title, {body, tag, data: {url}})`. All texts are already in the
 * passenger's language. One `tag` per incident, so each new update replaces the previous notification.
 */
@Serializable
data class PushPayload(
    val title: String,
    val body: String,
    val tag: String,
    val incidentId: String,
    val stage: Stage,
    val seq: Int,
    val nextUpdateBy: String? = null,
    /** Where to open the app when the notification is tapped. */
    val url: String,
)

@Serializable
private data class VapidKeys(val publicKey: String, val privateKey: String)

class WebPushSender(
    keys: Pair<String, String>,
    subject: String,
    /** Path in the passenger app for an incident; `{id}` is replaced. */
    private val urlPattern: String = "/#/incident/{id}",
) : PushSender {
    override val publicKey: String = keys.first
    private val service = PushService(keys.first, keys.second, subject)
    private val subs = ConcurrentHashMap<String, PushSubscriptionJson>()

    override fun register(token: String, sub: PushSubscriptionJson) {
        subs[token] = sub
    }

    override fun clear() = subs.clear()

    override suspend fun send(ci: CheckIn, facts: IncidentFacts, update: IncidentUpdate): Boolean {
        val sub = subs[ci.token] ?: return false
        val payload = PushPayload(
            title = update.title[ci.lang] ?: update.title.values.first(),
            body = update.body[ci.lang] ?: update.body.values.first(),
            tag = "incident-${facts.id}",
            incidentId = facts.id,
            stage = update.stage,
            seq = update.seq,
            nextUpdateBy = update.nextUpdateBy,
            url = urlPattern.replace("{id}", facts.id),
        )
        val json = RailJson.encodeToString(PushPayload.serializer(), payload)
        return withContext(Dispatchers.IO) {
            try {
                val res = service.send(Notification(sub.endpoint, sub.keys.p256dh, sub.keys.auth, json), Encoding.AES128GCM)
                val code = res.statusLine.statusCode
                if (code == 404 || code == 410) subs.remove(ci.token) // subscription expired
                if (code !in 200..299) log.warn("push to ${ci.passengerId} failed: HTTP $code")
                code in 200..299
            } catch (e: Exception) {
                log.warn("push to ${ci.passengerId} failed: ${e.message}")
                false
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(WebPushSender::class.java)

        init {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) Security.addProvider(BouncyCastleProvider())
        }

        private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /**
         * VAPID keys, as base64url strings: from VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY if set, else from `file`.
         * If neither exists, a new pair is generated and saved to `file`. Keep the file: phones that
         * subscribed with the old public key stop receiving pushes when it changes.
         */
        fun loadOrCreateKeys(env: Map<String, String>, file: File): Pair<String, String> {
            val pub = env["VAPID_PUBLIC_KEY"]
            val priv = env["VAPID_PRIVATE_KEY"]
            if (pub != null && priv != null) return pub to priv
            if (file.exists()) {
                val k = RailJson.decodeFromString(VapidKeys.serializer(), file.readText())
                return k.publicKey to k.privateKey
            }
            val gen = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME)
            gen.initialize(ECNamedCurveTable.getParameterSpec("prime256v1"))
            val pair = gen.generateKeyPair()
            val k = VapidKeys(
                publicKey = b64(Utils.encode(pair.public as ECPublicKey)),
                privateKey = b64(Utils.encode(pair.private as ECPrivateKey)),
            )
            file.writeText(RailJson.encodeToString(VapidKeys.serializer(), k))
            log.info("generated new VAPID keys in ${file.absolutePath}")
            return k.publicKey to k.privateKey
        }
    }
}
