@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.ui.map

import railstatus.mobile.data.findConnections
import railstatus.mobile.data.station
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class RouteGeoTest {
    private val at = Instant.parse("2026-11-20T08:00:00Z")
    private val conn = findConnections(station("geneve"), station("bern"), at, 1).first()

    @Test
    fun stopsHaveEndsAtBothEnds() {
        val r = buildRoute(conn, null)
        assertEquals(StopRole.END, r.stops.first().role)
        assertEquals(StopRole.END, r.stops.last().role)
        assertEquals(conn.legs.size, r.legs.size)
    }

    @Test
    fun trainOnlyWhileUnderWayAndOnTheLine() {
        val r = buildRoute(conn, null)
        assertNull(r.trainAt(conn, conn.dep - 1.minutes))
        val mid = Instant.fromEpochMilliseconds((conn.dep.toEpochMilliseconds() + conn.arr.toEpochMilliseconds()) / 2)
        val t = assertNotNull(r.trainAt(conn, mid))
        val xs = r.stops.map { it.x }
        assertTrue(t.x in xs.min()..xs.max(), "train between the end stops")
    }

    @Test
    fun fitFramesTheBox() {
        val pts = floatArrayOf(Merc.lx(6.14), Merc.ly(46.21), Merc.lx(7.44), Merc.ly(46.95))
        val c = assertNotNull(fitCamera(pts, 1000f, 2000f, FrameInsets(0f, 0f, 0f, 0f), 2f))
        assertTrue(c.zoom in 7.0..10.0, "zoom ${c.zoom}")
        assertEquals((Merc.x(6.14) + Merc.x(7.44)) / 2, c.cx, 1e-6)
    }
}
