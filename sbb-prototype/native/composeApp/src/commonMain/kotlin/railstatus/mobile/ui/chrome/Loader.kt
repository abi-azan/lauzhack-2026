package railstatus.mobile.ui.chrome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import railstatus.mobile.ui.theme.SbbTheme

/**
 * SBB's loading indicator (a10): five red segments receding to the right (a train in perspective) with a brightness
 * wave running through them every 333 ms. 42 x 21 dp.
 */
@Composable
fun SbbLoader(modifier: Modifier = Modifier) {
    val red = SbbTheme.colors.red
    var ms by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { ms = (it - start) / 1_000_000f }
    }
    Canvas(modifier.size(42.dp, 21.dp).semantics { contentDescription = "Loading" }) {
        val u = size.width / 42f
        val xs = floatArrayOf(0f, 17.5f, 28.5f, 35.2f, 39.2f, 42f)
        fun h(x: Float) = 10.5f * (1 - 0.72f * x / 42f)
        for (i in 0 until 5) {
            val x0 = xs[i]; val x1 = xs[i + 1] - 1
            // CSS keyframes 0%/100% opacity .3, 40% opacity 1, delay (i*333/5 - 333) ms.
            val phase = (((ms - (i * 333f / 5 - 333f)) % 333f) + 333f) % 333f / 333f
            val a = if (phase < 0.4f) 0.3f + 0.7f * phase / 0.4f else 1f - 0.7f * (phase - 0.4f) / 0.6f
            val p = Path().apply {
                moveTo(x0 * u, (10.5f - h(x0)) * u); lineTo(x1 * u, (10.5f - h(x1)) * u)
                lineTo(x1 * u, (10.5f + h(x1)) * u); lineTo(x0 * u, (10.5f + h(x0)) * u); close()
            }
            drawPath(p, red.copy(alpha = a))
        }
        Offset.Zero
    }
}
