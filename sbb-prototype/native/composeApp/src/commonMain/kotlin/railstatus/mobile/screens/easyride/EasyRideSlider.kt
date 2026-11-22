package railstatus.mobile.screens.easyride

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.p_er_start
import railstatus.mobile.resources.p_er_stop
import railstatus.mobile.resources.p_er_swipe_start
import railstatus.mobile.resources.p_er_swipe_stop
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.theme.SbbTheme
import kotlin.math.max
import kotlin.math.roundToInt

/** EasyRide's own colours (checkin.css `.er`), light and dark. */
internal class ErColors(
    val panel: Color, val track: Color, val trackEdge: Color, val edge: Color, val btnEdge: Color,
    val seg: Color, val segEdge: Color, val thumb: Color, val thumbEdge: Color,
)

@Composable
internal fun erColors(): ErColors = if (SbbTheme.colors.isDark) {
    ErColors(
        panel = Color(0xFF151515), track = Color(0xFF212121), trackEdge = Color(0xFF444444), edge = Color(0xFFB7B7B7), btnEdge = Color(0xFF8D8D8D),
        seg = Color(0xFF212121), segEdge = Color(0xFF444444), thumb = Color(0xFF444444), thumbEdge = Color(0xFFB7B7B7),
    )
} else {
    ErColors(
        panel = Color.White, track = Color(0xFFF6F6F6), trackEdge = Color(0xFFBDBDBD), edge = Color(0xFF686868), btnEdge = Color(0xFF8D8D8D),
        seg = Color(0xFFE5E5E5), segEdge = Color(0xFFE5E5E5), thumb = Color.White, thumbEdge = Color(0xFF686868),
    )
}

private val RunEasing = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)
private val BackEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * EasyRide's swipe slider (measured from SBB's promo video): the red circle follows the finger, the hint under it
 * fades and the opposite one fades in; let go past half way and it runs to the end and spins until EasyRide has
 * started (or stopped). Short of half way it springs back. [busy]: the start/stop is under way (the circle spins).
 */
