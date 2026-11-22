package railstatus.server

import io.lettuce.core.Consumer
import io.lettuce.core.RedisBusyException
import io.lettuce.core.RedisClient
import io.lettuce.core.StreamMessage
import io.lettuce.core.XGroupCreateArgs
import io.lettuce.core.XReadArgs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory

/** Stream names. */
object Streams {
    /** Control-centre ticker → incident service. Stand-in for the upstream Kafka topic. */
    const val TICKER = "ticker.events"
    /** Incident service → notifier. One message per published update. */
    const val PUBLISHED = "incident.published"
}

/**
 * A minimal message log with consumer groups. Each message is one JSON string. Two implementations:
 * Redis Streams (the real thing) and in-memory (fallback when Redis isn't running).
 */
interface Bus {
    suspend fun publish(stream: String, json: String)

    /** Start consuming `stream` as `group`. The handler runs once per message; a message is acked after the handler returns. */
    fun subscribe(scope: CoroutineScope, stream: String, group: String, handler: suspend (String) -> Unit): Job

    /** Empty all streams (demo reset). */
    suspend fun reset()

    fun close() {}
}

class MemoryBus : Bus {
    private val flows = mutableMapOf<String, MutableSharedFlow<String>>()

    @Synchronized
    private fun flow(stream: String) = flows.getOrPut(stream) { MutableSharedFlow(extraBufferCapacity = 1024) }

    override suspend fun publish(stream: String, json: String) = flow(stream).emit(json)

    override fun subscribe(scope: CoroutineScope, stream: String, group: String, handler: suspend (String) -> Unit): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            flow(stream).collect { msg ->
                runCatching { handler(msg) }.onFailure { log.error("handler failed on $stream/$group", it) }
            }
        }

    override suspend fun reset() {}

    private companion object {
        val log = LoggerFactory.getLogger(MemoryBus::class.java)
    }
}

class RedisBus(url: String) : Bus {
    private val client = RedisClient.create(url)
    private val conn = client.connect()

    init {
        conn.sync().ping() // fail fast so Main can fall back to memory
    }

    override suspend fun publish(stream: String, json: String) {
        withContext(Dispatchers.IO) { conn.sync().xadd(stream, mapOf("json" to json)) }
    }

    override fun subscribe(scope: CoroutineScope, stream: String, group: String, handler: suspend (String) -> Unit): Job {
        // A blocking XREADGROUP needs its own connection.
        val reader = client.connect().sync()
        try {
            // "$" = only messages published from now on, like a fresh Kafka consumer group at the latest offset.
            reader.xgroupCreate(XReadArgs.StreamOffset.from(stream, "$"), group, XGroupCreateArgs().mkstream(true))
        } catch (_: RedisBusyException) {
            // the group exists already: carry on from its last acked position
        }
        val consumer = Consumer.from(group, "server-1")
        return scope.launch(Dispatchers.IO) {
            while (isActive) {
                val batch: List<StreamMessage<String, String>> = try {
                    reader.xreadgroup(consumer, XReadArgs.Builder.block(1000).count(50), XReadArgs.StreamOffset.lastConsumed(stream))
                } catch (e: Exception) {
                    if (!isActive) break
                    log.warn("xreadgroup $stream/$group failed: ${e.message}")
                    delay(1000)
                    continue
                }
                for (msg in batch) {
                    val json = msg.body["json"] ?: continue
                    runCatching { handler(json) }.onFailure { log.error("handler failed on $stream/$group", it) }
                    reader.xack(stream, group, msg.id)
                }
            }
        }
    }

    override suspend fun reset() {
        // Trim instead of DEL so the consumer groups survive.
        withContext(Dispatchers.IO) { listOf(Streams.TICKER, Streams.PUBLISHED).forEach { conn.sync().xtrim(it, 0) } }
    }

    override fun close() {
        conn.close()
        client.shutdown()
    }

    private companion object {
        val log = LoggerFactory.getLogger(RedisBus::class.java)
    }
}
