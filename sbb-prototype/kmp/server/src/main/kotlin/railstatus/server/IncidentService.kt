package railstatus.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import railstatus.model.*
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

private val ZONE: ZoneId = ZoneId.of("Europe/Zurich")
fun now(): OffsetDateTime = OffsetDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS)
fun OffsetDateTime.iso(): String = format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
fun OffsetDateTime.hhmm(): String = atZoneSameInstant(ZONE).format(DateTimeFormatter.ofPattern("HH:mm"))
fun newId(prefix: String) = prefix + "-" + UUID.randomUUID().toString().take(8)

class NotFound(what: String) : RuntimeException("$what not found")

/** Message on the `incident.published` stream. */
@Serializable
data class PublishedMsg(val incidentId: String, val seq: Int)

/**
 * The incident service. It consumes the ticker stream, opens incidents for the trains on the affected
 * line, publishes the admin's updates to `incident.published`, and its notifier consumes that stream
 * and fans every update out to the passengers checked in to the affected trains.
 */
class IncidentService(
    private val bus: Bus,
    private val hub: Hub,
    private val scope: CoroutineScope,
    private val autoAckSeconds: Long,
    trains: List<Train> = Fixtures.trains,
    private val templates: List<Template> = Fixtures.templates,
    /** Other wordings of the templates for when a place, cause or delay is unknown (R8). */
    private val fallbacks: Map<String, Fixtures.Fallbacks> = Fixtures.fallbacks,
    private val push: PushSender? = null,
    /** "HH:MM" of the trains' day that should fall on now; see [TrainClock]. Null keeps their times. */
    private val trainsAnchor: String? = null,
    /** Real routes around a closure (T14). Only with anchored trains: OJP answers for today, not their own day. */
    ojp: OjpApi? = null,
) {
    private val planner: NextMovePlanner? = ojp?.takeIf { trainsAnchor != null }?.let { NextMovePlanner(it, scope) }

    /** The timetable as loaded; [trains] is this re-timed to the demo clock (at start and on reset). */
    private val baseTrains: List<Train> = trains

    @Volatile
    private var trains: List<Train> = TrainClock.shift(baseTrains, trainsAnchor)

    /** Scheduled detours for "next move", re-timed like [trains]. */
    @Volatile
    private var corridor: railstatus.nextmove.Corridor = NextMoveCorridor.forDemo(trainsAnchor)

    private class State(
        var facts: IncidentFacts,
        val sourceEventId: String,
        val reasonCode: String?,
        var location: Section?,
        val detectedAt: OffsetDateTime,
        /** The upstream event's own timestamp: the start of the "time to first notification" clock. */
        val upstreamAt: OffsetDateTime,
        var sourceEndedAt: String? = null,
        val ticker: MutableList<TickerEvent> = mutableListOf(),
        var metrics: IncidentMetrics = IncidentMetrics(),
        var autoAck: Job? = null,
    )

    /** Called (outside the lock) when an incident's ticker or published state changes; the draft desk drafts ahead. */
    @Volatile
    var onChange: ((String) -> Unit)? = null

    private val mutex = Mutex()
    private val checkIns = LinkedHashMap<String, CheckIn>() // by token
    private val incidents = LinkedHashMap<String, State>()
    private val bySource = HashMap<String, String>()

    fun start() {
        bus.subscribe(scope, Streams.TICKER, "incident-service") { json ->
            onTicker(RailJson.decodeFromString(TickerEvent.serializer(), json))
        }
        bus.subscribe(scope, Streams.PUBLISHED, "notifier") { json ->
            notify(RailJson.decodeFromString(PublishedMsg.serializer(), json))
        }
    }

    // ---- trains & check-ins ------------------------------------------------------------------------

    fun trains(): List<Train> = trains

    /** Every station the demo trains stop at, for the dashboard's "New incident" form. */
    fun stations(): List<Station> = trains.flatMap { t -> t.stops.map { it.station } }.distinctBy { it.id }.sortedBy { it.name }
    fun train(id: String): Train = trains.find { it.id == id } ?: throw NotFound("train $id")
    fun templates(): List<Template> = templates

    suspend fun checkIn(req: CheckInRequest): CheckInResponse {
        val train = train(req.trainId)
        val ci = CheckIn(
            passengerId = newId("p"), token = UUID.randomUUID().toString(), trainId = train.id,
            car = req.car, lang = req.lang, method = req.method, destinationId = req.destinationId, at = now().iso(),
            // Only a station the train stops at counts; anything else means "on board".
            boardingId = req.boardingId?.takeIf { id -> train.stops.any { it.station.id == id } },
        )
        val (incident, count) = mutex.withLock {
            checkIns[ci.token] = ci
            openFactsFor(train.id) to countOn(train.id)
        }
        hub.toAdmins(CheckinsChanged(now().iso(), train.id, count))
        refreshAdminsFor(train.id)
        return CheckInResponse(ci, train, incident)
    }

    suspend fun checkOut(token: String) {
        val (trainId, count) = mutex.withLock {
            val ci = checkIns.remove(token) ?: throw NotFound("check-in")
            ci.trainId to countOn(ci.trainId)
        }
        hub.toAdmins(CheckinsChanged(now().iso(), trainId, count))
        refreshAdminsFor(trainId)
    }

    suspend fun checkInByToken(token: String): CheckIn? = mutex.withLock { checkIns[token] }

    /** For the chat service: who is this token, which train, and what's published on it. */
    suspend fun checkInView(token: String): CheckInResponse {
        val ci = checkInByToken(token) ?: throw NotFound("check-in")
        return CheckInResponse(ci, train(ci.trainId), openIncidentFor(ci.trainId))
    }

    suspend fun setPush(token: String, sub: PushSubscriptionJson) {
        mutex.withLock {
            val ci = checkIns[token] ?: throw NotFound("check-in")
            checkIns[token] = ci.copy(hasPush = true)
        }
        push?.register(token, sub)
    }

    /** The newest published, unresolved incident on a train. */
    suspend fun openIncidentFor(trainId: String): IncidentFacts? = mutex.withLock { openFactsFor(trainId) }

    /** The newest published incident on a train; a resolved one still counts for 15 min, so a reconnect
     *  doesn't wipe the "resolved" screen and the chat's grace period works (same 15 min as `chatOpen`). */
    private fun openFactsFor(trainId: String): IncidentFacts? = incidents.values.map { it.facts }.lastOrNull {
        trainId in it.trainIds && it.stage != Stage.DETECTED &&
            (it.stage != Stage.RESOLVED || recentlyResolved(it.resolvedAt))
    }

    private fun recentlyResolved(at: String?): Boolean =
        at != null && runCatching { OffsetDateTime.parse(at).isAfter(now().minusMinutes(15)) }.getOrDefault(false)

    private fun countOn(trainId: String) = checkIns.values.count { it.trainId == trainId }

    // ---- ticker → incidents --------------------------------------------------------------------------

    suspend fun onTicker(ev: TickerEvent) {
        val st: State? = mutex.withLock {
            // A late message about a resolved incident (e.g. "Liveticker terminé" after Resolve) belongs to it;
            // only a new `created` event for that source opens a fresh incident (R1).
            val existing = bySource[ev.sourceEventId]?.let(incidents::get)
                ?.takeIf { it.facts.stage != Stage.RESOLVED || ev.kind != TickerKind.CREATED }
            when {
                existing != null -> existing.also { s ->
                    s.ticker += ev
                    if (s.location == null && ev.location != null) { s.location = ev.location; prefetchRoutes(s) }
                    if (s.facts.section == null && ev.line != null) s.facts = s.facts.copy(section = ev.line)
                    if (ev.kind == TickerKind.ENDED) s.sourceEndedAt = ev.ts
                }
                ev.kind == TickerKind.ENDED -> null // an end for something we never opened
                else -> {
                    val id = newId("inc")
                    val s = State(
                        facts = IncidentFacts(
                            id = id, version = 0, stage = Stage.DETECTED,
                            trainIds = affectedTrains(ev, trains).map { it.id }, section = ev.line,
                        ),
                        sourceEventId = ev.sourceEventId, reasonCode = ev.reasonCode, location = ev.location,
                        detectedAt = now(), upstreamAt = runCatching { OffsetDateTime.parse(ev.ts) }.getOrElse { now() },
                    )
                    s.ticker += ev
                    incidents[id] = s
                    bySource[ev.sourceEventId] = id
                    s.autoAck = scheduleAutoAck(id)
                    prefetchRoutes(s)
                    log.info("detected $id from ${ev.sourceEventId}: trains ${s.facts.trainIds}")
                    s
                }
            }
        }
        hub.toAdmins(TickerEntry(now().iso(), ev, st?.facts?.id))
        if (st != null) hub.toAdmins(IncidentChanged(now().iso(), mutex.withLock { st.toAdmin() }))
        if (st != null) onChange?.invoke(st.facts.id)
    }

    private fun scheduleAutoAck(id: String): Job? {
        if (autoAckSeconds <= 0) return null
        return scope.launch {
            delay(autoAckSeconds * 1000)
            // Drop our own job reference first: publish() cancels the pending auto-ack, and that would be us.
            val stillDetected = mutex.withLock {
                incidents[id]?.let { it.autoAck = null; it.facts.stage == Stage.DETECTED } ?: false
            }
            if (stillDetected) {
                log.info("auto-ack $id after ${autoAckSeconds}s without an update")
                publish(id, draft(id, DraftRequest(Fixtures.AUTO_ACK_TEMPLATE)), Author.AUTO)
            }
        }
    }

    // ---- admin -------------------------------------------------------------------------------------

    /** An incident opened by hand. It takes the same path as a ticker event, minus the queue. */
    suspend fun createManual(req: CreateIncidentRequest): AdminIncident {
        val sourceId = newId("manual")
        onTicker(
            TickerEvent(
                id = newId("tk"), sourceEventId = sourceId, kind = TickerKind.CREATED, source = TickerSource.MANUAL,
                ts = now().iso(), reasonCode = req.reasonCode, reasonText = req.cause, line = req.line,
                location = req.location, lines = req.lines, text = req.note.ifBlank { "Opened from the dashboard" },
            ),
        )
        return mutex.withLock { incidents[bySource[sourceId]]!!.toAdmin() }
    }

    private val questions = LinkedHashMap<String, QuestionsChanged>()

    private val questionCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** From the chat service; rebroadcast to the dashboards. The draft desk drafts again: the draft reads the question groups. */
    suspend fun onQuestions(ev: WsEvent) {
        if (ev is QuestionsChanged) {
            mutex.withLock { questions[ev.incidentId] = ev }
            questionCounts[ev.incidentId] = ev.clusters.sumOf { it.count }
        }
        hub.toAdmins(ev)
        if (ev is QuestionsChanged) onChange?.invoke(ev.incidentId)
    }

    /** How many passengers have asked about [incidentId] so far (part of the draft cache key). */
    fun questionCount(incidentId: String): Int = questionCounts[incidentId] ?: 0

    suspend fun questions(): List<QuestionsChanged> = mutex.withLock { questions.values.toList() }

    suspend fun adminIncidents(): List<AdminIncident> = mutex.withLock { incidents.values.reversed().map { it.toAdmin() } }
    suspend fun adminIncident(id: String): AdminIncident = mutex.withLock { (incidents[id] ?: throw NotFound("incident $id")).toAdmin() }

    /** Published incidents for the public status page, newest first. */
    suspend fun publicIncidents(): List<IncidentFacts> =
        mutex.withLock { incidents.values.reversed().map { it.facts }.filter { it.stage != Stage.DETECTED } }

    suspend fun publicIncident(id: String): IncidentFacts = mutex.withLock {
        incidents[id]?.facts?.takeIf { it.stage != Stage.DETECTED } ?: throw NotFound("incident $id")
    }

    suspend fun draft(id: String, req: DraftRequest): PublishUpdateRequest {
        val template = templates.find { it.id == req.templateId } ?: throw NotFound("template ${req.templateId}")
        val (facts, location) = mutex.withLock { (incidents[id] ?: throw NotFound("incident $id")).let { it.facts to it.location } }
        // Never a "?" or a raw {placeholder} for a passenger (R8): the line falls back to the location and the
        // location to the line; a place, cause or delay that is still unknown picks a wording without it.
        val section = facts.section ?: location
        val loc = location ?: facts.section
        val delay = req.delayMin ?: facts.delayMin
        val cause = (req.cause ?: facts.cause)?.takeIf { c -> c.values.any { it.isNotBlank() } }
        val known = buildSet {
            add("next")
            if (section != null) add("section")
            if (loc != null) addAll(listOf("locFrom", "locTo"))
            if (cause != null) add("cause")
            if (delay != null) addAll(listOf("delayLow", "delayHigh"))
        }
        fun pick(own: Text, others: List<Text>?): Text =
            (listOf(own) + others.orEmpty()).firstOrNull { t -> t.values.all { placeholdersIn(it).all(known::contains) } } ?: own
        val alt = fallbacks[template.id]
        val tpl = template.copy(title = pick(template.title, alt?.titles), body = pick(template.body, alt?.bodies))
        val vars = mapOf(
            "section" to (section?.let { "${it.from.name} – ${it.to.name}" } ?: "?"),
            "locFrom" to (loc?.from?.name ?: "?"),
            "locTo" to (loc?.to?.name ?: "?"),
            "next" to now().plusMinutes((tpl.nextUpdateInMin ?: 15).toLong()).hhmm(),
            "delayLow" to (delay?.low?.toString() ?: "?"),
            "delayHigh" to (delay?.high?.toString() ?: "?"),
        )
        // The draft carries the delay its text states, so publishing it keeps the numbers and the text in step.
        val statesDelay = (tpl.title.values + tpl.body.values).any { "{delayLow}" in it || "{delayHigh}" in it }
        return tpl.render(vars, cause).copy(delayMin = delay.takeIf { statesDelay })
    }

    private fun placeholdersIn(s: String): List<String> = PLACEHOLDER.findAll(s).map { it.groupValues[1] }.toList()

    suspend fun publish(id: String, req: PublishUpdateRequest, author: Author = Author.ADMIN): IncidentUpdate {
        require(req.stage != Stage.DETECTED) { "can't publish stage 'detected'" }
        require(req.title.isNotEmpty() && req.body.isNotEmpty()) { "title and body need at least one language" }
        val update = mutex.withLock {
            val s = incidents[id] ?: throw NotFound("incident $id")
            val t = now()
            val f = s.facts
            val seq = f.version + 1
            val nextBy = req.nextUpdateInMin?.let { t.plusMinutes(it.toLong()).iso() }
            val resolved = req.stage == Stage.RESOLVED
            val u = IncidentUpdate(
                id = newId("upd"), incidentId = id, seq = seq, stage = req.stage,
                title = fillLanguages(req.title), body = fillLanguages(req.body), publishedAt = t.iso(),
                nextUpdateBy = if (resolved) null else nextBy, author = author, templateId = req.templateId,
            )
            s.facts = f.copy(
                version = seq,
                stage = req.stage,
                cause = req.cause.keepOrClear(f.cause),
                // The delay belongs to the update that states it, like its text: an update without one (or
                // "resolved") clears it, so the app never shows numbers the latest update doesn't say.
                delayMin = if (resolved) null else req.delayMin,
                alternatives = if (resolved) emptyList() else req.alternatives ?: f.alternatives, // stale once the line is open
                location = if (resolved) null else s.location ?: f.location, // for the app's map; every update names the place
                onboard = req.onboard.keepOrClear(f.onboard),
                nextUpdateBy = u.nextUpdateBy,
                openedAt = f.openedAt ?: t.iso(),
                resolvedAt = if (resolved) t.iso() else null,
                updates = f.updates + u,
            )
            s.autoAck?.cancel()
            s.autoAck = null
            u
        }
        bus.publish(Streams.PUBLISHED, RailJson.encodeToString(PublishedMsg.serializer(), PublishedMsg(id, update.seq)))
        onChange?.invoke(id)
        hub.toAdmins(IncidentChanged(now().iso(), adminIncident(id)))
        return update
    }

    private fun Text?.keepOrClear(old: Text?): Text? = when {
        this == null -> old
        isEmpty() -> null
        else -> fillLanguages(this)
    }

    // ---- notifier --------------------------------------------------------------------------------------

    private suspend fun notify(msg: PublishedMsg) {
        val (facts, update, recipients) = mutex.withLock {
            val s = incidents[msg.incidentId] ?: return
            val u = s.facts.updates.find { it.seq == msg.seq } ?: return
            Triple(s.facts, u, checkIns.values.filter { it.trainId in s.facts.trainIds })
        }
        val event = IncidentUpdated(now().iso(), facts, update)
        var delivered = 0
        for (ci in recipients) {
            val ws = hub.toPassenger(ci.token, event.copy(nextMove = nextMoveFor(ci, facts)))
            val pushed = if (ci.hasPush) push?.send(ci, facts, update) ?: false else false
            if (ws > 0 || pushed) delivered++
        }
        hub.toStatus(event)
        val admin = mutex.withLock {
            val s = incidents[msg.incidentId] ?: return
            val t = now()
            s.metrics = s.metrics.copy(
                lastDeliveries = delivered,
                firstNotifiedAt = s.metrics.firstNotifiedAt ?: t.iso().takeIf { delivered > 0 },
                secondsToFirstNotification = s.metrics.secondsToFirstNotification
                    ?: Duration.between(s.upstreamAt, t).toMillis().div(1000.0).takeIf { delivered > 0 },
            )
            s.toAdmin()
        }
        log.info("update ${update.seq} of ${msg.incidentId} delivered to $delivered/${recipients.size} passengers")
        hub.toAdmins(IncidentChanged(now().iso(), admin))
    }

    // ---- next move (T3) ----------------------------------------------------------------------------

    /**
     * The next move for one passenger on an incident, or null when it doesn't apply. Uses OJP's real routes
     * when the planner has them; otherwise the hand-entered corridor (Allaman – Renens VD only).
     */
    suspend fun nextMoveFor(ci: CheckIn, facts: IncidentFacts): railstatus.nextmove.NextMove? {
        val location = mutex.withLock { incidents[facts.id]?.location }
        val train = trains.find { it.id == ci.trainId } ?: return null
        // An OJP answer with no way around (or none yet) falls back to the corridor, like OJP being off.
        val plan = location?.let { planner?.cached(it, train, ci.destinationId, ci.boardingId) }?.takeIf { it.routes.isNotEmpty() }
        if (plan != null) return railstatus.nextmove.nextMove(facts, train, ci, now().hhmm(), plan.corridor, plan.routes)
        if (!NextMoveCorridor.covers(location)) return null
        return railstatus.nextmove.nextMove(facts, train, ci, now().hhmm(), corridor)
    }

    /** Ask OJP as soon as an incident opens, so the routes are ready before the first update goes out. */
    private fun prefetchRoutes(s: State) {
        val p = planner ?: return
        val loc = s.location ?: return
        for (t in trains.filter { it.id in s.facts.trainIds }) {
            val trips = checkIns.values.filter { it.trainId == t.id }.map { it.destinationId to it.boardingId }.toSet().ifEmpty { setOf(null to null) }
            trips.forEach { (dest, board) -> p.prefetch(loc, t, dest, board) }
        }
    }

    // ---- misc --------------------------------------------------------------------------------------

    suspend fun reset() {
        mutex.withLock {
            incidents.values.forEach { it.autoAck?.cancel() }
            incidents.clear()
            bySource.clear()
            checkIns.clear()
            questions.clear()
            questionCounts.clear()
            trains = TrainClock.shift(baseTrains, trainsAnchor)
            corridor = NextMoveCorridor.forDemo(trainsAnchor)
        }
        planner?.clear()
        bus.reset()
        push?.clear()
        hub.toEveryone(Reset(now().iso()))
    }

    private suspend fun refreshAdminsFor(trainId: String) {
        val changed = mutex.withLock {
            incidents.values.filter { trainId in it.facts.trainIds && it.facts.stage != Stage.RESOLVED }.map { it.toAdmin() }
        }
        changed.forEach { hub.toAdmins(IncidentChanged(now().iso(), it)) }
    }

    /** Call with the mutex held. */
    private fun State.toAdmin() = AdminIncident(
        facts = facts,
        sourceEventId = sourceEventId,
        reasonCode = reasonCode,
        location = location,
        detectedAt = detectedAt.iso(),
        sourceEndedAt = sourceEndedAt,
        ticker = ticker.toList(),
        passengersByTrain = facts.trainIds.associateWith { countOn(it) },
        metrics = metrics,
        autoAckAt = if (facts.stage == Stage.DETECTED && autoAckSeconds > 0) detectedAt.plusSeconds(autoAckSeconds).iso() else null,
    )

    private companion object {
        val log = LoggerFactory.getLogger(IncidentService::class.java)
        val PLACEHOLDER = Regex("""\{(\w+)\}""")
    }
}
