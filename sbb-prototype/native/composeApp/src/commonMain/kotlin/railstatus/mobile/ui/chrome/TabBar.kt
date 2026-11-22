package railstatus.mobile.ui.chrome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.nav.Tab
import railstatus.mobile.ui.chrome.TabBarMotion.BUBBLE_R
import railstatus.mobile.ui.chrome.TabBarMotion.CENTER_Y
import railstatus.mobile.ui.chrome.TabBarMotion.OVERHANG
import railstatus.mobile.ui.chrome.TabBarMotion.STEP
import railstatus.mobile.ui.chrome.TabBarMotion.State
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbIcons
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.SvgRenderer
import railstatus.mobile.ui.theme.SbbDimens
import railstatus.mobile.ui.theme.SbbTheme
import kotlin.math.max
import kotlin.math.min

/**
 * SBB Mobile's "fancy tabs", measured on a Pixel 10a (dp): six equal slots with the outer centres 44.3 dp from the
 * screen edges, a 12 dp see-through overhang above the bar, and the active tab's 44.2 dp bubble sitting 5.7 dp below
 * the bar edge in a U-shaped notch. The bubbles never move: switching tabs moves only the bar's edge, which covers
 * the old bubble from below and uncovers the new one. The edge's shape over time is the phone's own, frame by frame
 * ([TabBarMotion]). Only the active tab has a label (14 sp, its box top 70.3 dp below the view's top).
 *
 * The view is [SbbDimens.TabBar] tall plus the gesture-nav inset, which the bar fills. Screens run on [SbbDimens.Under]
 * below its top so they show through the notch.
 *
 * @param badges tabs with the red tick badge (e.g. Tickets during an EasyRide journey)
 * @param on tabs whose glyph is in its "on" state (EasyRide's switch while a journey runs)
 */
@Composable
fun TabBar(
    active: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
    badges: Set<Tab> = emptySet(),
    on: Set<Tab> = emptySet(),
) {
    val tabs = Tab.entries
    val activeIndex = tabs.indexOf(active)
    val anim = remember { EdgeAnimator(activeIndex) }
    val navInset = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }

    LaunchedEffect(activeIndex) { anim.switchTo(activeIndex) }

    val c = SbbTheme.colors
    val light = !c.isDark
    BoxWithConstraints(modifier.fillMaxWidth().height(SbbDimens.TabBar + navInset)) {
        val density = LocalDensity.current
        val wDp = constraints.maxWidth / density.density
        anim.widthDp = wDp
        val hPx = constraints.maxHeight.toFloat()
        val state = anim.state
        val barPath = remember(state, wDp, hPx) { anim.barPath(state, wDp, hPx, density.density) }
        val cx = FloatArray(tabs.size) { TabBarMotion.tabX(wDp, it, tabs.size) }

        // 1. Bubbles (never moving), each with its inverted icon. The bar is drawn over them.
        tabs.forEachIndexed { i, tab ->
            Box(
                Modifier
                    .offset((cx[i] - BUBBLE_R).dp, (CENTER_Y - BUBBLE_R).dp)
                    .size((BUBBLE_R * 2).dp)
                    .then(if (light) Modifier.dropShadow(CircleShape, BubbleShadow) else Modifier)
                    .clip(CircleShape)
                    .background(c.tabOnBg),
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val g = glyph(tab, tab in on)
                    val box = 24.dp.toPx()
                    g?.draw(this, c.tabOnFg, Offset(size.width / 2 - box / 2, size.height / 2 - box / 2), Size(box, box))
                }
            }
        }
        // 2. The bar with its notch, and the plain icons living on it (clipped to it, so the open notch never shows
        //    a second copy).
        Box(
            Modifier
                .matchParentSize()
                .then(if (light) Modifier.dropShadow(PathShape(barPath), BarShadow) else Modifier),
        )
        Canvas(Modifier.matchParentSize()) {
            drawPath(barPath, c.tabBg)
            clipPath(barPath) {
                val box = 24.dp.toPx()
                tabs.forEachIndexed { i, tab ->
                    val x = cx[i].dp.toPx(); val y = CENTER_Y.dp.toPx()
                    glyph(tab, tab in on)?.draw(this, c.tabFg, Offset(x - box / 2, y - box / 2), Size(box, box))
                }
            }
        }
        // 3. Touch targets, the tick badges and the active label.
        tabs.forEachIndexed { i, tab ->
            val slotW = (wDp - 2 * TabBarMotion.TAB_PAD) / tabs.size
            val label = stringResource(tab.label)
            Box(
                Modifier
                    .offset((TabBarMotion.TAB_PAD + i * slotW).dp, 0.dp)
                    .size(slotW.dp, SbbDimens.TabBar)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(tab) }
                    .semantics { contentDescription = label; selected = tab == active },
            )
            if (tab in badges) {
                Box(
                    Modifier
                        .offset((cx[i] + 6f).dp, 20.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(c.red),
                    contentAlignment = Alignment.Center,
                ) { SbbIcon("circle-tick-small", size = 18.dp, tint = Color.White) }
            }
            if (tab == active) {
                CenteredAt(cx[i], 70.3f) { SbbText(label, style = SbbTheme.type.tabLabel, color = c.tabFg, maxLines = 1, softWrap = false) }
            }
        }
    }
}

