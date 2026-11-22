package railstatus.mobile.ui.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClosureOverlayTest {
    @Test
    fun theClosedSectionRunsBetweenItsTwoStations() {
        val c = assertNotNull(closureOf("Renens VD", "Allaman")) // no rail network: a straight line
        assertEquals(4, c.xy.size)
        assertEquals(Merc.lx(6.5785), c.xy[0])
        assertEquals(Merc.ly(46.4715), c.xy[3])
    }

    @Test
    fun aStationTheAppDoesNotKnowDrawsNothing() {
        assertNull(closureOf("Renens VD", "Nowhere"))
    }
}
