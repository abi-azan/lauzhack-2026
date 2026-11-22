package railstatus.server

import railstatus.model.Station
import railstatus.model.Stop
import railstatus.model.Train
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TrainClockTest {
    private val bern = Station("8507000", "Bern")
    private val thun = Station("8507100", "Thun")
    private val train = Train(
        "S1-15121", "S", "S 1", "15121", "Thun",
        listOf(Stop(bern, null, "06:47", "10"), Stop(thun, "07:18", null, "5")),
        cars = 4,
    )
    private fun at(h: Int, m: Int) = OffsetDateTime.of(2026, 11, 22, h, m, 30, 0, now().offset)

    @Test
    fun `shifts every time so the anchor lands on now`() {
        val t = TrainClock.shift(listOf(train), "06:52", at(10, 0)).single()
        assertEquals("09:55", t.stops[0].dep)
        assertEquals("10:26", t.stops[1].arr)
        assertEquals(null, t.stops[0].arr)
        assertEquals("10", t.stops[0].platform)
    }

    @Test
    fun `wraps around midnight`() {
        val t = TrainClock.shift(listOf(train), "06:52", at(23, 50)).single()
        assertEquals("23:45", t.stops[0].dep)
        assertEquals("00:16", t.stops[1].arr)
    }

    @Test
    fun `no anchor or a bad anchor keeps the timetable`() {
        val list = listOf(train)
        assertSame(list, TrainClock.shift(list, null, at(10, 0)))
        assertSame(list, TrainClock.shift(list, "soon", at(10, 0)))
        assertSame(list, TrainClock.shift(list, "25:00", at(10, 0)))
    }

    @Test
    fun `the real 1 Sep trains file loads`() {
        val file = listOf(File("data/real-trains-2026-09-01.json"), File("../data/real-trains-2026-09-01.json")).first { it.exists() }
        val trains = loadTrains(file)
        assertEquals(65, trains.size)
        val s1 = trains.single { it.id == "S1-15121" }
        assertTrue(s1.stops.any { it.station.id == "8507002" }, "S1 halts at Ostermundigen")
        assertTrue(trains.single { it.id == "S11-15022" }.cancelled, "S11-15022 was cancelled")
    }
}
