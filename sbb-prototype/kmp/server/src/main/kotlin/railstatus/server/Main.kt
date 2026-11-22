package railstatus.server

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import railstatus.model.RailJson
import railstatus.model.Train
import java.io.File

private val log = LoggerFactory.getLogger("railstatus.server.Main")

/**
 * Environment:
 *   PORT              default 8080
 *   BUS               redis | memory | auto (default: Redis if reachable, else memory)
 *   REDIS_URL         default redis://localhost:6379
 *   AUTO_ACK_SECONDS  default 60; 0 turns the safety net off
 *   OJP_KEY           opentransportdata.swiss OJP 2.0 key: real next-move routes (needs TRAINS_ANCHOR); unset = the demo corridor
 *   OJP_URL           OJP 2.0 endpoint; default opentransportdata.swiss (transport.opendata.ch/ojp20 is keyless but ignores NotVia)
 *   TRAINS_FILE       JSON list of trains (or {"trains": [...]}); default: the demo trains in Fixtures.kt
 *   TRAINS_ANCHOR     "HH:MM" of the trains' day to put on the current minute (at start and on reset),
 *                     e.g. 06:52 with data/real-trains-2026-09-01.json; unset keeps the trains' own times
 *   PUSH              on | off (default on)
 *   VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY   base64url; default: .vapid.json, generated on first run
 *   VAPID_SUBJECT     default mailto:railstatus@example.com
 *   PUSH_URL          path the push notification opens, {id} = incident id; default /#/incident/{id}
 *   CHAT_KEY          shared secret for POST /api/internal/questions (X-Chat-Key), for an external chat service;
 *                     unset = endpoint refuses everything. The chat at /api/chat/... runs in this server and doesn't need it.
 *   CHAT_TZ           IANA zone for clock times in chat answers; default Europe/Zurich
 *   DRAFT_ENGINE      "koog": the AI draft runs as the Koog agent (kmp/agent, T17), falling back to the single-call drafter; unset = single call
 *   OPENROUTER_API_KEY  enables POST /api/admin/incidents/{id}/draft/ai and the chat's two model steps
 *   CHAT_CLASSIFY_MODEL  picks topic + station when the chat's rules say "other"; default z-ai/glm-5.3-flash
 *   CHAT_MODEL        free-text chat answer, only if the classifier also says "other"; default openai/gpt-6-luna
 *   DRAFT_MODEL       OpenRouter model for AI drafts; default openai/gpt-6-luna (the chat's model)
 *   ADMIN_KEY         when set, the /api/admin, /api/control and /api/ingest routes and /ws/admin need it
 *                     (header X-Admin-Key, or ?key=); the dashboard takes it from /admin/?key=...
 *   STATIC            mounts for built frontends, e.g. "/=../mobile/dist,/admin=../admin/dist"
 */
