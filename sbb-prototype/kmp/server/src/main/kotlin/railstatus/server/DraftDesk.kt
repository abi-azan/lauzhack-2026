package railstatus.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import railstatus.model.PublishUpdateRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * The AI draft desk:
 * - **Switches the engine at runtime** (`koog` or `single-call`) without a restart, so incidents and check-ins survive.
 *   `POST /api/control/draft-engine`, or `./demo koog on|off`.
 * - **Drafts ahead.** When an incident's ticker moves or an update is published, a draft starts in the background
 *   ([debounceMs] after the last change, so one demo beat gives one draft). The operator's click then usually
 *   returns at once instead of waiting 15–20 s for the Koog agent. Cached per incident, engine, version, ticker size and question count.
 */
class DraftDesk(
    private val single: Drafter,
    private val koog: Drafter?,
    private val service: IncidentService,
    private val scope: CoroutineScope,
    startWithKoog: Boolean,
    private val debounceMs: Long = 2_500,
) {
    @Volatile
    private var useKoog: Boolean = startWithKoog && koog != null

    val engine: String get() = if (useKoog) "koog" else "single-call"

    /** Returns the engine actually in use (Koog can't be switched on without an agent). */
    fun setEngine(koogOn: Boolean): String {
        useKoog = koogOn && koog != null
        cache.clear()
        log.info("draft engine: $engine")
        return engine
    }

    private class Entry(val key: String, val job: Deferred<PublishUpdateRequest>)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val pending = ConcurrentHashMap<String, Job>()

    // The question count too: the draft answers what passengers ask in the chat.
    private fun key(inc: railstatus.model.AdminIncident) = "$engine|${inc.facts.version}|${inc.ticker.size}|${service.questionCount(inc.facts.id)}"

    private fun current(): Drafter = if (useKoog) koog!! else single

    /** Something changed on [incidentId]: draft it ahead, once things have settled for [debounceMs]. */
    fun prefetch(incidentId: String) {
        pending.remove(incidentId)?.cancel()
        pending[incidentId] = scope.launch {
            delay(debounceMs)
            runCatching { start(service.adminIncident(incidentId)) }
        }
    }

    private fun start(inc: railstatus.model.AdminIncident): Entry {
        val k = key(inc)
        cache[inc.facts.id]?.let { e ->
            val failed = e.job.isCompleted && runCatching { e.job.getCompletionExceptionOrNull() }.getOrNull() != null
            if (e.key == k && !failed) return e
        }
        val drafter = current()
        val trains = service.trains()
        val e = Entry(k, scope.async { drafter.draft(inc, trains) })
        cache[inc.facts.id] = e
        return e
    }

    /** The draft for the operator: the prefetched one if it's still current (or still running), else a fresh one. */
    suspend fun draft(incidentId: String): PublishUpdateRequest {
        val inc = service.adminIncident(incidentId)
        val entry = start(inc)
        if (entry.job.isCompleted) log.info("draft for $incidentId served from the prefetch ($engine)")
        return try {
            entry.job.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cache.remove(incidentId)
            log.warn("prefetched draft for $incidentId failed (${e.message}); drafting again")
            current().draft(inc, service.trains())
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(DraftDesk::class.java)
    }
}