@Composable
internal fun EasyRideSlider(running: Boolean, busy: Boolean, onStart: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val er = erColors()
    val red = SbbTheme.colors.red
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val p = remember(running) { Animatable(0f) } // progress towards the other end, 0–1
    var pressed by remember { mutableStateOf(false) }
    var done by remember(running) { mutableStateOf(false) }
    // A failed start springs back.
    LaunchedEffect(busy) { if (!busy && done) { done = false; p.animateTo(0f, tween(260, easing = BackEasing)) } }
    val spinning = busy || done
    val dir = if (running) -1f else 1f
    val startLabel = stringResource(Res.string.p_er_start)
    val stopLabel = stringResource(Res.string.p_er_stop)

    fun commit() {
        done = true
        scope.launch { p.animateTo(1f, tween(170, easing = RunEasing)) }
        if (running) onStop() else onStart()
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(89.9.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(er.panel)
            // Measured on the real app: a 1.14 dp edge, then right inside it a 3.05 dp band in the track colour, then
            // the panel colour (11 px in all on the Pixel). The band touches the edge: no gap, so no doubled line.
            .drawBehind {
                val r = CornerRadius(size.height / 2)
                val ring = 4.19.dp.toPx()
                val edge = 1.14.dp.toPx()
                drawRoundRect(er.track, cornerRadius = r)
                drawRoundRect(er.panel, Offset(ring, ring), Size(size.width - 2 * ring, size.height - 2 * ring), CornerRadius(size.height / 2 - ring))
                drawRoundRect(er.trackEdge, Offset(edge / 2, edge / 2), Size(size.width - edge, size.height - edge), CornerRadius(size.height / 2 - edge / 2), style = Stroke(edge))
            }
            .semantics {
                contentDescription = if (running) "$stopLabel. You are currently checked in." else "$startLabel. You are currently checked out."
                onClick { if (!spinning) commit(); true }
            },
    ) {
        val knob = 81.5.dp
        // The same gap all round: centred vertically, and as far from the left end as from the top.
        val inset = (maxHeight - knob) / 2
        val maxPx = with(density) { (maxWidth - knob - inset * 2).toPx() }
        val progress = p.value
        val own = if (spinning) 0f else max(0f, 1f - progress * 2.2f)
        val other = if (spinning) 1f else max(0f, progress * 2f - 1f)
        val hintStyle = SbbTheme.type.small
        SbbText(
            stringResource(Res.string.p_er_swipe_start),
            Modifier.align(Alignment.CenterEnd).padding(end = 19.dp).graphicsLayer { alpha = if (running) other else own },
            style = hintStyle, color = SbbTheme.colors.meta, maxLines = 1,
        )
        SbbText(
            stringResource(Res.string.p_er_swipe_stop),
            Modifier.align(Alignment.CenterStart).padding(start = 20.dp).graphicsLayer { alpha = if (running) own else other },
            style = hintStyle, color = SbbTheme.colors.meta, maxLines = 1,
        )
        val x = (if (running) 1f - progress else progress) * maxPx
        val fill = when {
            spinning -> lerp(er.track, red, 0.72f)
            pressed -> lerp(er.track, red, 0.68f)
            else -> red
        }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = inset)
                .offset { IntOffset(x.roundToInt(), 0) }
                .size(knob)
                .clip(CircleShape)
                .background(fill)
                .then(if (pressed) Modifier.border(2.5.dp, red, CircleShape) else Modifier)
                .pointerInput(running, spinning, maxPx) {
                    if (spinning || maxPx <= 0f) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { pressed = true },
                        onDragEnd = {
                            pressed = false
                            if (p.value > 0.5f) commit() else scope.launch { p.animateTo(0f, tween(260, easing = BackEasing)) }
                        },
                        onDragCancel = { pressed = false; scope.launch { p.animateTo(0f, tween(260, easing = BackEasing)) } },
                    ) { change, dx ->
                        change.consume()
                        scope.launch { p.snapTo((p.value + dx * dir / maxPx).coerceIn(0f, 1f)) }
                    }
                }
                .pointerInput(spinning) {
                    // A tap shows the hint by nudging the circle, like SBB's.
                    if (spinning) return@pointerInput
                    detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false })
                },
            contentAlignment = Alignment.Center,
        ) {
            if (spinning) Spinner(red)
            else SbbText(
                if (running) stopLabel else startLabel,
                Modifier.padding(top = 1.dp),
                style = SbbTheme.type.bodyBold.copy(fontSize = SbbTheme.type.title.fontSize, lineHeight = SbbTheme.type.title.lineHeight),
                color = Color.White,
            )
        }
    }
}

/** The knob's spinner: a red arc growing and shrinking while it turns (checkin.css `er-rot` / `er-arc`). */
@Composable
private fun Spinner(color: Color) {
    val t = rememberInfiniteTransition()
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)))
    val arc by t.animateFloat(20f, 170f, infiniteRepeatable(tween(700), RepeatMode.Reverse))
    Canvas(Modifier.fillMaxSize()) {
        val r = size.minDimension * 36.5f / 82f
        val stroke = size.minDimension * 5f / 82f
        val sweep = arc / (2 * kotlin.math.PI.toFloat() * 36.5f) * 360f
        drawArc(
            color, startAngle = rot - 90f, sweepAngle = sweep, useCenter = false,
            topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** The default payment card left of the slider (44.2 x 28.2 dp): a card outline with the two overlapping circles. */
@Composable
internal fun PayCard() {
    val er = erColors()
    Canvas(Modifier.size(44.2.dp, 28.2.dp)) {
        val u = size.width / 44.2f
        drawRoundRect(er.panel, Offset(0.57f * u, 0.57f * u), Size(43.06f * u, 27.06f * u), androidx.compose.ui.geometry.CornerRadius(2.6f * u))
        drawRoundRect(
            er.edge, Offset(0.57f * u, 0.57f * u), Size(43.06f * u, 27.06f * u), androidx.compose.ui.geometry.CornerRadius(2.6f * u),
            style = Stroke(1.14f * u),
        )
        drawCircle(Color(0xFFEB001B), 6.4f * u, Offset(18.6f * u, 14.1f * u))
        drawCircle(Color(0xFFF79E1B).copy(alpha = 0.9f), 6.4f * u, Offset(25.6f * u, 14.1f * u))
    }
}