fun main() {
    val env = System.getenv()
    val port = env["PORT"]?.toInt() ?: 8080
    val bus = makeBus(env["BUS"] ?: "auto", env["REDIS_URL"] ?: "redis://localhost:6379")
    val autoAck = env["AUTO_ACK_SECONDS"]?.toLong() ?: 60
    val trains = env["TRAINS_FILE"]?.let { loadTrains(File(it)) } ?: Fixtures.trains
    val push: PushSender? = if (env["PUSH"] == "off") null else WebPushSender(
        WebPushSender.loadOrCreateKeys(env, File(".vapid.json")),
        subject = env["VAPID_SUBJECT"] ?: "mailto:railstatus@example.com",
        urlPattern = env["PUSH_URL"] ?: "/#/incident/{id}",
    )
    val mounts = parseMounts(env["STATIC"])

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hub = Hub()
    val ojp = env["OJP_KEY"]?.takeIf(String::isNotBlank)?.let { OjpClient(it, env["OJP_URL"] ?: "https://api.opentransportdata.swiss/ojp20") }
    val service = IncidentService(bus, hub, scope, autoAck, trains = trains, push = push, trainsAnchor = env["TRAINS_ANCHOR"], ojp = ojp)
    service.start()
    // Both drafters when there's a key; the desk switches between them at runtime and drafts ahead.
    val drafts: DraftDesk? = env["OPENROUTER_API_KEY"]?.takeIf(String::isNotBlank)?.let { key ->
        val model = env["DRAFT_MODEL"] ?: "openai/gpt-6-luna"
        val single = AiDrafter(key, model) { id ->
            questionLines(service.questions().firstOrNull { it.incidentId == id }?.clusters.orEmpty())
        }
        val koog = runCatching {
            KoogDrafter(
                railstatus.agent.DraftAgent(railstatus.agent.DraftAgent.openRouterExecutor(key), railstatus.agent.DraftAgent.openRouterModel(model)),
                fallback = single, service = service, guard = single::guard,
            )
        }.getOrNull()
        DraftDesk(single, koog, service, scope, startWithKoog = env["DRAFT_ENGINE"] == "koog").also { desk ->
            service.onChange = desk::prefetch
        }
    }
    val chat = ChatConfig(
        key = env["CHAT_KEY"],
        llmKey = env["OPENROUTER_API_KEY"]?.takeIf(String::isNotBlank),
        classifyModel = env["CHAT_CLASSIFY_MODEL"] ?: "z-ai/glm-5.3-flash",
        model = env["CHAT_MODEL"] ?: "openai/gpt-6-luna",
        timeZone = env["CHAT_TZ"] ?: "Europe/Zurich",
        smart = env["OPENROUTER_API_KEY"]?.takeIf(String::isNotBlank)?.takeIf { env["SMART_CHAT"] != "off" }?.let { key ->
            SmartChat(key, env["OJP_KEY"]?.takeIf(String::isNotBlank), model = env["SMART_CHAT_MODEL"] ?: "anthropic/claude-sonnet-5.5")
        },
    )

    log.info(
        "railstatus on :$port, bus=${bus::class.simpleName}, auto-ack=${autoAck}s, trains=${trains.size}, " +
            "push=${push != null}, ai-draft=${drafts?.engine ?: "off"}, chat-models=${chat.llmKey != null}, static=${mounts.keys}",
    )
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        val translator = env["OPENROUTER_API_KEY"]?.takeIf(String::isNotBlank)?.let { Translator(it, env["DRAFT_MODEL"] ?: "openai/gpt-6-luna") }
        railModule(service, hub, bus, push, mounts, chat, drafts, translator, adminKey = env["ADMIN_KEY"]?.takeIf(String::isNotBlank))
    }.start(wait = true)
}

fun makeBus(kind: String, redisUrl: String): Bus = when (kind) {
    "memory" -> MemoryBus()
    "redis" -> RedisBus(redisUrl)
    else -> try {
        RedisBus(redisUrl)
    } catch (e: Exception) {
        log.warn("Redis not reachable at $redisUrl (${e.message}); using the in-memory bus")
        MemoryBus()
    }
}

/** Accepts a bare list of trains or an object with a `trains` list. Extra fields are ignored. */
fun loadTrains(file: File): List<Train> {
    val root = RailJson.parseToJsonElement(file.readText())
    val list = when (root) {
        is JsonArray -> root
        is JsonObject -> root["trains"]?.jsonArray ?: error("${file.path}: no \"trains\" list")
        else -> error("${file.path}: expected a list of trains")
    }
    return RailJson.decodeFromJsonElement(ListSerializer(Train.serializer()), list).also {
        log.info("loaded ${it.size} trains from ${file.path}")
    }
}

/** "/=../mobile/dist,/admin=../admin/dist" → {"/": dir, "/admin": dir}; directories that don't exist are skipped. */
fun parseMounts(spec: String?): Map<String, File> =
    spec.orEmpty().split(',').filter { '=' in it }.associate { part ->
        val (path, dir) = part.split('=', limit = 2)
        path.trim() to File(dir.trim())
    }.filterValues { dir ->
        dir.isDirectory.also { if (!it) log.warn("static dir ${dir.path} doesn't exist, skipping") }
    }