/** Places [content] unconstrained with its top-centre at (x, y) dp. */
@Composable
private fun CenteredAt(x: Float, y: Float, content: @Composable () -> Unit) {
    Layout(content) { measurables, _ ->
        val p = measurables.first().measure(androidx.compose.ui.unit.Constraints())
        layout(0, 0) { p.place((x.dp.toPx() - p.width / 2f).toInt(), y.dp.roundToPx()) }
    }
}

/** Light mode, measured: the bar casts a soft shadow upwards (#F6F6F6 -> #E9E9E9 at the edge)... */
private val BarShadow = Shadow(radius = 10.dp, color = Color.Black, alpha = 0.1f)
/** ...and the bubble one into the notch (CSS drop-shadow(0 3px 3px rgba(0,0,0,.32))). */
private val BubbleShadow = Shadow(radius = 3.dp, color = Color.Black, alpha = 0.32f, offset = DpOffset(0.dp, 3.dp))

private class PathShape(val path: Path) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) = Outline.Generic(path)
}

/** The edge's motion state, driven frame by frame. Mirrors the web TabBar's refs (motion, shown, offset). */
private class EdgeAnimator(initial: Int) {
    var state: State by mutableStateOf(State.At(initial))
    var widthDp = 411.43f
    /** What is on screen now, and (when a switch interrupted another) how far that is from the motion's start. */
    private var shown: FloatArray? = null
    private var offset: FloatArray? = null

    suspend fun switchTo(to: Int) {
        val m = state
        val from = when (m) { is State.At -> m.index; is State.Switch -> m.to }
        if (from == to) {
            // A switch to this tab was cut off before it finished (another tap came and went before its own effect
            // ran: A→B, then C and back to B within a frame). Run it out from where it stopped; returning here left the
            // edge frozen mid-way, a stray notch with the neighbour's white bubble showing beside the active one.
            if (m is State.Switch) {
                val t0 = m.t
                animateFrames(TabBarMotion.SWITCH_MS - t0) { dt ->
                    val t = t0 + dt
                    if (t >= TabBarMotion.SWITCH_MS) { offset = null; state = State.At(to) } else state = State.Switch(m.from, to, t)
                }
            }
            return
        }
        // Interrupted mid-switch: remember how the screen differs from where this switch starts.
        val now = shown
        val start = TabBarMotion.profile(State.Switch(from, to, 0f), widthDp)
        offset = if (m is State.Switch && now != null && now.size == start.size) FloatArray(start.size) { now[it] - start[it] } else null
        state = State.Switch(from, to, 0f)
        animateFrames(TabBarMotion.SWITCH_MS, firstFrameMs = TabBarMotion.FIRST_FRAME_MS) { t ->
            if (t >= TabBarMotion.SWITCH_MS) { offset = null; state = State.At(to) } else state = State.Switch(from, to, t)
        }
    }

    /** The bar's outline in px: flat bottom, the measured edge on top. */
    fun barPath(m: State, w: Float, hPx: Float, density: Float): Path {
        val d = TabBarMotion.profile(m, w)
        val off = offset
        if (off != null && m is State.Switch && off.size == d.size) {
            // Fade the interrupted shape into the measured motion as the old notch drains.
            val left = TabBarMotion.closingDepth(m.t, 0f) / TabBarMotion.closingDepth(0f, 0f)
            for (k in d.indices) d[k] = max(0f, d[k] + off[k] * left)
        }
        shown = d
        val p = Path()
        p.moveTo(0f, hPx)
        for (k in d.indices) p.lineTo(min(k * STEP, w) * density, (OVERHANG + d[k]) * density)
        p.lineTo(w * density, hPx)
        p.close()
        return p
    }
}

