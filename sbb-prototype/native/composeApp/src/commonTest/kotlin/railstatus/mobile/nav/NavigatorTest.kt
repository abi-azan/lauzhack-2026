package railstatus.mobile.nav

import androidx.compose.runtime.Composable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigatorTest {
    private data class D(val name: String) : Destination {
        @Composable override fun Content() {}
    }

    private fun nav() = Navigator { D(it.name) }

    @Test fun pushAndBackSlide() {
        val n = nav()
        n.push(D("a"))
        assertEquals(D("a"), n.current.destination)
        assertEquals(1, n.slide?.dir)
        assertTrue(n.back())
        assertEquals(-1, n.slide?.dir)
        assertEquals(D("Plan"), n.current.destination)
        assertFalse(n.back())
    }

    @Test fun tabsKeepTheirStacks() {
        val n = nav()
        n.push(D("a"))
        n.selectTab(Tab.Profile)
        assertEquals(D("Profile"), n.current.destination)
        n.selectTab(Tab.Plan)
        assertEquals(D("a"), n.current.destination)
        // Tapping the selected tab goes back to its root, without a slide.
        n.selectTab(Tab.Plan)
        assertEquals(1, n.stack.size)
        assertNull(n.slide)
    }

    @Test fun replaceAndOpen() {
        val n = nav()
        n.push(D("a")); n.replace(D("b"))
        assertEquals(listOf(D("Plan"), D("b")), n.stack.map { it.destination })
        n.open(Tab.EasyRide, D("x"), D("y"))
        assertEquals(Tab.EasyRide, n.tab)
        assertEquals(listOf(D("EasyRide"), D("x"), D("y")), n.stack.map { it.destination })
        assertNull(n.slide)
    }

    @Test fun removedEntriesAreReported() {
        val n = nav()
        val removed = mutableListOf<NavEntry>()
        n.onRemoved = { removed += it }
        n.push(D("a")); n.back()
        assertEquals(listOf(D("a")), removed.map { it.destination })
    }
}
