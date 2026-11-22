package railstatus.mobile.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import railstatus.mobile.screens.plan.TouchGestureTracker
import railstatus.mobile.screens.plan.TouchOutcome
import railstatus.mobile.screens.plan.TouchStep
import railstatus.mobile.screens.plan.TouchTarget
import railstatus.mobile.screens.plan.TouchTarget.CurrentLocation
import railstatus.mobile.screens.plan.TouchTarget.FromCell
import railstatus.mobile.screens.plan.TouchTarget.Station
import railstatus.mobile.screens.plan.TouchTarget.ToCell
import railstatus.mobile.data.AppStore
import railstatus.mobile.data.InMemoryKeyValueStore
import railstatus.mobile.screens.plan.TouchNext
import railstatus.mobile.screens.plan.applyTouchRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Touch timetable's gesture logic on a 2-column board like the real one: tiles 200 x 80 px, 2 px apart,
 * then the Current location | from | to row.
 */
class TouchGesturesTest {
    private val w = 200f; private val h = 80f; private val g = 2f
    private val ids = listOf("bern", "lausanne", "olten", "geneve", "renens", "fribourg")

    private fun cell(col: Int, row: Int) = Rect(col * (w + g), row * (h + g), col * (w + g) + w, row * (h + g) + h)

    private val board: Map<TouchTarget, Rect> = buildMap {
        ids.forEachIndexed { i, id -> put(Station(id), cell(i % 2, i / 2)) }
        val y = 3 * (h + g)
        put(CurrentLocation, Rect(0f, y, w, y + h))
        put(FromCell, Rect(w + g, y, w + g + (w - g) / 2, y + h))
        put(ToCell, Rect(w + g + (w - g) / 2 + g, y, 2 * w + g, y + h))
    }

    private fun tracker(allowVia: Boolean = true) = TouchGestureTracker(slop = 10f, allowVia = allowVia).also { it.boundsProvider = { board } }

    private fun centre(t: TouchTarget) = board.getValue(t).center
    private fun st(id: String) = Station(id)

    /** Down on [from], move in [steps] steps to each point of [through] in turn, up at the last one. */
    private fun TouchGestureTracker.drag(from: TouchTarget, through: List<Offset>, steps: Int = 8): TouchOutcome {
        var p = centre(from)
        assertTrue(down(p))
        for (q in through) {
            for (i in 1..steps) move(p + (q - p) * (i / steps.toFloat()))
            p = q
        }
        return up(p)
    }

    @Test
    fun hitTestsTilesNotGaps() {
        val t = tracker(); t.down(centre(st("bern")))
        assertEquals(st("bern"), t.hit(Offset(5f, 5f)))
        assertEquals(st("lausanne"), t.hit(Offset(w + g + 1f, 1f)))
        assertNull(t.hit(Offset(w + 1f, 10f))) // the 2 px gap
        assertEquals(st("olten"), t.hit(Offset(10f, h + g + 10f)))
        assertEquals(ToCell, t.hit(Offset(2 * w, 3 * (h + g) + 10f)))
        assertNull(t.hit(Offset(10f, 10f), inset = 0.2f)) // inside the tile, but not past the inset
    }

    @Test
    fun downOutsideIsIgnored() {
        val t = tracker()
        assertFalse(t.down(Offset(w + 1f, 10f)))
        assertEquals(TouchStep.NONE, t.move(Offset(300f, 300f)))
        assertEquals(TouchOutcome.Cancelled, t.up())
    }

    @Test
    fun tapIsATapNotARoute() {
        val t = tracker()
        t.down(centre(st("bern")))
        assertEquals(TouchStep.NONE, t.move(centre(st("bern")) + Offset(4f, 3f))) // under the slop
        assertFalse(t.dragging)
        assertEquals(TouchOutcome.Tap(st("bern")), t.up())
    }

    @Test
    fun dragAToBSearchesAToB() {
        val t = tracker()
        t.down(centre(st("bern")))
        assertEquals(TouchStep.DRAG_STARTED, t.move(centre(st("bern")) + Offset(20f, 0f)))
        assertTrue(t.dragging)
        assertEquals(TouchStep.ENTERED, t.move(centre(st("lausanne"))))
        assertEquals(listOf(st("bern"), st("lausanne")), t.path.toList())
        assertEquals(TouchOutcome.Route(st("bern"), st("lausanne")), t.up())
        assertTrue(t.path.isEmpty())
    }

    @Test
    fun cuttingACornerDoesNotPickUpTheNeighbour() {
        // Bern (top left) to Geneve (row 2, right): the straight line crosses the corners of Lausanne and Olten.
        val out = tracker().drag(st("bern"), listOf(centre(st("geneve"))), steps = 40)
        assertEquals(TouchOutcome.Route(st("bern"), st("geneve")), out)
    }

