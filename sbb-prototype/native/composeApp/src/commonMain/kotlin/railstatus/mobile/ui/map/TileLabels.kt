package railstatus.mobile.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin

// Names from the vector tiles (OpenMapTiles `place`, `water_name`, `transportation_name`), drawn by the map in the SBB
// font on top of the basemap bitmaps: places from city to neighbourhood by zoom, lake names, street names along the
// street from z13. Decoded once per data tile; each name is measured once (TextCache) and placed greedily after the
// journey's own labels, outside the floating UI.

internal enum class LabelKind { CITY, TOWN, STOP, WATER, VILLAGE, SUBURB, STREET }

/**
 * One name. Points: [x], [y] (local Mercator). Along a line ([angle] != null): the middle of the line's longest
 * straight run, [len] long (Mercator units), at [angle] degrees.
 */
internal class TileLabel(
    val kind: LabelKind, val name: String, val rank: Int, val big: Boolean, val minZoom: Double,
    val x: Float, val y: Float, val angle: Float? = null, val len: Float = 0f,
    val maxZoom: Double = 99.0,
    /** A stop's pictogram (SBB's navy square for bus, tram, metro, train). */
    val icon: String? = null,
) {
    val id: String = name + "|" + (x * 1e6).roundToLong() / 2 + "|" + (y * 1e6).roundToLong() / 2
}

internal val LABEL_LAYERS = setOf("place", "water_name", "transportation_name", "poi")

/** The stop pictograms the labels use. */
internal val STOP_ICONS = listOf("picto-bus-right", "picto-tram-right", "picto-metro-right-de", "picto-train-right", "picto-cableway-right", "picto-funicular-railway-right")

/** The app's language for map names (name:de/fr/it/en), else the local name. */
private val nameKey: String = when (Locale.current.language.lowercase()) {
    "de" -> "name:de"; "fr" -> "name:fr"; "it" -> "name:it"; else -> "name:en"
}

/** Stops and streets keep their local names (as SBB shows them); places and lakes in the app's language. */
private fun MvtFeature.localName(): String? = (props["name"] as? String)?.takeIf { it.isNotBlank() }

private fun MvtFeature.name(): String? = (props[nameKey] as? String)?.takeIf { it.isNotBlank() } ?: (props["name"] as? String)?.takeIf { it.isNotBlank() }

