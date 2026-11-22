@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import railstatus.mobile.incident.TrainView
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import railstatus.model.Train as ApiTrain

// The saved trip in Trips is the train the passenger is checked in on (the backend's, or the offline demo's scripted
// one), from where they board to where they get off. DemoTripSync feeds it in through [DemoTrip.setLive] and clears
// it at check-out; without a check-in there is no trip, and Trips shows its empty state. The trip has a stable id
// ([DemoTrip.ID]): it is saved once and always rebuilds with the current train, and [getConnection] resolves it like
// any other id.

/** A stop of the trip: its station, times and platform. */
data class DemoStop(val station: Station, val arr: Instant?, val dep: Instant?, val platform: String?)

/** The checked-in train cut to the passenger's ride: first stop where they board, last where they get off. */
data class DemoRide(
    val trainId: String,
    /** "IR", "IC", ... (the backend's free string). */
    val product: String,
    /** Line number for the badge: "90" for IR 90. */
    val line: String,
    val number: String,
    val direction: String,
    val stops: List<DemoStop>,
)

object DemoTrip {
    /** The saved trip's id, for [getConnection] and [AppState.saved]. It carries no time: see [connection]. */
    const val ID = "checked-in-trip"

    /** Disruptions on the trip that come from an incident (not the timetable) have ids with this prefix. */
    const val INCIDENT_PREFIX = "incident-"

    private val _live = MutableStateFlow<DemoRide?>(null)

    /** The checked-in ride, or null: not checked in (no saved trip). */
    val live: StateFlow<DemoRide?> = _live.asStateFlow()

    private val _disruption = MutableStateFlow<Disruption?>(null)

    /** The open incident on the train as the red banner's disruption (id starts with [INCIDENT_PREFIX]), or null. */
    val disruption: StateFlow<Disruption?> = _disruption.asStateFlow()

    fun setLive(ride: DemoRide?) {
        _live.value = ride?.takeIf { it.stops.size >= 2 && it.stops.first().dep != null && it.stops.last().arr != null }
    }

    fun setDisruption(d: Disruption?) {
        _disruption.value = d
    }

    /** The trip as a [Connection], or null when not checked in. */
    fun connection(zone: TimeZone = AppClock.zone): Connection? = _live.value?.let { build(it, _disruption.value, zone) }

    /**
     * The backend's train cut to the passenger's ride: from [boardingId] (a station id of the train) or else
     * [boardingName], else its first stop; to [destId] or else [destName] (a later stop), else its last stop. Its
     * "HH:MM" times are taken on [now]'s day, as the passenger screens do (`mapTrain`), rolling over midnight.
     */
    fun fromTrain(
        t: ApiTrain,
        boardingId: String? = null,
        boardingName: String? = null,
        destId: String? = null,
        destName: String? = null,
        now: Instant = now(),
        zone: TimeZone = AppClock.zone,
    ): DemoRide? {
        var day = now.local(zone).date
        var last: Instant? = null
        fun at(hhmm: String?): Instant? {
            val p = hhmm?.split(':')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
            if (p.size != 2 || p[0] !in 0..23 || p[1] !in 0..59) return null
            var i = localInstant(day, p[0], p[1], zone)
            // A train running past midnight: its later times are on the next day.
            if (last != null && i < addMin(last!!, -6 * 60)) {
                day = day.plus(DatePeriod(days = 1))
                i = localInstant(day, p[0], p[1], zone)
            }
            last = i
            return i
        }
        val raw = t.stops.map { s -> RawStop(s.station.id, s.station.name, at(s.arr), at(s.dep), s.platform) }
        val line = if (t.line.startsWith(t.product)) t.line.substring(t.product.length).trim() else t.line
        return ride(t.id, t.product, line, t.number, t.direction, raw, boardingId, boardingName, destId, destName)
    }

    /** The offline demo's scripted train (planned time per stop), cut the same way. It has no platforms. */
    fun fromPlanned(
        t: TrainView,
        boardingId: String? = null,
        boardingName: String? = null,
        destId: String? = null,
        destName: String? = null,
    ): DemoRide? {
        val raw = t.stops.map { RawStop(it.stationId, it.station, it.planned, it.planned, null) }
        return ride(t.id, t.product, t.line, t.number, t.direction, raw, boardingId, boardingName, destId, destName)
    }