// Tab icons in a 24 dp box: SBB's open icons where one exists; EasyRide, shop, tickets and profile are drawn to
// match the app (ported from the web's TabGlyph).
private const val LINE = """fill="none" stroke="currentColor" stroke-width="1" stroke-linejoin="round""""

private val easyRideOff by lazy {
    SvgRenderer.parse("""<svg viewBox="0 0 24 24"><rect x="2.5" y="7" width="19" height="10" rx="5" $LINE/><circle cx="7.7" cy="12" r="2.9" $LINE/></svg>""")
}
private val easyRideOn by lazy {
    SvgRenderer.parse("""<svg viewBox="0 0 24 24"><rect x="2.5" y="7" width="19" height="10" rx="5" $LINE/><circle cx="16.3" cy="12" r="2.9" fill="#EB0000"/></svg>""")
}
private val shopGlyph by lazy {
    SvgRenderer.parse(
        """<svg viewBox="0 0 24 24">""" +
            """<path d="M4.6 3.7h14.8l2.1 4.6v.9c0 .9-.6 1.4-1.2 1.4s-1.2-.5-1.2-1.4c0 .9-.6 1.4-1.2 1.4s-1.2-.5-1.2-1.4c0 .9-.6 1.4-1.2 1.4s-1.2-.5-1.2-1.4c0 .9-.6 1.4-1.2 1.4s-1.2-.5-1.2-1.4c0 .9-.6 1.4-1.2 1.4s-1.2-.5-1.2-1.4c0 .9-.6 1.4-1.2 1.4S4.3 10.1 4.3 9.2c0 .9-.6 1.4-1.2 1.4S2.5 10.1 2.5 9.2v-.9z" $LINE/>""" +
            """<path d="M3.9 10.4v8.9c0 .7.5 1.2 1.2 1.2h13.8c.7 0 1.2-.5 1.2-1.2v-8.9" $LINE/>""" +
            """<path fill="currentColor" fill-rule="evenodd" transform="translate(12 15.9) scale(.8) translate(-12 -12)" d="$SIGNET_PATH"/>""" +
            "</svg>",
    )
}
private val ticketsGlyph by lazy {
    SvgRenderer.parse(
        """<svg viewBox="0 0 24 24"><path fill="currentColor" fill-rule="evenodd" d="M3 3h7v7H3zm1.4 1.4v4.2h4.2V4.4zM5.5 5.5h2v2h-2zM14 3h7v7h-7zm1.4 1.4v4.2h4.2V4.4zm1.1 1.1h2v2h-2zM3 14h7v7H3zm1.4 1.4v4.2h4.2v-4.2zm1.1 1.1h2v2h-2zM11.3 3h1.4v3h-1.4zm0 4.3h1.4v4h-1.4zM3 11.3h3v1.4H3zm4.3 0h3v1.4h-3zm4 0h3.4v1.4h-2v1.3h-1.4zm4.7 0h1.4v2.7H16zm2.7 0H21v1.4h-2.3zM14 14h1.4v1.3h2.6v1.4h-4zm5.3 0H21v4h-1.4v-2.6h-.3zM11.3 15.4h1.4v2.6H14v1.4h-2.7zm4 2.6h2.7v1.4h-1.3V21h-1.4zm4.3 1.6H21V21h-1.4zM11.3 19.6h1.4V21h-1.4z"/></svg>""",
    )
}
private val profileGlyph by lazy {
    SvgRenderer.parse(
        """<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5" $LINE/><circle cx="12" cy="9.4" r="2.9" $LINE/>""" +
            """<path d="M7.4 18.4v-.9a3 3 0 0 1 3-3h3.2a3 3 0 0 1 3 3v.9" $LINE/></svg>""",
    )
}

private fun glyph(tab: Tab, on: Boolean): SvgRenderer? = when (tab) {
    Tab.Plan -> SbbIcons.get("timetable-small")
    Tab.Trips -> SbbIcons.get("train-profile-small")
    Tab.EasyRide -> if (on) easyRideOn else easyRideOff
    Tab.Tickets -> ticketsGlyph
    Tab.Shop -> shopGlyph
    Tab.Profile -> profileGlyph
}
