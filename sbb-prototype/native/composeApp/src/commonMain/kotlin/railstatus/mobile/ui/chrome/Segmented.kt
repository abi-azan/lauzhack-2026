package railstatus.mobile.ui.chrome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.theme.SbbDimens
import railstatus.mobile.ui.theme.SbbTheme

/** Looks of [Segmented]. */
enum class SegmentedStyle {
    /** In the red header: red125 track, red thumb with a red150 ring (reads as a cut-out), white labels. */
    Header,
    /** In a sheet: #424242 / #D9D9D9 track, inverted thumb (white in dark, black in light). */
    Sheet,
}

private val Pill = RoundedCornerShape(percent = 50)

/**
 * Segmented control (measured): 44.2 dp tall, inset 8 dp, 15.6 dp above the content (in the header). The track is a
 * 42.7 dp pill; the selected tab is the full 44.2 dp with a 1.15 dp ring. Tabs share the width by label length
 * (CSS `flex: 1 1 auto`: each gets its label width plus an equal share of the rest; the bold selected label is wider).
 * The thumb jumps: no transition, as in the app. Slide the page below with [SegmentedPage].
 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    style: SegmentedStyle = SegmentedStyle.Header,
) {
    val c = SbbTheme.colors
    val track = when (style) { SegmentedStyle.Header -> c.red125; SegmentedStyle.Sheet -> if (c.isDark) Color(0xFF424242) else Color(0xFFD9D9D9) }
    val thumb = when (style) { SegmentedStyle.Header -> c.red; SegmentedStyle.Sheet -> if (c.isDark) Color.White else Color.Black }
    val ring = when (style) { SegmentedStyle.Header -> c.red150; SegmentedStyle.Sheet -> thumb }
    val onText = when (style) { SegmentedStyle.Header -> Color.White; SegmentedStyle.Sheet -> if (c.isDark) Color.Black else Color.White }
    val offText = when (style) { SegmentedStyle.Header -> Color.White; SegmentedStyle.Sheet -> c.text }
    val base = SbbTheme.type.segment
    val measurer = rememberTextMeasurer()
    val border = 1.15.dp

    Box(
        modifier
            .padding(start = 8.dp, end = 8.dp, bottom = if (style == SegmentedStyle.Header) 15.6.dp else 0.dp)
            .fillMaxWidth()
            .height(SbbDimens.SegmentedHeight),
    ) {
        Box(Modifier.fillMaxSize().padding(vertical = 0.75.dp).background(track, Pill))
        Layout(
            content = {
                options.forEachIndexed { i, label ->
                    val on = i == selected
                    Box(
                        Modifier
                            .then(if (on) Modifier.background(thumb, Pill).border(BorderStroke(border, ring), Pill) else Modifier)
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { if (!on) onSelect(i) }
                            .semantics { this.selected = on }
                            .padding(top = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        SbbText(label, style = base, color = if (on) onText else offText, weight = if (on) FontWeight.Bold else FontWeight.Light, maxLines = 1, softWrap = false)
                    }
                }
            },
        ) { measurables, constraints ->
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            // flex: 1 1 auto -> basis = label width + borders; grow equally, or shrink in proportion to the basis.
            val basis = options.mapIndexed { i, label ->
                val wt = if (i == selected) FontWeight.Bold else FontWeight.Light
                measurer.measure(label, base.copy(fontWeight = wt)).size.width + 2 * border.toPx()
            }
            val free = w - basis.sum()
            val widths = basis.map { b -> if (free >= 0) b + free / basis.size else b * (1 + free / basis.sum()) }
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i].toInt(), h)) }
            layout(w, h) {
                var x = 0f
                placeables.forEachIndexed { i, p -> p.place(x.toInt(), 0); x += widths[i] }
            }
        }
    }
}

/**
 * The page under a [Segmented] control. When [index] changes, the new page slides in horizontally by the full width
 * over ~200 ms, nearly linear with a short ease-out (ViewPager, a06 recording).
 */
@Composable
fun SegmentedPage(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var last by remember { mutableIntStateOf(index) }
    val dir = remember(index) { index.compareTo(last) }
    // Keyed on the index, so the first frame of a change already starts off-screen.
    var shift by remember(index) { mutableFloatStateOf(dir.toFloat()) }
    LaunchedEffect(index) {
        last = index
        if (dir == 0) return@LaunchedEffect
        animateFrames(SbbMotion.SEGMENT_MS) { t -> shift = dir * (1f - SbbMotion.SegmentEasing.transform(t / SbbMotion.SEGMENT_MS)) }
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        val w = constraints.maxWidth
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = shift * w }) { content() }
    }
}