    private class RawStop(val id: String?, val name: String, val arr: Instant?, val dep: Instant?, val platform: String?)

    private fun ride(
        trainId: String, product: String, line: String, number: String, direction: String, raw: List<RawStop>,
        boardingId: String?, boardingName: String?, destId: String?, destName: String?,
    ): DemoRide? {
        if (raw.size < 2) return null
        fun indexOf(id: String?, name: String?, after: Int): Int {
            val range = (after + 1) until raw.size
            id?.takeIf { it.isNotEmpty() }?.let { v -> range.firstOrNull { raw[it].id == v }?.let { return it } }
            name?.takeIf { it.isNotEmpty() }?.let { v -> range.firstOrNull { raw[it].name == v }?.let { return it } }
            return -1
        }
        val from = indexOf(boardingId, boardingName, -1).takeIf { it in 0 until raw.size - 1 } ?: 0
        val to = indexOf(destId, destName, from).takeIf { it > from } ?: (raw.size - 1)
        val cut = raw.subList(from, to + 1)
        val stations = stationsOf(cut.map { it.id to it.name })
        val stops = cut.mapIndexed { i, s ->
            DemoStop(
                station = stations[i],
                arr = if (i == 0) null else s.arr,
                dep = if (i == cut.size - 1) null else s.dep,
                platform = s.platform,
            )
        }
        return DemoRide(trainId, product, line, number, direction, stops)
    }

    /**
     * The app's stations for the backend's (id, name) stops: a known station by name, else one of the backend's own
     * id and name, placed between its known neighbours (the map needs coordinates).
     */
    private fun stationsOf(stops: List<Pair<String?, String>>): List<Station> {
        val known = stops.map { (_, name) -> STATIONS.firstOrNull { it.name == name } }
        return stops.mapIndexed { i, (id, name) ->
            known[i] ?: run {
                val before = (i - 1 downTo 0).firstOrNull { known[it] != null }
                val after = (i + 1 until stops.size).firstOrNull { known[it] != null }
                val (lat, lon) = when {
                    before != null && after != null -> {
                        val f = (i - before).toDouble() / (after - before)
                        val a = known[before]!!
                        val b = known[after]!!
                        (a.lat + (b.lat - a.lat) * f) to (a.lon + (b.lon - a.lon) * f)
                    }
                    before != null -> known[before]!!.let { (it.lat + 0.012 * (i - before)) to (it.lon + 0.012 * (i - before)) }
                    after != null -> known[after]!!.let { (it.lat - 0.012 * (after - i)) to (it.lon - 0.012 * (after - i)) }
                    // Nothing known on the whole ride: the middle of Switzerland.
                    else -> (46.8 + 0.012 * i) to (8.2 + 0.012 * i)
                }
                Station(id = id?.takeIf { it.isNotEmpty() } ?: ("ride-" + fnv1a(name).toString(16)), name = name, lat = lat, lon = lon)
            }
        }
    }

    // ------------------------------------------------------------ the connection

    private fun build(r: DemoRide, incident: Disruption?, zone: TimeZone): Connection {
        val dep = r.stops.first().dep!!
        val seed = fnv1a("${r.trainId}:${dep.local(zone).date}")
        val hour = dep.local(zone).hour
        val occ = rideOccupancy(seed, hour)
        val product = Product.fromCode(r.product)
        val mode = product?.let(::modeOf) ?: Mode.TRAIN
        val leg = Leg(
            id = "$ID:L0",
            mode = mode,
            product = product,
            line = r.line,
            number = r.number,
            direction = r.direction,
            stops = r.stops.mapIndexed { j, s ->
                Stop(station = s.station, arr = s.arr, dep = s.dep, platform = s.platform, occupancy = rideOccupancy(seed + j, hour))
            },
            attrs = if (mode == Mode.TRAIN) listOf(Attr.NF, Attr.VO) else listOf(Attr.NF),
            occupancy = occ,
            disruptions = listOfNotNull(incident),
            formation = product?.let { formationFor(it, seed, hour) },
        )
        return Connection(
            id = ID,
            from = r.stops.first().station,
            to = r.stops.last().station,
            dep = dep,
            arr = r.stops.last().arr!!,
            legs = listOf(leg),
        )
    }
}

/** True for the disruption an open incident puts on the trip (the trip card's red banner). */
val Disruption.isIncident: Boolean get() = id.startsWith(DemoTrip.INCIDENT_PREFIX)
