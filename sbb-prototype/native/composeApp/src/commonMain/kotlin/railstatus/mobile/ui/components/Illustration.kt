package railstatus.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.commuting_dark
import railstatus.mobile.resources.commuting_light
import railstatus.mobile.resources.easyride_checked_in_light
import railstatus.mobile.resources.easyride_dark
import railstatus.mobile.resources.easyride_light
import railstatus.mobile.resources.trips_dark
import railstatus.mobile.resources.trips_light
import railstatus.mobile.ui.theme.SbbTheme

/**
 * How SBB Mobile places a scene in its box, fitted against the real app's screenshots on the same Pixel (2.625 px/dp).
 *
 * @param scale relative to filling the box's width (1 = the image as wide as the box)
 * @param bottom the image's bottom edge in dp below the box's top; null: at the box's bottom
 * @param cropLeft where the visible part starts, as a fraction of the scaled image's width; null: centred
 * @param focus a point of the image (fractions of its width and height) to centre in the box instead: the image covers
 *   the box ([scale] x the cover scale), the point as near the box's centre as covering allows. For short windows.
 */
data class SceneFit(val scale: Float = 1f, val bottom: Dp? = null, val cropLeft: Float? = null, val focus: Offset? = null)

/**
 * The official SBB Mobile illustrations, provided by SBB for the LauzHack 2026 demo (native/assets/illustrations,
 * copied into the gitignored composeResources/drawable at build time), day and night, with how the real app places
 * each ([SceneFit]; null: cropped to fill, bottom-centre).
 */
enum class Scene(val light: DrawableResource, val dark: DrawableResource, val lightFit: SceneFit?, val darkFit: SceneFit?) {
    /**
     * Trips > Single Trips: the lakeside with the train. Box top = the header's bottom (173.7 dp on the phone).
     * Night (1125x2250, sky included): width-fit, bottom 727.1 dp. Day (square): 2.1x width-fit, bottom 691.8 dp, the
     * visible part from 19.4 % of its width (the bench left of centre, the tree's trunk cut off).
     */
    Trips(Res.drawable.trips_light, Res.drawable.trips_dark, SceneFit(2.1f, 691.8.dp, 0.194f), SceneFit(1f, 727.1.dp)),

    /**
     * Trips > Commuting: the train interior. Night (1125x2432): 1.84x width-fit, bottom 958.5 dp, visible from 2.8 %
     * of its width (zoomed on the man with the laptop). Day: as Trips' day, centred (not captured in the real app).
     */
    Commuting(Res.drawable.commuting_light, Res.drawable.commuting_dark, SceneFit(2.1f, 691.8.dp), SceneFit(1.84f, 958.5.dp, 0.028f)),

    /**
     * EasyRide, checked out: the station, full-bleed from the header's bottom down to the panel, fitted to the real
     * app's capture on the same Pixel. Night (960x1819): width-fit (1.005x), bottom 585.5 dp. Day (1579x1534): 1.26x
     * width-fit, bottom 546.7 dp, from 0.3 % of its width (the woman with her phone left, the "E" sign and the tram
     * right; SBB's day asset differs slightly on the right).
     */
    EasyRide(Res.drawable.easyride_light, Res.drawable.easyride_dark, SceneFit(1.26f, 546.7.dp, 0.003f), SceneFit(1.005f, 585.5.dp, 0.0025f)),

    /**
     * EasyRide, checked in / riding: SBB's checked-in scene by day. SBB has no night version, so at night the commuting
     * train interior (the closest night scene: you are on the train).
     */
    EasyRideCheckedIn(Res.drawable.easyride_checked_in_light, Res.drawable.commuting_dark, SceneFit(focus = Offset(0.5f, 0.45f)), SceneFit(focus = Offset(0.40f, 0.65f))),
}

/**
 * A scene filling its parent, placed as SBB Mobile places it ([Scene.lightFit] / [Scene.darkFit]) and clipped to the
 * parent; without a fit, cropped to fill and anchored bottom-centre. The day/night variant follows the theme. Put it
 * first in a Box, content on top.
 */
@Composable
fun Landscape(scene: Scene, modifier: Modifier = Modifier) {
    val dark = SbbTheme.colors.isDark
    val painter = painterResource(if (dark) scene.dark else scene.light)
    val fit = if (dark) scene.darkFit else scene.lightFit
    if (fit == null) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.BottomCenter,
        )
        return
    }
    Canvas(modifier.fillMaxSize().clipToBounds()) {
        val src = painter.intrinsicSize
        if (src.isUnspecified || src.width <= 0f || src.height <= 0f) return@Canvas // not loaded yet
        val focus = fit.focus
        val w: Float
        val h: Float
        val left: Float
        val top: Float
        if (focus != null) {
            val cover = maxOf(size.width / src.width, size.height / src.height) * fit.scale
            w = src.width * cover
            h = src.height * cover
            left = (size.width / 2f - focus.x * w).coerceIn(size.width - w, 0f)
            top = (size.height / 2f - focus.y * h).coerceIn(size.height - h, 0f)
        } else {
            w = size.width * fit.scale
            h = w * src.height / src.width
            left = fit.cropLeft?.let { -it * w } ?: ((size.width - w) / 2f)
            top = (fit.bottom?.toPx() ?: size.height) - h
        }
        translate(left, top) { with(painter) { draw(Size(w, h)) } }
    }
}