    @Test
    fun passingThroughATileMakesItAVia() {
        // Bern -> straight down over Olten to Renens -> across to Fribourg.
        val out = tracker().drag(st("bern"), listOf(centre(st("renens")), centre(st("fribourg"))))
        assertEquals(TouchOutcome.Route(st("bern"), st("fribourg"), via = listOf("olten", "renens")), out)
    }

    @Test
    fun withoutViasOnlyTheLastTileCounts() {
        val out = tracker(allowVia = false).drag(st("bern"), listOf(centre(st("renens")), centre(st("fribourg"))))
        assertEquals(TouchOutcome.Route(st("bern"), st("fribourg")), out)
    }

    @Test
    fun draggingBackUndoes() {
        val t = tracker()
        t.down(centre(st("bern")))
        t.move(centre(st("lausanne")))
        t.move(centre(st("geneve")))
        assertEquals(listOf(st("bern"), st("lausanne"), st("geneve")), t.path.toList())
        assertEquals(TouchStep.BACKED_OUT, t.move(centre(st("lausanne"))))
        assertEquals(listOf(st("bern"), st("lausanne")), t.path.toList())
        assertEquals(TouchStep.BACKED_OUT, t.move(centre(st("bern"))))
        assertEquals(listOf(st("bern")), t.path.toList())
        // Let go back on the start tile: nothing.
        assertEquals(TouchOutcome.Cancelled, t.up(centre(st("bern"))))
    }

    @Test
    fun releasingOutsideTheBoardCancels() {
        val t = tracker()
        t.down(centre(st("bern")))
        t.move(centre(st("lausanne")))
        assertEquals(TouchOutcome.Cancelled, t.up(Offset(2 * w + 80f, 40f)))
    }

    @Test
    fun releasingInAGapStillCounts() {
        val t = tracker()
        t.down(centre(st("bern")))
        t.move(centre(st("lausanne")))
        assertEquals(TouchOutcome.Route(st("bern"), st("lausanne")), t.up(Offset(w + 1f, h + 1f)))
    }

    @Test
    fun currentLocationAndTheFromToCells() {
        assertEquals(TouchOutcome.Route(CurrentLocation, st("fribourg")), tracker().drag(CurrentLocation, listOf(centre(st("fribourg")))))
        assertEquals(TouchOutcome.Route(st("fribourg"), ToCell), tracker().drag(st("fribourg"), listOf(centre(ToCell))))
        assertEquals(TouchOutcome.Route(st("fribourg"), FromCell), tracker().drag(st("fribourg"), listOf(centre(FromCell))))
        // A cell onto a cell names no station.
        assertEquals(TouchOutcome.Cancelled, tracker().drag(CurrentLocation, listOf(centre(ToCell))))
    }

    @Test
    fun aCellIsAnEndNeverAVia() {
        // Over the "to" cell and on to Fribourg: the cell drops out.
        val t = tracker()
        t.down(centre(st("renens")))
        t.move(centre(ToCell))
        t.move(centre(st("fribourg")))
        assertEquals(listOf(st("renens"), st("fribourg")), t.path.toList())
    }

    @Test
    fun longPressOnlyWhenStill() {
        val t = tracker()
        t.down(centre(st("olten")))
        assertEquals(TouchOutcome.LongPress(st("olten")), t.longPress())
        t.down(centre(st("olten")))
        t.move(centre(st("olten")) + Offset(30f, 0f))
        assertNull(t.longPress())
    }

    @Test
    fun routesApplyToTheStore() {
        val app = AppStore(InMemoryKeyValueStore())
        assertEquals(TouchNext.CONNECTIONS, app.applyTouchRoute(TouchOutcome.Route(st("bern"), st("olten"))))
        assertEquals("bern" to "olten", app.value.fromId to app.value.toId)
        assertEquals("olten", app.value.recent.first())
        assertEquals(TouchNext.CONNECTIONS, app.applyTouchRoute(TouchOutcome.Route(CurrentLocation, st("geneve")), hereId = "epfl"))
        assertEquals("epfl" to "geneve", app.value.fromId to app.value.toId)
        // Onto the "to" cell: the tile becomes To; From is set, so straight to the connections.
        assertEquals(TouchNext.CONNECTIONS, app.applyTouchRoute(TouchOutcome.Route(st("luzern"), ToCell)))
        assertEquals("epfl" to "luzern", app.value.fromId to app.value.toId)
        // From the "from" cell onto a tile: that tile is To, then pick From.
        assertEquals(TouchNext.SEARCH_FROM, app.applyTouchRoute(TouchOutcome.Route(FromCell, st("basel"))))
        assertEquals("basel", app.value.toId)
    }
}
