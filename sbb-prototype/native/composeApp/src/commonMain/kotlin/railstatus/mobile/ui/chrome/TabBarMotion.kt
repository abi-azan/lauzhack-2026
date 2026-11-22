package railstatus.mobile.ui.chrome

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The tab bar's edge, measured on SBB Mobile (Pixel 10a, real speed, 120 fps screen recording). A line-for-line
 * port of the web app's tabbar-motion.ts; the measured table is in [TabBarMotionData].
 *
 * The bubbles never move; only the bar's top edge does. Its depth below the flat edge was read off every frame of a
 * switch between neighbouring tabs (Plan -> Trips), across the whole bar. Everything else derives from that one
 * measurement, because the phone shows the same pieces in every case:
 *   - neighbours: the old notch drains into the new one through a neck between them (the table itself);
 *   - two or more tabs apart: no bridge; the old notch closes in place (the table's outer side of the old tab)
 *     while the new one opens in place (its outer side of the new tab);
 *   - at rest: the table's last frame around the new tab.
 */
object TabBarMotion {
    private val T = TabBarMotionData.T
    private const val U0 = TabBarMotionData.U0
    private const val DU = TabBarMotionData.DU
    private const val COLS = TabBarMotionData.COLS

    /** Distance between neighbouring tab centres on the phone (dp). */
    const val SPACING = TabBarMotionData.SPACING

    @OptIn(ExperimentalEncodingApi::class)
    private val table: FloatArray by lazy {
        val bytes = Base64.decode(TabBarMotionData.DATA)
        FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 4f }
    }

    /** How long a switch takes (ms). */
    val SWITCH_MS: Float = T[T.size - 1]

    /** Depth (dp) at time t (ms) and table position u (dp from the old centre, positive towards the new one). */
    fun sample(t: Float, u: Float): Float {
        val f = (u - U0) / DU
        if (f < 0f || f > COLS - 1) return 0f
        val j = min(COLS - 2, floor(f).toInt())
        val w = f - j
        val a: Int; val b: Int; val k: Float
        when {
            t <= 0f -> { a = 0; b = 0; k = 0f }
            t >= SWITCH_MS -> { a = T.size - 1; b = T.size - 1; k = 0f }
            else -> {
                var i = 1
                while (T[i] < t) i++
                a = i - 1; b = i; k = (t - T[i - 1]) / (T[i] - T[i - 1])
            }
        }
        val tab = table
        val atA = tab[a * COLS + j] * (1 - w) + tab[a * COLS + j + 1] * w
        val atB = tab[b * COLS + j] * (1 - w) + tab[b * COLS + j + 1] * w
        return atA * (1 - k) + atB * k
    }

    /** The table's old-side columns end at the phone's screen edge, 44 dp out. */
    private val TAIL = -U0

    /** The notch at rest, v dp from the active tab's centre. */
    fun restDepth(v: Float): Float = sample(SWITCH_MS, SPACING + abs(v))

    /** The closing notch of a far switch, v dp from the old tab's centre. */
    fun closingDepth(t: Float, v: Float): Float {
        val a = abs(v)
        if (a <= TAIL) return sample(t, -a)
        // Beyond the measured columns (only ever < 2.5 dp): the rest notch's tail, scaled to match.
        val r = restDepth(TAIL)
        return if (r > 0f) sample(t, -TAIL) * restDepth(a) / r else 0f
    }

    /** The opening notch of a far switch, v dp from the new tab's centre. */
    fun openingDepth(t: Float, v: Float): Float = sample(t, SPACING + abs(v))

    /** A switch between neighbours: u in dp from the old centre towards the new one; spacing = our centre distance. */
    fun neighbourDepth(t: Float, u: Float, spacing: Float): Float = when {
        u < 0f -> closingDepth(t, u)
        u > spacing -> openingDepth(t, u - spacing)
        else -> sample(t, u * SPACING / spacing)
    }

    // ---- the bar's edge profile (the web's TabBar in chrome.tsx) ----------------------------------------------

    /** Edge sampling step (dp). */
    const val STEP = 0.5f
    /** Side padding of the six slots (dp). */
    const val TAB_PAD = 12f
    /** See-through overhang above the flat bar edge (dp). */
    const val OVERHANG = 12f
    /** Active bubble radius (dp): a 44.2 dp disc. */
    const val BUBBLE_R = 22.1f
    /** Bubble/icon centre below the view's top (dp): 5.7 dp below the flat edge to the bubble's top. */
    const val CENTER_Y = OVERHANG + 27.6f
    /** The phone's first frame after the tap already shows ~8 ms of motion (120 Hz). */
    const val FIRST_FRAME_MS = 8.45f

    /** Centre (dp) of tab [i] of [count] in a bar [w] dp wide. */
    fun tabX(w: Float, i: Int, count: Int = 6): Float = TAB_PAD + (i + 0.5f) * (w - 2 * TAB_PAD) / count

    /** Where the edge is heading: at rest under a tab, or [t] ms into a switch. */
    sealed interface State {
        data class At(val index: Int) : State
        data class Switch(val from: Int, val to: Int, val t: Float) : State
    }

    /** Depth below the flat edge (dp) at x, for a state. */
    fun depthAt(m: State, x: Float, cx: FloatArray): Float = when (m) {
        is State.At -> restDepth(x - cx[m.index])
        is State.Switch -> if (abs(m.from - m.to) == 1) {
            val dir = if (m.to > m.from) 1f else -1f
            neighbourDepth(m.t, (x - cx[m.from]) * dir, abs(cx[m.to] - cx[m.from]))
        } else max(closingDepth(m.t, x - cx[m.from]), openingDepth(m.t, x - cx[m.to]))
    }

    /** The edge's depth every [STEP] dp across a bar [w] dp wide. */
    fun profile(m: State, w: Float, count: Int = 6): FloatArray {
        val cx = FloatArray(count) { tabX(w, it, count) }
        val n = floor(w / STEP).toInt() + 1
        return FloatArray(n) { k -> depthAt(m, min(k * STEP, w), cx) }
    }
}
