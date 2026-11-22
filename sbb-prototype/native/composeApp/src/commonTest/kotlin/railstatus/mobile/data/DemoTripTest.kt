@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.data

import kotlinx.datetime.TimeZone
import railstatus.mobile.incident.StopView
import railstatus.mobile.incident.TrainView
import railstatus.model.Station as ApiStation
import railstatus.model.Stop as ApiStop
import railstatus.model.Train as ApiTrain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The saved trip is the checked-in train, cut from where the passenger boards to where they get off. */
class DemoTripTest {
    private val zurich = TimeZone.of("Europe/Zurich")

    /** 2026-11-21 19:35 in Zurich. */
    private val now = Instant.fromEpochMilliseconds(1795286100000)

    private fun stop(id: String, name: String, arr: String?, dep: String?, pl: String) =
        ApiStop(ApiStation(id, name), arr = arr, dep = dep, platform = pl)

    private val train = ApiTrain(
        id = "RE33-4711", product = "RE", line = "RE 33", number = "4711", direction = "Bern",
        stops = listOf(
            stop("1", "Lausanne", null, "20:05", "6"),
            stop("2", "Somewhere Small", "20:15", "20:16", "1"),
            stop("3", "Fribourg/Freiburg", "20:50", "20:52", "2"),
            stop("4", "Bern", "21:15", null, "7"),
        ),
    )

    @AfterTest fun tearDown() = DemoTrip.setLive(null)

    @Test fun wholeTrainWithoutBoardingOrDestination() {
        val r = assertNotNull(DemoTrip.fromTrain(train, now = now, zone = zurich))
        assertEquals(listOf("Lausanne", "Somewhere Small", "Fribourg/Freiburg", "Bern"), r.stops.map { it.station.name })
        assertNull(r.stops.first().arr)
        assertNull(r.stops.last().dep)
        assertEquals(listOf("6", "1", "2", "7"), r.stops.map { it.platform })
        assertEquals("33", r.line)
        assertEquals("20:05", hhmm(r.stops.first().dep!!, zurich))
        // An unknown station keeps the backend's id and sits between its known neighbours.
        val small = r.stops[1].station
        assertEquals("2", small.id)
        val (a, b) = station("lausanne") to station("fribourg")
        assertTrue(small.lat in minOf(a.lat, b.lat)..maxOf(a.lat, b.lat))
    }

    @Test fun cutFromBoardingToDestination() {
        val byId = assertNotNull(DemoTrip.fromTrain(train, boardingId = "2", destName = "Fribourg/Freiburg", now = now, zone = zurich))
        assertEquals(listOf("2", "fribourg"), byId.stops.map { it.station.id })
        val byName = assertNotNull(DemoTrip.fromTrain(train, boardingName = "Fribourg/Freiburg", now = now, zone = zurich))
        assertEquals(listOf("fribourg", "bern"), byName.stops.map { it.station.id })
        // A destination before the boarding stop doesn't count: ride to the end.
        val back = assertNotNull(DemoTrip.fromTrain(train, boardingName = "Fribourg/Freiburg", destName = "Lausanne", now = now, zone = zurich))
        assertEquals("bern", back.stops.last().station.id)
    }

    @Test fun runsPastMidnight() {
        val late = train.copy(stops = listOf(stop("1", "Lausanne", null, "23:40", "6"), stop("4", "Bern", "00:45", null, "7")))
        val r = assertNotNull(DemoTrip.fromTrain(late, now = now, zone = zurich))
        assertTrue(r.stops.last().arr!! > r.stops.first().dep!!)
    }

    @Test fun noCheckInNoTrip() {
        DemoTrip.setLive(null)
        assertNull(DemoTrip.connection(zurich))
        assertNull(getConnection(DemoTrip.ID, zurich))
        assertEquals(emptyList(), savedTrips(listOf(DemoTrip.ID), now).map { it.id })
    }

    @Test fun checkedInTripIsASavedConnection() {
        DemoTrip.setLive(DemoTrip.fromTrain(train, destName = "Fribourg/Freiburg", now = now, zone = zurich))
        val c = assertNotNull(getConnection(DemoTrip.ID, zurich))
        assertEquals("Lausanne" to "Fribourg/Freiburg", c.from.name to c.to.name)
        assertEquals(Product.RE, c.legs.single().product)
        assertEquals("4711", c.legs.single().number)
        assertEquals(listOf(DemoTrip.ID), savedTrips(listOf(DemoTrip.ID), now).map { it.id })
    }

    @Test fun offlineScriptedTrain() {
        val t0 = now
        val view = TrainView(
            id = "X-1", product = "IR", line = "90", number = "1", direction = "Bern",
            stops = listOf(
                StopView("Lausanne", planned = t0),
                StopView("Fribourg/Freiburg", planned = addMin(t0, 40)),
                StopView("Bern", planned = addMin(t0, 65)),
            ),
        )
        val r = assertNotNull(DemoTrip.fromPlanned(view, destName = "Fribourg/Freiburg"))
        assertEquals(listOf("lausanne", "fribourg"), r.stops.map { it.station.id })
        assertEquals(addMin(t0, 40), r.stops.last().arr)
    }
}
