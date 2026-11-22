package railstatus.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import railstatus.agent.DraftAgent
import railstatus.agent.DraftOutput
import railstatus.agent.DraftSources
import railstatus.agent.Langs
import railstatus.model.*
import railstatus.nextmove.MoveSource
import railstatus.nextmove.NextMove

/** Something that drafts the next status update for an incident. The admin always reviews it before publishing. */
interface Drafter {
    suspend fun draft(inc: AdminIncident, trains: List<Train>): PublishUpdateRequest
}

/**
 * The AI draft as a Koog agent (T17, `kmp/agent`), behind `DRAFT_ENGINE=koog`.
 *
 * The agent reads the incident through read-only tools: the scrubbed ticker, the trains, what's published, and
 * the passenger's next move and routes around the closure (the same deterministic functions as the card,
 * OJP included). Its typed answer becomes the same [PublishUpdateRequest] the single-call drafter returns, with
 * the same output guard. It also sees what passengers ask in the chat, as question groups (label × count, never their
 * words), so the next update can answer them. Any error or a slow run falls back to [fallback], so the dashboard always gets a draft.
 */
class KoogDrafter(
    private val agent: DraftAgent,
    private val fallback: Drafter,
    private val service: IncidentService,
    private val guard: (PublishUpdateRequest, Collection<String>) -> PublishUpdateRequest,
    private val timeoutMs: Long = 60_000,
) : Drafter {
    override suspend fun draft(inc: AdminIncident, trains: List<Train>): PublishUpdateRequest {
        val run = try {
            withTimeout(timeoutMs) { agent.draft(sources(inc, trains), AiDrafter.SYSTEM) }
        } catch (e: TimeoutCancellationException) {
            log.warn("Koog draft for ${inc.facts.id} took over ${timeoutMs / 1000}s; using the single-call drafter")
            return fallback.draft(inc, trains)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Koog draft for ${inc.facts.id} failed (${e.message}); using the single-call drafter")
            return fallback.draft(inc, trains)
        }
        log.info("Koog draft for ${inc.facts.id}: tools ${run.toolCalls.map { it.tool }}")
        // The dashboard shows which tools the agent called, in order (the pitch's "watch the agent work" moment).
        return guard(toRequest(run.output), places(inc, trains)).copy(draftedBy = DraftInfo("koog", tools = run.toolCalls.map { it.tool }))
    }

    internal fun toRequest(out: DraftOutput): PublishUpdateRequest {
        val stage = when (out.stage) {
            "identified" -> Stage.IDENTIFIED
            "monitoring" -> Stage.MONITORING
            "resolved" -> Stage.RESOLVED
            else -> Stage.INVESTIGATING
        }
        val next = out.nextUpdateInMin.coerceIn(10, 30)
        val vars = mapOf("next" to now().plusMinutes(next.toLong()).hhmm())
        return PublishUpdateRequest(
            stage = stage,
            title = text(out.title).fill(vars),
            body = text(out.body).fill(vars),
            nextUpdateInMin = if (stage == Stage.RESOLVED) null else next,
            cause = if (stage == Stage.INVESTIGATING) null else out.cause?.let(::text),
            alternatives = out.alternatives.mapIndexed { i, a -> Alternative("ai-${i + 1}", text(a)) },
            templateId = "ai",
        )
    }

    private fun sources(inc: AdminIncident, trains: List<Train>) = object : DraftSources {
        val f = inc.facts
        val places = places(inc, trains)

        override suspend fun incidentSummary() = buildString {
            appendLine("Now: ${now().hhmm()}")
            appendLine("Affected line: ${f.section?.let { "${it.from.name} – ${it.to.name}" } ?: "unknown"}")
            inc.location?.let { appendLine("Location: between ${it.from.name} and ${it.to.name}") }
            appendLine("Incident detected: ${inc.detectedAt.substring(11, 16)}")
            appendLine("Current published stage: ${f.stage.name.lowercase()}")
            inc.ticker.firstNotNullOfOrNull { it.reasonText?.get(Lang.EN) }?.let { appendLine("Cause reported by the source system: $it") }
        }.trim()

        override suspend fun ticker() =
            inc.ticker.map { "${it.ts.substring(11, 16)} [${it.source.name}] ${Pii.scrub(it.text, keep = places).replace('\n', ' ')}" }

        override suspend fun affectedTrains() =
            f.trainIds.mapNotNull { id -> trains.find { it.id == id } }.map { "${it.id}: ${it.line} ${it.number} to ${it.direction}" }

        override suspend fun publishedUpdates() =
            f.updates.map { "${it.publishedAt.substring(11, 16)} ${it.title[Lang.EN]}: ${it.body[Lang.EN]}" }

        /** What a passenger on that train sees on their card now (destination assumed, as for a fresh check-in). */
        override suspend fun nextMove(trainId: String): NextMove? =
            if (trainId !in f.trainIds) null
            else service.nextMoveFor(CheckIn(passengerId = "draft-agent", token = "draft-agent", trainId = trainId, at = now().iso()), f)

        override suspend fun routesAround(): List<String>? = f.trainIds.flatMap { id ->
            val nm = nextMove(id) ?: return@flatMap emptyList()
            nm.options.filter { it.source == MoveSource.TIMETABLE }.map { o ->
                "From ${o.legs.firstOrNull()?.from ?: "?"}: ${o.title[Lang.EN]}, leaves ${o.departAt}, in ${nm.destination.name} by ${o.arriveLatest}"
            }
        }.distinct().ifEmpty { null }

        override suspend fun passengerQuestions(): List<String> =
            questionLines(service.questions().firstOrNull { it.incidentId == f.id }?.clusters.orEmpty())
    }

    private fun text(l: Langs): Text = mapOf(Lang.DE to l.de, Lang.FR to l.fr, Lang.IT to l.it, Lang.EN to l.en)

    /** Station names stay in the text even where they'd look like a name (as in the single-call drafter). */
    private fun places(inc: AdminIncident, trains: List<Train>): Set<String> =
        (trains.flatMap { t -> t.stops.map { it.station.name } } +
            listOfNotNull(inc.facts.section, inc.location).flatMap { listOf(it.from.name, it.to.name) }).toSet()

    private companion object {
        val log = LoggerFactory.getLogger(KoogDrafter::class.java)
    }
}

/** The chat's question groups for the draft: label and count only. A group's `sample` is a passenger's own words: never. */
internal fun questionLines(clusters: List<QuestionCluster>): List<String> =
    clusters.sortedWith(compareByDescending<QuestionCluster> { it.urgent }.thenByDescending { it.count }).map { c ->
        "${c.label}: ${c.count} passenger${if (c.count == 1) "" else "s"}${if (c.urgent) " (urgent)" else ""}"
    }
