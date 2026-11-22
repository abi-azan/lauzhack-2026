package railstatus.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import railstatus.model.Lang
import railstatus.model.Section
import railstatus.model.Train
import railstatus.nextmove.Corridor
import railstatus.nextmove.MoveLeg
import railstatus.nextmove.MoveOption
import railstatus.nextmove.MoveSource
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Real ways around a closed section, for any incident in Switzerland, from OJP (T14).
 *
 * Nothing here is hand-entered. For an incident closing the line between stations A and B:
 *  1. **The closed stations:** a rail trip A → B lists the stations in between (its intermediate stops).
 *  2. **The line around this train:** the train's last stop before the closure (P) and first stop after
 *     it (F); a rail trip P → F gives the stations in order, so we know which side of the closure each is on.
 *  3. **The trips,** in three hops: P → the near edge of the closure; around it with no trains (buses, metro,
 *     walks); then onward to the destination. OJP doesn't know about our incident, so any hop with a train
 *     through the closure is dropped. Walking between platform and bus stop is in OJP's trips.
 *     (`NotVia` was tested live on 22 Nov: it blocks either every route or none, so it isn't used.)
 *
 * Never on the push path: [cached] returns what's there and refreshes in the background; the caller falls
 * back to [NextMoveCorridor] when there's nothing yet or OJP failed.
 */
class NextMovePlanner(
    private val ojp: OjpApi,
    private val scope: CoroutineScope,
    private val ttl: Duration = Duration.ofMinutes(3),
    private val clock: () -> Instant = Instant::now,
) {
    data class Plan(val corridor: Corridor, val routes: List<MoveOption>)

    private data class Key(val from: String, val to: String, val trainId: String, val destId: String?, val boardingId: String?)
    private class Entry(val plan: Plan?, val at: Instant)

    private val plans = ConcurrentHashMap<Key, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<Key>()
    private val interiors = ConcurrentHashMap<Pair<String, String>, List<String>>()

    /**
     * The latest plan for this passenger's trip, or null; starts a refresh when missing or stale.
     * [boardingId]: a passenger still waiting on the platform there gets routes from that station.
     */
    fun cached(location: Section, train: Train, destId: String?, boardingId: String? = null): Plan? {
        val k = Key(location.from.id, location.to.id, train.id, destId, boardingId)
        val e = plans[k]
        val maxAge = if (e?.plan == null) Duration.ofSeconds(30) else ttl
        if (e == null || Duration.between(e.at, clock()) > maxAge) refresh(k, location, train, destId, boardingId)
        return e?.plan
    }

    fun prefetch(location: Section, train: Train, destId: String?, boardingId: String? = null) {
        cached(location, train, destId, boardingId)
    }

    fun clear() {
        plans.clear()
        interiors.clear()
    }

    private fun refresh(k: Key, location: Section, train: Train, destId: String?, boardingId: String?) {
        if (!inFlight.add(k)) return
        scope.launch {
            try {
                val p = withTimeoutOrNull(15_000) { plan(location, train, destId, ZonedDateTime.ofInstant(clock(), ZONE), boardingId) }
                plans[k] = Entry(p, clock())
                log.info("OJP plan for ${train.id} → ${destId ?: "next stop"}: ${p?.routes?.size ?: "none"} routes")
            } catch (e: Exception) {
                plans[k] = Entry(null, clock())
                log.warn("OJP plan for ${train.id} failed: ${e.message}")
            } finally {
                inFlight.remove(k)
            }
        }
    }

    suspend fun plan(location: Section, train: Train, destId: String?, now: ZonedDateTime, boardingId: String? = null): Plan? {
        val a = location.from.id
        val b = location.to.id
        val interior = interior(a, b, now) ?: return null.also { log.info("OJP: no rail trip $a → $b") }
        val block = interior.toSet() + a + b
        val ids = train.stops.map { it.station.id }

        // P and F: around the train's stops in the closure, or the pair of stops the closure lies between.
        var pIdx: Int
        var fIdx: Int
        var order: List<String>? = null
        val first = ids.indexOfFirst { it in block }
        if (first >= 0) {
            pIdx = first - 1
            fIdx = ids.indexOfLast { it in block } + 1
            if (pIdx < 0 || fIdx >= ids.size) return null
            order = lineOrder(ids[pIdx], ids[fIdx], a, b, now)
        } else {
            pIdx = -1; fIdx = -1
            for (i in 0 until minOf(ids.size - 1, 6)) {
                order = lineOrder(ids[i], ids[i + 1], a, b, now)
                if (order != null) { pIdx = i; fIdx = i + 1; break }
            }
        }
        if (order == null) { log.info("OJP: no line order around the closure for ${train.id}"); return null }
        val ia = order.indexOf(a)
        val ib = order.indexOf(b)
        val nearIdx = minOf(ia, ib)
        val farIdx = maxOf(ia, ib)
        val corridor = Corridor(order = order, westEdge = order[nearIdx], eastEdge = order[farIdx])

        val near = order.subList(0, nearIdx + 1).toSet() + ids.subList(0, pIdx + 1)
        val far = order.subList(farIdx, order.size).toSet() + ids.subList(fIdx, ids.size)
        val closed = order.subList(nearIdx + 1, farIdx).toSet() + interior

        val dest = destId?.takeIf { it in ids.subList(fIdx, ids.size) } ?: ids[fIdx]
        // From the train's last stop before the closure, or from the platform of a passenger still waiting there.
        // Someone waiting on a platform at or past the closure isn't going around it: no route for them here
        // (their card says when the train comes). Only platforms before the closure get routes, from there.
        val boardIdx = boardingId?.let(ids::indexOf) ?: -1
        if (boardIdx > pIdx) return Plan(corridor, emptyList())
        val oIdx = boardIdx.takeIf { it >= 0 } ?: pIdx
        val o = train.stops[oIdx]
        val depart = listOfNotNull(if (oIdx == pIdx) o.arr ?: o.dep else o.dep).mapNotNull { at(it, now) }.firstOrNull()?.takeIf { it.isAfter(now) } ?: now
        // Three hops, each a plain OJP query (OJP's NotVia blocks everything or nothing in practice, so we don't use it):
        //   1. to the near edge of the closure by any means (trains don't cross it on the way there);
        //   2. around the closure with no trains: buses, metro, walks, exactly as OJP knows them;
        //   3. onward from the first station past the closure, if the destination is further.
        fun ok(t: OjpTrip) = t.legs.none { crosses(it, closed, near, far) }
        val edge = order[nearIdx]
        val toEdge: OjpTrip? = if (ids[oIdx] == edge) null else
            ojp.trips(TripQuery(ids[oIdx], edge, depart.toInstant(), results = 3)).filter(::ok).minByOrNull { it.end } ?: return Plan(corridor, emptyList())
        val around = ojp.trips(TripQuery(edge, ids[fIdx], (toEdge?.end ?: depart.toInstant()), results = 3, noRail = true)).filter(::ok)
        val routes = around.sortedBy { it.end }.take(2).mapNotNull { mid ->
            val onward = if (dest == ids[fIdx]) null else
                ojp.trips(TripQuery(ids[fIdx], dest, mid.end, results = 3)).filter(::ok).minByOrNull { it.end } ?: return@mapNotNull null
            val parts = listOfNotNull(toEdge, mid, onward)
            OjpTrip(parts.first().start, parts.last().end, parts.flatMap { it.legs })
        }.mapIndexed { i, t -> option(i, t) }
        log.info("OJP ${train.id}: closed ${closed.size} stations, from ${ids[oIdx]} via $edge to $dest: ${routes.size} routes")
        return Plan(corridor, routes)
    }

    /** The stations strictly between [a] and [b] on the line, from a rail trip a → b. */
    private suspend fun interior(a: String, b: String, now: ZonedDateTime): List<String>? {
        interiors[a to b]?.let { return it }
        val trips = ojp.trips(TripQuery(a, b, now.toInstant(), results = 6, railOnly = true))
        val calls = trips.map(::railCalls).filter { it.firstOrNull() == a && it.lastOrNull() == b }.maxByOrNull { it.size } ?: return null
        return calls.subList(1, calls.size - 1).also { interiors[a to b] = it }
    }

    /** The stations from [p] to [f] in line order, through both edges, from a rail trip p → f. */
    private suspend fun lineOrder(p: String, f: String, a: String, b: String, now: ZonedDateTime): List<String>? =
        ojp.trips(TripQuery(p, f, now.toInstant(), results = 8, railOnly = true))
            .map(::railCalls)
            .filter { a in it && b in it }
            .maxByOrNull { it.size }

    private fun railCalls(t: OjpTrip): List<String> =
        t.legs.filter { it.mode == "rail" }.flatMap { l -> l.calls.mapNotNull { it.stationId } }
            .fold(mutableListOf()) { acc, id -> if (acc.lastOrNull() != id) acc += id; acc }

    private fun option(i: Int, t: OjpTrip): MoveOption {
        val legs = t.legs.map { l ->
            MoveLeg(
                mode = when (l.mode) { "rail" -> "train"; "water" -> "boat"; else -> l.mode },
                line = l.line, from = l.board.name, to = l.alight.name,
                dep = l.board.time?.let(::hhmm), arr = l.alight.time?.let(::hhmm),
            )
        }
        val title = legs.filter { it.mode != "walk" }.joinToString(" · ") { listOfNotNull(it.mode.replaceFirstChar(Char::uppercase), it.line).joinToString(" ") }
        return MoveOption(
            id = "ojp-$i", source = MoveSource.TIMETABLE, title = Lang.entries.associateWith { title }, legs = legs,
            departAt = hhmm(t.start), arriveEarliest = hhmm(t.end), arriveLatest = hhmm(t.end),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(NextMovePlanner::class.java)
        private val ZONE: ZoneId = ZoneId.of("Europe/Zurich")

        /** A rail leg that runs through the closure: it calls at a closed station, or on both sides of it. */
        fun crosses(l: OjpLeg, closed: Set<String>, near: Set<String>, far: Set<String>): Boolean {
            if (l.mode != "rail") return false
            val ids = l.calls.mapNotNull { it.stationId }
            return ids.any { it in closed } || (ids.any { it in near } && ids.any { it in far })
        }

        private fun hhmm(i: Instant): String = ZonedDateTime.ofInstant(i, ZONE).let { "%02d:%02d".format(it.hour, it.minute) }

        /** "HH:MM" on the day of [now], within 12 hours of it. */
        private fun at(t: String, now: ZonedDateTime): ZonedDateTime? {
            val (h, m) = t.split(":").mapNotNull(String::toIntOrNull).takeIf { it.size == 2 } ?: return null
            var z = now.withHour(h).withMinute(m).withSecond(0).withNano(0)
            if (Duration.between(now, z).toHours() > 12) z = z.minusDays(1)
            if (Duration.between(z, now).toHours() > 12) z = z.plusDays(1)
            return z
        }
    }
}
