package railstatus.mobile.screens.trips

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import railstatus.mobile.ui.theme.SbbTheme

/**
 * Pull to refresh (web `usePullToRefresh`, recording a07): drag down at the top of the list and a small disc with a
 * progress arrow drops from under the header; past the threshold it spins for ~600 ms and scales out. The content
 * itself does not move.
 */
@Stable
class PullToRefreshState internal constructor(private val scope: CoroutineScope, private val density: Density, private val onRefresh: () -> Unit) {
    enum class Phase { Idle, Pulling, Spinning, Done }

    var phase by mutableStateOf(Phase.Idle)
        private set

    /** How far the disc has come down, dp (the web's `pull`: 0.55 × the drag, at most 96). */
    var pull by mutableFloatStateOf(0f)
        private set

    private var drag = 0f
    internal val scale = Animatable(1f)
    internal val settle = Animatable(0f)

    private fun updatePull(dp: Float) { pull = dp }

    internal val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Dragging back up while pulling shrinks the pull before the list scrolls.
            if (phase != Phase.Pulling || available.y >= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
            val dy = with(density) { available.y.toDp().value }
            val before = drag
            drag = (drag + dy).coerceAtLeast(0f)
            updatePull(minOf(96f, drag * 0.55f))
            return Offset(0f, with(density) { (drag - before).dp.toPx() })
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            if (phase != Phase.Idle && phase != Phase.Pulling) return Offset.Zero
            drag += with(density) { available.y.toDp().value }
            // The web starts after 4 px of movement.
            if (drag > 4f) {
                phase = Phase.Pulling
                updatePull(minOf(96f, drag * 0.55f))
            }
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (phase != Phase.Pulling && drag == 0f) return Velocity.Zero
            release()
            return available
        }
    }

    private fun release() {
        drag = 0f
        if (phase != Phase.Pulling) return
        if (pull > 60f) {
            phase = Phase.Spinning
            scope.launch {
                animatePull(64f)
                delay(600)
                onRefresh()
                phase = Phase.Done
                scale.animateTo(0f, tween(160, easing = Standard))
                phase = Phase.Idle
                updatePull(0f)
                scale.snapTo(1f)
            }
        } else {
            phase = Phase.Idle
            updatePull(0f)
        }
    }

    private suspend fun animatePull(to: Float) {
        settle.snapTo(pull)
        settle.animateTo(to, tween(160, easing = Standard)) { updatePull(value) }
    }

    private companion object {
        /** cubic-bezier(.4, 0, .2, 1) */
        val Standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    }
}

@Composable
fun rememberPullToRefresh(onRefresh: () -> Unit = {}): PullToRefreshState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember { PullToRefreshState(scope, density, onRefresh) }
}

/** Put this on the scrolling container, before `verticalScroll`. */
fun Modifier.pullToRefresh(state: PullToRefreshState): Modifier = nestedScroll(state.connection)

/**
 * The indicator (web `.ptr`): a 40 dp disc, #313131 with a white arrow (dark) or white with a red arrow (light), a
 * soft shadow; it hangs from the top of the content, `pull - 40` dp down. Place it in the content's Box.
 */
@Composable
fun BoxScope.PullToRefreshIndicator(state: PullToRefreshState) {
    val c = SbbTheme.colors
    val idle = state.phase == PullToRefreshState.Phase.Idle
    val spin = if (state.phase == PullToRefreshState.Phase.Spinning || state.phase == PullToRefreshState.Phase.Done) {
        val t = rememberInfiniteTransition()
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart)).value
    } else state.pull * 4f
    val y = if (idle) -48f else state.pull - 40f
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .graphicsLayer {
                translationY = y.dp.toPx()
                alpha = if (idle) 0f else 1f
                scaleX = state.scale.value
                scaleY = state.scale.value
            }
            .size(40.dp)
            .shadow(3.dp, CircleShape, ambientColor = Color(0x66000000), spotColor = Color(0x66000000))
            .background(if (c.isDark) Color(0xFF313131) else Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val ink = if (c.isDark) Color.White else c.red
        Canvas(Modifier.size(22.dp)) {
            val u = size.width / 22f
            rotate(spin) {
                // M11 4 a7 7 0 1 0 7 7: three quarters of a circle from the top, anticlockwise round to the right.
                drawArc(ink, 0f, 270f, false, Offset(4 * u, 4 * u), Size(14 * u, 14 * u), style = Stroke(2.2f * u))
                val head = Path().apply { moveTo(14.5f * u, 1.2f * u); lineTo(19f * u, 4.6f * u); lineTo(14.4f * u, 7.8f * u); close() }
                drawPath(head, ink)
            }
        }
    }
}
