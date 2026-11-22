package railstatus.mobile.screens.plan

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.touch_basel
import railstatus.mobile.resources.touch_bern
import railstatus.mobile.resources.touch_fribourg
import railstatus.mobile.resources.touch_geneve
import railstatus.mobile.resources.touch_lausanne
import railstatus.mobile.resources.touch_lugano
import railstatus.mobile.resources.touch_luzern
import railstatus.mobile.resources.touch_olten
import railstatus.mobile.resources.touch_renens
import railstatus.mobile.resources.touch_st_gallen
import railstatus.mobile.resources.touch_winterthur
import railstatus.mobile.resources.touch_zuerich

// Touch-timetable tile backgrounds: a dark map snippet around the station (faint streets, bright tracks), like SBB's.
// Pre-rendered from OpenStreetMap data (© OpenStreetMap contributors, ODbL) by native/scripts/touch-tiles/touch_tiles.py
// into native/assets/touch-tiles/<stationId>.webp; the build copies them to drawable/touch_<id>.webp.

/** SBB's tile navy, behind the map and for stations without a pre-rendered map. */
val TouchTileNavy = Color(0xFF293552)

private val TILE_MAPS: Map<String, DrawableResource> = mapOf(
    "bern" to Res.drawable.touch_bern,
    "lausanne" to Res.drawable.touch_lausanne,
    "olten" to Res.drawable.touch_olten,
    "geneve" to Res.drawable.touch_geneve,
    "renens" to Res.drawable.touch_renens,
    "fribourg" to Res.drawable.touch_fribourg,
    "basel" to Res.drawable.touch_basel,
    "zuerich" to Res.drawable.touch_zuerich,
    "luzern" to Res.drawable.touch_luzern,
    "winterthur" to Res.drawable.touch_winterthur,
    "st-gallen" to Res.drawable.touch_st_gallen,
    "lugano" to Res.drawable.touch_lugano,
)

/** Whether [stationId] has a pre-rendered map tile. */
fun hasTouchTileMap(stationId: String): Boolean = stationId in TILE_MAPS

/**
 * The map snippet for [stationId], cropped to fill [modifier]'s bounds (centred), on SBB's tile navy.
 * Stations without a pre-rendered map get the plain navy.
 */
@Composable
fun TouchTileBackground(stationId: String, modifier: Modifier = Modifier) {
    val res = TILE_MAPS[stationId]
    Box(modifier.clipToBounds().drawBehind { drawRect(TouchTileNavy) }) {
        if (res != null) {
            Image(painterResource(res), contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        }
    }
}

/**
 * The same background as a modifier, for a tile whose content is padded: draws the map behind the whole tile.
 * [fallback] draws stations without a pre-rendered map (e.g. ones added in the board editor).
 */
@Composable
fun Modifier.touchTileBackground(stationId: String, fallback: DrawScope.() -> Unit = { drawRect(TouchTileNavy) }): Modifier {
    val res = TILE_MAPS[stationId] ?: return drawBehind(fallback)
    val painter = painterResource(res)
    return drawBehind {
        drawRect(TouchTileNavy)
        val src = painter.intrinsicSize
        if (src.width <= 0f || src.height <= 0f) return@drawBehind
        val scale = maxOf(size.width / src.width, size.height / src.height)
        val dst = Size(src.width * scale, src.height * scale)
        clipRect {
            translate((size.width - dst.width) / 2f, (size.height - dst.height) / 2f) {
                with(painter) { draw(dst) }
            }
        }
    }
}
