package railstatus.mobile.ui

import railstatus.mobile.ui.chrome.TabBarMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Kotlin port must give the same numbers as the web app's tabbar-motion.ts (reference values computed with node). */
class TabBarMotionTest {
    private fun near(expected: Double, actual: Float, tol: Double = 1e-3) =
        assertTrue(abs(expected - actual) < tol, "expected $expected, got $actual")

    @Test fun switchDuration() = near(259.03, TabBarMotion.SWITCH_MS)

    @Test fun restNotch() {
        listOf(0 to 55.25, 10 to 53.369, 20 to 46.738, 28 to 24.273, 35 to 8.738, 45 to 2.0, 53 to 0.0, 60 to 0.0)
            .forEach { (v, d) -> near(d, TabBarMotion.restDepth(v.toFloat())) }
    }

    @Test fun closingAndOpening() {
        near(35.455155746509135, TabBarMotion.closingDepth(50f, 0f))
        near(14.725728155339807, TabBarMotion.closingDepth(100f, 20f))
        near(17.75, TabBarMotion.closingDepth(8.45f, 30f))
        near(0.0, TabBarMotion.closingDepth(200f, 50f))
        near(19.385875402792696, TabBarMotion.openingDepth(50f, 0f))
        near(27.79048786407767, TabBarMotion.openingDepth(100f, 20f))
        near(4.0648456973293765, TabBarMotion.openingDepth(150f, 40f))
    }

    @Test fun neighbours() {
        near(41.75962732919255, TabBarMotion.neighbourDepth(30f, 10f, 64.762f))
        near(25.845919610231423, TabBarMotion.neighbourDepth(120f, 32f, 64.762f))
        near(30.803453947368425, TabBarMotion.neighbourDepth(60f, -10f, 64.5f))
        near(28.885363636363635, TabBarMotion.neighbourDepth(90f, 80f, 64.5f))
        near(55.25, TabBarMotion.neighbourDepth(250f, 64f, 64.5f))
    }

    @Test fun profileSamplesEveryHalfDp() {
        val w = 1080f / 2.625f
        val p = TabBarMotion.profile(TabBarMotion.State.At(0), w)
        assertEquals((w / 0.5f).toInt() + 1, p.size)
        // Deepest under the first tab's centre (44.3 dp from the edge on the phone).
        val cx = TabBarMotion.tabX(w, 0)
        near(44.3, cx, 0.1)
        near(55.25, p[(cx / 0.5f).toInt()], 0.6)
        // Flat far from it.
        assertEquals(0f, p[p.size - 1])
    }
}
