@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.data

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Values the screens derive from a connection, ported from `mobile/src/data/derive.ts`.
// A `!` in the TS (a stop time that must be there) is a `!!` here.

/** Where the traveller boards this leg: `first(leg)`. */
val Leg.firstStop: Stop get() = stops.first()

/** Where the traveller alights: `last(leg)`. */
val Leg.lastStop: Stop get() = stops.last()

/** `durationMin(c)` */
val Connection.durationMin: Int get() = minutesBetween(dep, arr)

/** Positions (0..1) of the change dots on the pearl chain. */
fun Connection.changePositions(): List<Double> {
    val total = (arr.toEpochMilliseconds() - dep.toEpochMilliseconds()).toDouble()
    return legs.dropLast(1).map { (it.lastStop.arr!!.toEpochMilliseconds() - dep.toEpochMilliseconds()) / total }
}

/** 0..1 (exclusive) while the trip is under way, otherwise null. */
fun Connection.progress(now: Instant): Double? {
    val p = (now.toEpochMilliseconds() - dep.toEpochMilliseconds()).toDouble() / (arr.toEpochMilliseconds() - dep.toEpochMilliseconds()).toDouble()
    return if (p > 0 && p < 1) p else null
}

/** Every disruption on the connection, first occurrence of each id, in leg order. */
fun Connection.allDisruptions(): List<Disruption> = legs.flatMap { it.disruptions }.distinctBy { it.id }

/** The stop shown in cards (platform, delay): the boarding stop of the first leg. */
val Connection.boardingStop: Stop get() = legs[0].firstStop

enum class StopTime { ARR, DEP }

fun Stop.time(which: StopTime): Instant? = if (which == StopTime.ARR) arr else dep

data class Transfer(
    val at: Station,
    val arrive: Stop,
    val depart: Stop,
    val fromLeg: Leg,
    val toLeg: Leg,
    val minutes: Int,
)

fun Connection.transfers(): List<Transfer> = legs.dropLast(1).mapIndexed { i, l ->
    val next = legs[i + 1]
    Transfer(
        at = l.lastStop.station, arrive = l.lastStop, depart = next.firstStop, fromLeg = l, toLeg = next,
        minutes = minutesBetween(l.lastStop.arr!!, next.firstStop.dep!!),
    )
}

/**
 * Step cards for the travel companion's bottom sheet, in trip order: departure, one card per change, arrival.
 * Feature work adds a "next move" card here.
 */
sealed interface StepCard {
    data class Departure(val leg: Leg, val stop: Stop) : StepCard
    data class Change(val transfer: Transfer) : StepCard
    data class Arrival(val leg: Leg, val stop: Stop, val remainingMin: Int) : StepCard
}

fun Connection.stepCards(now: Instant): List<StepCard> {
    val cards = mutableListOf<StepCard>(StepCard.Departure(legs[0], legs[0].firstStop))
    transfers().forEach { cards += StepCard.Change(it) }
    val lastLeg = legs.last()
    val from = if (now > dep) now else dep
    cards += StepCard.Arrival(lastLeg, lastLeg.lastStop, maxOf(0, minutesBetween(from, arr)))
    return cards
}

/** Index of the step card that matters right now. */
fun Connection.currentStep(now: Instant): Int {
    if (now < dep) return 0
    val ts = transfers()
    val next = ts.indexOfFirst { now < it.depart.dep!! }
    return if (next == -1) ts.size + 1 else next + 1
}

/** Index of the leg the traveller is on (or about to board). */
fun Connection.currentLeg(now: Instant): Int {
    val i = legs.indexOfFirst { now < it.lastStop.arr!! }
    return if (i == -1) legs.size - 1 else i
}

/** A map position. The web passes `[lon, lat]` arrays. */
data class LonLat(val lon: Double, val lat: Double)

val Station.lonLat: LonLat get() = LonLat(lon, lat)

/** Interpolated train position along the stops of the current leg. */
fun Connection.trainPosition(now: Instant): LonLat {
    val s = legs[currentLeg(now)].stops
    if (now <= s[0].dep!!) return s[0].station.lonLat
    for (i in 0 until s.size - 1) {
        val a = s[i].dep ?: s[i].arr!!
        val b = s[i + 1].arr ?: s[i + 1].dep!!
        if (now <= b) {
            val span = maxOf(1L, b.toEpochMilliseconds() - a.toEpochMilliseconds()).toDouble()
            val f = minOf(1.0, maxOf(0.0, (now.toEpochMilliseconds() - a.toEpochMilliseconds()) / span))
            val p = s[i].station
            val q = s[i + 1].station
            return LonLat(p.lon + (q.lon - p.lon) * f, p.lat + (q.lat - p.lat) * f)
        }
    }
    return s.last().station.lonLat
}