/** The labels of data tile [z]/[x]/[y], most important first. */
internal fun extractLabels(layers: Map<String, MvtLayer>, z: Int, x: Int, y: Int): List<TileLabel> {
    val out = ArrayList<TileLabel>()
    val n = (1 shl z).toDouble()
    fun lx(l: MvtLayer, px: Int) = ((x + px.toDouble() / l.extent) / n - Merc.OX).toFloat()
    fun ly(l: MvtLayer, py: Int) = ((y + py.toDouble() / l.extent) / n - Merc.OY).toFloat()
    layers["place"]?.let { l ->
        for (f in l.features) {
            if (f.type != 1) continue
            val name = f.name() ?: continue
            val rank = (f.props["rank"] as? Long)?.toInt() ?: 99
            val capital = (f.props["capital"] as? Long)?.toInt() ?: 99
            val cls = f.props["class"] as? String
            val big = cls == "city" && (rank <= 6 || capital <= 5)
            // Place names give way to the streets and stops at street zoom (SBB's map shows none there).
            val (kind, min, max) = when (cls) {
                "city" -> Triple(LabelKind.CITY, if (big) 5.0 else if (rank <= 8) 7.0 else 8.0, 13.5)
                "town" -> Triple(LabelKind.TOWN, if (rank <= 10) 8.0 else 9.0, 14.0)
                "village" -> Triple(LabelKind.VILLAGE, 10.6, 14.5)
                "suburb" -> Triple(LabelKind.SUBURB, 12.5, 14.5)
                else -> continue
            }
            val p = firstPoint(f.geom) ?: continue
            out += TileLabel(kind, name, rank, big, min, lx(l, p.first), ly(l, p.second), maxZoom = max)
        }
    }
    layers["water_name"]?.let { l ->
        for (f in l.features) {
            val name = f.name() ?: continue
            val cls = f.props["class"] as? String
            if (cls != "lake" && cls != "bay") continue
            when (f.type) {
                // Lakes are named at regional zooms; SBB names no ponds at street zoom.
                1 -> firstPoint(f.geom)?.let { p -> out += TileLabel(LabelKind.WATER, name, 0, false, 8.0, lx(l, p.first), ly(l, p.second), maxZoom = 13.0) }
                2 -> straightRun(f.geom, l, x, y, n)?.let { r -> out += TileLabel(LabelKind.WATER, name, 0, false, 8.0, r[0], r[1], angle = 0f, len = r[3], maxZoom = 13.0) }
            }
        }
    }
    layers["transportation_name"]?.let { l ->
        for (f in l.features) {
            if (f.type != 2) continue
            val name = f.localName() ?: continue
            val min = when (f.props["class"] as? String) {
                "primary", "secondary" -> 13.0
                "tertiary" -> 14.0
                "minor" -> 15.0
                "pedestrian" -> 16.0
                else -> continue
            }
            val r = straightRun(f.geom, l, x, y, n) ?: continue
            out += TileLabel(LabelKind.STREET, name, 0, false, min, r[0], r[1], angle = r[2], len = r[3])
        }
    }
    // Localities of this tile, for stop names without one (SBB: "Ecublens VD, EPFL/Colladon").
    val towns = layers["place"]?.let { l ->
        l.features.filter { it.type == 1 && (it.props["class"] as? String) in setOf("city", "town", "village") }
            .mapNotNull { f -> val p = firstPoint(f.geom); val n = f.localName(); if (p != null && n != null) Triple(p.first, p.second, n) else null }
    } ?: emptyList()
    layers["poi"]?.let { l ->
        for (f in l.features) {
            if (f.type != 1) continue
            val cls = f.props["class"] as? String
            val sub = f.props["subclass"] as? String
            val (icon, min) = when {
                cls == "railway" && sub == "station" -> "picto-train-right" to 9.6
                cls == "railway" && sub == "halt" -> "picto-train-right" to 11.5
                cls == "railway" && sub == "subway" -> "picto-metro-right-de" to 14.0
                sub == "tram_stop" -> "picto-tram-right" to 15.0
                cls == "bus" || sub == "bus_stop" || sub == "bus_station" -> "picto-bus-right" to 15.0
                cls == "aerialway" -> "picto-cableway-right" to 14.0
                sub == "funicular" -> "picto-funicular-railway-right" to 14.0
                else -> continue
            }
            var name = f.localName() ?: continue
            val p = firstPoint(f.geom) ?: continue
            if (p.first < 0 || p.second < 0 || p.first >= l.extent || p.second >= l.extent) continue // the neighbour has it
            if (icon != "picto-train-right" && ',' !in name) {
                val town = towns.minByOrNull { (tx, ty, _) -> (tx - p.first).toDouble().let { it * it } + (ty - p.second).toDouble().let { it * it } }
                if (town != null && !name.startsWith(town.third)) name = "${town.third}, $name"
            }
            val rank = (f.props["rank"] as? Long)?.toInt() ?: 99
            out += TileLabel(LabelKind.STOP, name, if (icon == "picto-train-right") 0 else rank, false, min, lx(l, p.first), ly(l, p.second), icon = icon)
        }
    }
    out.sortWith(compareBy({ it.kind.ordinal }, { if (it.big) 0 else 1 }, { it.rank }, { -it.len }))
    return out
}

private fun firstPoint(geom: IntArray): Pair<Int, Int>? {
    if (geom.size < 3 || geom[0] and 7 != 1) return null
    return ((geom[1] ushr 1) xor -(geom[1] and 1)) to ((geom[2] ushr 1) xor -(geom[2] and 1))
}

/**
 * The longest nearly straight run (turns under 20 degrees) of a line feature: its middle (local Mercator), its angle
 * (degrees, kept upright) and its chord length (Mercator units).
 */
