package railstatus.mobile.ui.map

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Job
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Where the map looks: the Mercator point at the centre of the view and a MapLibre zoom (the world is 512 dp wide at
 * zoom 0). Read in the draw phase only, so panning redraws without recomposing.
 */
@Stable
internal class MapCamera(cx: Double = Merc.x(8.2), cy: Double = Merc.y(46.8), zoom: Double = 7.0) {
    var cx by mutableDoubleStateOf(cx)
    var cy by mutableDoubleStateOf(cy)
    var zoom by mutableDoubleStateOf(zoom)

    /** The running camera animation, cancelled when a finger touches the map. */
    var job: Job? = null

    /** Pixels per Mercator unit. */
    fun scale(density: Float): Double = 512.0 * 2.0.pow(zoom) * density

    fun set(c: CameraTarget) { cx = c.cx; cy = c.cy; zoom = c.zoom; clamp() }

    fun target() = CameraTarget(cx, cy, zoom)

    fun panBy(dx: Float, dy: Float, density: Float) {
        val s = scale(density)
        cx -= dx / s; cy -= dy / s
        clamp()
    }

    /** Zooms by [dz] levels keeping the point under [at] (px from the view's top left) still. */
    fun zoomBy(dz: Double, at: Offset, w: Float, h: Float, density: Float) {
        val s0 = scale(density)
        val px = cx + (at.x - w / 2) / s0
        val py = cy + (at.y - h / 2) / s0
        zoom = (zoom + dz).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val s1 = scale(density)
        cx = px - (at.x - w / 2) / s1
        cy = py - (at.y - h / 2) / s1
        clamp()
    }

    private fun clamp() {
        cx = cx.coerceIn(Merc.x(3.0), Merc.x(13.5))
        cy = cy.coerceIn(Merc.y(49.5), Merc.y(43.5))
    }

    companion object {
        const val MIN_ZOOM = 5.0
        const val MAX_ZOOM = 16.0
    }
}

internal data class CameraTarget(val cx: Double, val cy: Double, val zoom: Double) {
    fun lerp(to: CameraTarget, t: Float) = CameraTarget(cx + (to.cx - cx) * t, cy + (to.cy - cy) * t, zoom + (to.zoom - zoom) * t)
}

/** Free space around the framed points, in px (the floating UI plus a margin). */
internal data class FrameInsets(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * The camera that frames [points] (local Mercator x, y pairs) inside the view minus [pad], like MapLibre's fitBounds.
 * A single point (a change) gets a fixed close zoom.
 */
internal fun fitCamera(points: FloatArray, w: Float, h: Float, pad: FrameInsets, density: Float, maxZoom: Double = 13.0): CameraTarget? {
    if (points.size < 2 || w <= 0f || h <= 0f) return null
    var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
    for (i in 0 until points.size / 2) {
        val x = points[2 * i]; val y = points[2 * i + 1]
        x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
    }
    // When the floating UI leaves almost nothing (sheet pulled up), shrink the padding so a part of the map stays.
    val vk = min(1f, h * 0.85f / (pad.top + pad.bottom))
    val hk = min(1f, w * 0.85f / (pad.left + pad.right))
    val l = pad.left * hk; val r = pad.right * hk; val t = pad.top * vk; val b = pad.bottom * vk
    val dx = (x1 - x0).toDouble(); val dy = (y1 - y0).toDouble()
    val single = max(dx, dy) < 2e-6 // ~50 m
    val zoom = if (single) maxZoom else {
        val sx = (w - l - r) / (dx * 512.0 * density)
        val sy = (h - t - b) / (dy * 512.0 * density)
        (ln(min(sx, sy)) / ln(2.0)).coerceAtMost(maxZoom)
    }.coerceIn(MapCamera.MIN_ZOOM, MapCamera.MAX_ZOOM)
    val s = 512.0 * 2.0.pow(zoom) * density
    val bx = Merc.OX + (x0 + x1) / 2.0
    val by = Merc.OY + (y0 + y1) / 2.0
    return CameraTarget(bx - ((l - r) / 2.0) / s, by - ((t - b) / 2.0) / s, zoom)
}
