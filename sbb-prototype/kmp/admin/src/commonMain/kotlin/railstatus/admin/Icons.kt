package railstatus.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The few SBB icons the dashboard needs, copied from the passenger app's `native/assets/icons` (SBB icons, Apache-2.0).
 * [square]: one of SBB's red disruption pictograms (16 viewBox, a red rounded square with white ink); otherwise a
 * 24 viewBox line icon drawn in a tint.
 */
enum class Icon(val square: Boolean, vararg val paths: String) {
    Info(true, "M8.081 2a1.48 1.48 0 1 0 0 2.96 1.48 1.48 0 0 0 0-2.96M6.723 12.665h-.929a.294.294 0 0 0 0 .587l.93.001h3.571a.293.293 0 1 0 0-.588H9.39V5.689H5.794a.294.294 0 1 0 0 .587h.93z"),
    Cancellation(
        true,
        "M10.4 11.265 8 8.865l-2.4 2.4-.865-.866L7.135 8l-2.4-2.4.865-.865 2.4 2.4 2.4-2.4.865.865-2.4 2.4 2.4 2.4z",
        "M7.994 2A5.997 5.997 0 0 0 2 8c0 3.312 2.682 6 5.994 6A6.003 6.003 0 0 0 14 8c0-3.312-2.688-6-6.006-6M8 12.8A4.8 4.8 0 0 1 3.2 8c0-2.652 2.148-4.8 4.8-4.8s4.8 2.148 4.8 4.8-2.148 4.8-4.8 4.8",
    ),
    Delay(
        true,
        "M7.994 2A5.997 5.997 0 0 0 2 8c0 3.312 2.682 6 5.994 6A6.003 6.003 0 0 0 14 8c0-3.312-2.688-6-6.006-6M8 12.8A4.8 4.8 0 0 1 3.2 8c0-2.652 2.148-4.8 4.8-4.8s4.8 2.148 4.8 4.8-2.148 4.8-4.8 4.8",
        "M8.3 5h-.9v3.541l3.15 1.859.45-.726-2.7-1.576z",
    ),
    ReplacementBus(true, "M6.822 10.362c-1.296 0-2.346 1.024-2.346 2.288s1.05 2.288 2.346 2.288S9.17 13.914 9.17 12.65s-1.05-2.288-2.347-2.288m0 3.363c-.604 0-1.095-.478-1.095-1.068s.49-1.068 1.095-1.068c.606 0 1.096.479 1.096 1.068s-.49 1.068-1.096 1.068m5.38-9.586C12.088 3.51 11.524 3 10.958 3H.003v1.227h1.615V8.5H.001v1.666L0 12.657h3.71c0-1.675 1.394-3.034 3.112-3.034 1.72 0 3.113 1.359 3.113 3.034h2.216c.572 0 1.036-.513 1.036-1.158V8.652zm-9.851.088h3.23V8.5H2.35zm4.038.004h.867v4.282H6.39zm1.706-.004h.868V8.51h-.868zM9.812 8.51V4.227h1.465l.957 4.282z"),
    Tick(false, "M12 3a9 9 0 0 1 9 9 9 9 0 0 1-9 9 9 9 0 0 1-9-9 9 9 0 0 1 9-9m0 19c5.523 0 10-4.477 10-10S17.523 2 12 2 2 6.477 2 12s4.477 10 10 10m-1.104-6.695 5-6.5-.793-.61-4.689 6.095-2.101-1.68-.626.78 2.5 2 .399.319z"),
    Exclamation(false, "M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18m0 19c5.523 0 10-4.477 10-10S17.523 2 12 2 2 6.477 2 12s4.477 10 10 10m.5-16v7h-1V6zm0 9v2h-1v-2z"),
    InfoCircle(false, "M12 3c5.028 0 9 3.97 9 9 0 5.028-3.972 9-9 9s-9-3.972-9-9c0-5.03 3.972-9 9-9m0 19c5.58 0 10-4.42 10-10 0-5.582-4.42-10-10-10S2 6.418 2 12c0 5.58 4.42 10 10 10m.98-14V6h-1v2zm2 7.99v1h-5v-1h2v-5H10v-1h2.98v6z"),
    Chevron(false, "m14.627 11.645.36.355-.36.356-3.95 3.904-.703-.712L13.564 12l-3.59-3.548.704-.71z"),
    ArrowLeft(false, "m5.804 12.49.352-.352 3.99-3.992.707.707-3.136 3.138H19v1H7.714l3.14 3.156-.708.705-3.99-4.008z"),
    Cross(false, "m12.707 12 5.647-5.647-.707-.707L12 11.293 6.354 5.646l-.707.707L11.293 12l-5.646 5.646.707.707L12 12.707l5.647 5.646.707-.707z"),
    Signet(false, "m13.264 9 2.352 2.318h-2.924V9h-1.383v2.318H8.401L10.753 9H9.004L6 12l3.004 3h1.749L8.4 12.698h2.908V15h1.383v-2.302h2.924L13.264 15h1.748L18 12l-2.988-3z"),
    ;

    internal val parsed: List<Path> by lazy {
        paths.map { d -> PathParser().parsePathString(d).toPath().apply { fillType = PathFillType.EvenOdd } }
    }
}

/**
 * Draws [icon] at [size]. Red squares keep their own colours; [ring] adds the 1.1 dp white outline SBB draws when a
 * pictogram sits on red. Line icons take [tint].
 */
@Composable
fun SbbIcon(icon: Icon, size: Dp = 24.dp, tint: Color = C.ink, ring: Boolean = false, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val view = if (icon.square) 16f else 24f
        val k = this.size.width / view
        // [ring] = on a red band: just the white symbol, no red square and no white box around it (as in the app).
        if (icon.square && !ring) drawRoundRect(C.red, cornerRadius = CornerRadius(2f * k))
        scale(k, k, pivot = Offset.Zero) {
            icon.parsed.forEach { drawPath(it, if (icon.square) Color.White else tint) }
        }
    }
}

/** The SBB signet, cropped to its ink (2 : 1), as in the app's header. */
@Composable
fun SbbSignet(width: Dp = 27.4.dp, color: Color = Color.White) {
    Canvas(Modifier.size(width, width / 2)) {
        val k = this.size.width / 12f
        scale(k, k, pivot = Offset.Zero) {
            translate(-6f, -9f) { drawPath(Icon.Signet.parsed[0], color) }
        }
    }
}