private fun straightRun(geom: IntArray, l: MvtLayer, tx: Int, ty: Int, n: Double): FloatArray? {
    var best: FloatArray? = null
    var i = 0; var cx = 0; var cy = 0
    val pts = ArrayList<Int>()
    fun runs() {
        if (pts.size < 4) { pts.clear(); return }
        var s = 0
        while (s < pts.size / 2 - 1) {
            var e = s + 1
            val a0 = atan2((pts[2 * e + 1] - pts[2 * s + 1]).toDouble(), (pts[2 * e] - pts[2 * s]).toDouble())
            while (e + 1 < pts.size / 2) {
                val a1 = atan2((pts[2 * e + 3] - pts[2 * e + 1]).toDouble(), (pts[2 * e + 2] - pts[2 * e]).toDouble())
                var d = abs(a1 - a0); if (d > PI) d = 2 * PI - d
                if (d > 20 * PI / 180) break
                e++
            }
            val dx = (pts[2 * e] - pts[2 * s]).toDouble(); val dy = (pts[2 * e + 1] - pts[2 * s + 1]).toDouble()
            val len = hypot(dx, dy) / l.extent / n
            if (best == null || len > best!![3]) {
                var ang = atan2(dy, dx) * 180 / PI
                if (ang > 90) ang -= 180; if (ang <= -90) ang += 180
                val mx = (pts[2 * s] + pts[2 * e]) / 2.0; val my = (pts[2 * s + 1] + pts[2 * e + 1]) / 2.0
                // Only runs whose middle is inside this tile (the buffer repeats them in the neighbour).
                if (mx >= 0 && my >= 0 && mx < l.extent && my < l.extent) best = floatArrayOf(
                    ((tx + mx / l.extent) / n - Merc.OX).toFloat(), ((ty + my / l.extent) / n - Merc.OY).toFloat(), ang.toFloat(), len.toFloat(),
                )
            }
            s = e
        }
        pts.clear()
    }
    while (i < geom.size) {
        val c = geom[i++]; val cmd = c and 7; val cnt = c ushr 3
        if (cmd == 1 || cmd == 2) repeat(cnt) {
            cx += (geom[i] ushr 1) xor -(geom[i] and 1); cy += (geom[i + 1] ushr 1) xor -(geom[i + 1] and 1); i += 2
            if (cmd == 1) runs()
            pts += cx; pts += cy
        }
    }
    runs()
    return best
}

/** Label colours over the basemap: muted, with a halo in the land colour. */
internal class LabelColors(val city: Color, val town: Color, val village: Color, val street: Color, val water: Color, val halo: Color, val stop: Color)

internal val DarkLabels = LabelColors(
    city = Color(0xFFF2F2F2), town = Color(0xFFCFCFCF), village = Color(0xFFA9A9A9), street = Color(0xFF9C9C9C),
    water = Color(0xFF7D8AAE), halo = Color(0xD0292929), stop = Color.White,
)
internal val LightLabels = LabelColors(
    city = Color(0xFF151515), town = Color(0xFF3A3A3A), village = Color(0xFF5E5E5E), street = Color(0xFF6E6E6E),
    water = Color(0xFF4A7BA3), halo = Color(0xEBFAFAFA), stop = Color.Black,
)

/** Draws [labels] that show at this zoom and fit; returns nothing (the placer remembers what was placed). */
internal fun DrawScope.drawTileLabels(
    labels: List<TileLabel>, v: View, c: LabelColors, bold: TextCacheAccess, regular: TextCacheAccess,
    free: (Rect) -> Boolean, add: (Rect) -> Unit, stops: List<Offset>, dot: Color, dotEdge: Color,
    icons: Map<String, railstatus.mobile.ui.components.SvgRenderer?>,
) {
    val z = v.zoom
    val halo = Stroke(v.dp(1.4f) * 2, join = StrokeJoin.Round)
    val waters = HashSet<String>() // a lake is named once in the view (every tile has its piece of the centre line)
    for (lb in labels) {
        if (z < lb.minZoom || z > lb.maxZoom) continue
        val x = v.x(lb.x); val y = v.y(lb.y)
        if (x < 0 || y < 0 || x > v.w || y > v.h) continue
        if (lb.kind == LabelKind.WATER && lb.name in waters) continue
        // The journey labels its own stations: skip the basemap's name for them.
        if ((lb.kind <= LabelKind.STOP) && stops.any { hypot(it.x - x, it.y - y) < v.dp(18f) }) continue
        val (size, color, strong) = when (lb.kind) {
            LabelKind.CITY -> Triple(if (lb.big) 17f else 15f, c.city, lb.big)
            LabelKind.TOWN -> Triple(14f, c.town, false)
            LabelKind.VILLAGE -> Triple(13f, c.village, false)
            LabelKind.SUBURB -> Triple(12.5f, c.village, false)
            LabelKind.WATER -> Triple(13f, c.water, false)
            LabelKind.STREET -> Triple(12f, c.street, false)
            LabelKind.STOP -> Triple(14f, c.stop, false)
        }
        val px = v.dp(size)
        val texts = if (strong) bold else regular
        val angle = lb.angle
        val icon = lb.icon?.let { icons[it] }
        if (icon != null) {
            // A stop: the pictogram on the place, the name centred below it (SBB).
            val hs = v.dp(10f)
            val ib = Rect(x - hs, y - hs, x + hs, y + hs)
            if (!free(ib)) continue
            add(ib)
            icon.draw(this, Color.White, Offset(x - hs, y - hs), Size(2 * hs, 2 * hs))
            val l = texts.get(lb.name, px, TextAlign.Center, px * 8f, density)
            val w = l.size.width.toFloat(); val h = l.size.height.toFloat()
            val tl = Offset(x - w / 2, y + hs + v.dp(4f))
            val box = Rect(tl, Size(w, h))
            if (!free(box)) continue
            add(box)
            drawText(l, c.halo, tl, drawStyle = halo)
            drawText(l, color, tl, drawStyle = Fill)
            continue
        }
        if (angle != null) {
            // Along a street (or a lake's centre line): only where the straight run is long enough for the name.
            val l = texts.get(lb.name, px, TextAlign.Left, Float.MAX_VALUE, density)
            val w = l.size.width.toFloat(); val h = l.size.height.toFloat()
            if (lb.len * v.s < w + v.dp(16f)) continue
            val r = angle * PI.toFloat() / 180f
            val bw = abs(w * cos(r)) + abs(h * sin(r)); val bh = abs(w * sin(r)) + abs(h * cos(r))
            val box = Rect(x - bw / 2, y - bh / 2, x + bw / 2, y + bh / 2)
            if (!free(box)) continue
            add(box)
            if (lb.kind == LabelKind.WATER) waters += lb.name
            rotate(angle, Offset(x, y)) {
                val tl = Offset(x - w / 2, y - h / 2)
                drawText(l, c.halo, tl, drawStyle = halo)
                drawText(l, color, tl, drawStyle = Fill)
            }
            continue
        }
        // Cities and towns: a dot with the name beside it; the rest: the name centred on the place.
        val withDot = lb.kind == LabelKind.CITY || lb.kind == LabelKind.TOWN
        val anchors = if (withDot) listOf(Offset(0f, 1f), Offset(0f, -1f), Offset(1f, 0f), Offset(-1f, 0f)) else listOf(Offset.Zero)
        val r = v.dp(if (lb.big) 3.2f else 2.8f)
        val dotBox = Rect(x - r, y - r, x + r, y + r)
        if (withDot && !free(dotBox)) continue
        val align = TextAlign.Center
        val l = texts.get(lb.name, px, align, px * 8f, density)
        val w = l.size.width.toFloat(); val h = l.size.height.toFloat()
        val off = px * 0.55f
        for (a in anchors) {
            val tl = when {
                a.y > 0 -> Offset(x - w / 2, y + off)
                a.y < 0 -> Offset(x - w / 2, y - off - h)
                a.x > 0 -> Offset(x + off, y - h / 2)
                a.x < 0 -> Offset(x - off - w, y - h / 2)
                else -> Offset(x - w / 2, y - h / 2)
            }
            val box = Rect(tl, Size(w, h))
            if (!free(box)) continue
            add(box)
            if (withDot) {
                add(dotBox)
                drawCircle(dot, r, Offset(x, y))
                drawCircle(dotEdge, r, Offset(x, y), style = Stroke(v.dp(1f)))
            }
            if (lb.kind == LabelKind.WATER) waters += lb.name
            drawText(l, c.halo, tl, drawStyle = halo)
            drawText(l, color, tl, drawStyle = Fill)
            break
        }
    }
}

/** What [drawTileLabels] needs from a text cache (RouteMap's TextCache is private to that file). */
internal fun interface TextCacheAccess {
    fun get(text: String, px: Float, align: TextAlign, maxWidth: Float, density: Float): androidx.compose.ui.text.TextLayoutResult
}
