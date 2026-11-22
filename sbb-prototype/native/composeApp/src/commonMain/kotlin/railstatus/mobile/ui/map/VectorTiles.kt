package railstatus.mobile.ui.map

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ln

// OpenFreeMap's vector tiles (OpenMapTiles schema, OpenStreetMap data, no key) decoded and drawn into 512 px bitmaps
// in SBB Mobile's map style: land, landuse and woods, lakes and rivers, roads, buildings, country borders. No labels:
// the map draws its own names in the SBB font on top. Map data © OpenMapTiles © OpenStreetMap contributors (ODbL).

internal const val VECTOR_MAX_ZOOM = 14
internal const val TILE_BITMAP_PX = 512

// ------------------------------------------------------------------ Mapbox Vector Tile decoding (protobuf)

private class Pbf(val b: ByteArray, var pos: Int, val end: Int) {
    fun more() = pos < end
    fun varint(): Long {
        var r = 0L; var s = 0
        while (true) {
            val x = b[pos++].toInt() and 0xFF
            r = r or ((x and 0x7F).toLong() shl s)
            if (x < 0x80) return r
            s += 7
        }
    }
    fun sub(): Pbf { val n = varint().toInt(); val p = Pbf(b, pos, pos + n); pos += n; return p }
    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> pos += 8
            2 -> pos += varint().toInt()
            5 -> pos += 4
            else -> error("wire type $wire")
        }
    }
    fun string(): String { val n = varint().toInt(); val s = b.decodeToString(pos, pos + n); pos += n; return s }
    fun packed(): IntArray {
        val p = sub(); val out = ArrayList<Int>()
        while (p.more()) out += p.varint().toInt()
        return out.toIntArray()
    }
}

internal class MvtFeature(val type: Int, val props: Map<String, Any?>, val geom: IntArray)
internal class MvtLayer(val extent: Int, val features: List<MvtFeature>)

/** The [wanted] layers of a vector tile, with each feature's properties and raw geometry commands. */
internal fun decodeMvt(bytes: ByteArray, wanted: Set<String>): Map<String, MvtLayer> {
    val out = HashMap<String, MvtLayer>()
    val t = Pbf(bytes, 0, bytes.size)
    while (t.more()) {
        val k = t.varint().toInt()
        if (k shr 3 != 3) { t.skip(k and 7); continue }
        val l = t.sub()
        var name = ""; var extent = 4096
        val feats = ArrayList<Pbf>(); val keys = ArrayList<String>(); val vals = ArrayList<Any?>()
        while (l.more()) {
            val lk = l.varint().toInt()
            when (lk shr 3) {
                1 -> name = l.string()
                2 -> feats += l.sub()
                3 -> keys += l.string()
                4 -> vals += value(l.sub())
                5 -> extent = l.varint().toInt()
                else -> l.skip(lk and 7)
            }
        }
        if (name !in wanted) continue
        out[name] = MvtLayer(extent, feats.map { f ->
            var tags = IntArray(0); var type = 0; var geom = IntArray(0)
            while (f.more()) {
                val fk = f.varint().toInt()
                when (fk shr 3) {
                    2 -> tags = f.packed()
                    3 -> type = f.varint().toInt()
                    4 -> geom = f.packed()
                    else -> f.skip(fk and 7)
                }
            }
            val props = HashMap<String, Any?>()
            var i = 0
            while (i + 1 < tags.size) { props[keys[tags[i]]] = vals[tags[i + 1]]; i += 2 }
            MvtFeature(type, props, geom)
        })
    }
    return out
}

private fun value(p: Pbf): Any? {
    var v: Any? = null
    while (p.more()) {
        val k = p.varint().toInt()
        when (k shr 3) {
            1 -> v = p.string()
            2 -> { v = Float.fromBits(readInt32(p)); }
            3 -> { val lo = readInt32(p).toLong() and 0xFFFFFFFFL; val hi = readInt32(p).toLong(); v = Double.fromBits(lo or (hi shl 32)) }
            4, 5 -> v = p.varint()
            6 -> { val n = p.varint(); v = (n ushr 1) xor -(n and 1) }
            7 -> v = p.varint() != 0L
            else -> p.skip(k and 7)
        }
    }
    return v
}

private fun readInt32(p: Pbf): Int {
    val b = p.b; val i = p.pos; p.pos += 4
    return (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
}

/** Adds a feature's geometry to [path]: tile coordinates (0..extent) mapped by px = x * k + ox. */
private fun Path.addGeometry(geom: IntArray, k: Float, ox: Float, oy: Float, polygon: Boolean) {
    var i = 0; var x = 0; var y = 0
    while (i < geom.size) {
        val c = geom[i++]; val cmd = c and 7; val n = c ushr 3
        when (cmd) {
            1, 2 -> repeat(n) {
                x += (geom[i] ushr 1) xor -(geom[i] and 1); y += (geom[i + 1] ushr 1) xor -(geom[i + 1] and 1); i += 2
                if (cmd == 1) moveTo(x * k + ox, y * k + oy) else lineTo(x * k + ox, y * k + oy)
            }
            7 -> if (polygon) close()
        }
    }
}

// ------------------------------------------------------------------ style

internal class VectorStyle(
    val land: Color, val residential: Color?, val wood: Color, val park: Color, val water: Color,
    val road: Color, val roadCase: Color?, val path: Color, val building: Color, val buildingEdge: Color,
    val border: Color, val borderCore: Color?, val rail: Color, val railStreet: Color,
) {
    companion object {
        // Dark, measured from SBB Mobile's Plan map at street zoom: land #292929, buildings darker blocks with a
        // lighter edge, roads #454545, parks and woods dark green; rails a quiet grey.
        val Dark = VectorStyle(
            land = Color(0xFF292929), residential = null, wood = Color(0xFF2A322A), park = Color(0xFF2F3A2F), water = Color(0xFF17202E),
            road = Color(0xFF454545), roadCase = null, path = Color(0xFF3C3C3C), building = Color(0xFF1D1D1F), buildingEdge = Color(0xFF333335),
            border = Color(0xFF555555), borderCore = null, rail = Color(0xFF8E8C8C), railStreet = Color(0xFF5A5A5A),
        )
        // Light: the same map by day (land #FAFAFA, blue lakes, white roads with a grey edge, light grey buildings).
        val Light = VectorStyle(
            land = Color(0xFFF7F7F7), residential = null, wood = Color(0xFFE3EEDF), park = Color(0xFFDDEBD7), water = Color(0xFFB4D6EA),
            road = Color(0xFFFFFFFF), roadCase = Color(0xFFDADADA), path = Color(0xFFE2E2E2), building = Color(0xFFE6E6E6), buildingEdge = Color(0xFFD6D6D6),
            border = Color(0xFFB7C7B7), borderCore = Color(0xFF84B18E), rail = Color(0xFF8C8C8C), railStreet = Color(0xFFB0B0B0),
        )
    }
}

internal val TILE_LAYERS = setOf("water", "waterway", "landcover", "landuse", "park", "transportation", "building", "boundary")

/**
 * Draws tile [tz]/[x]/[y] from the decoded vector tile [layers] (zoom min(tz, 14): deeper tiles are a part of a z14 tile,
 * scaled up) into a 512 px bitmap. [density] is px per dp on screen; the bitmap is shown at about one bitmap px per
 * screen px, so line widths are dp x density at the matching map zoom.
 */
internal fun renderVectorTile(layers: Map<String, MvtLayer>, tz: Int, x: Int, y: Int, dark: Boolean, density: Float): ImageBitmap {
    val st = if (dark) VectorStyle.Dark else VectorStyle.Light
    val over = (tz - VECTOR_MAX_ZOOM).coerceAtLeast(0)
    val m = (1 shl over) - 1
    // The map zoom this bitmap is drawn for (MapLibre zoom, 512 dp world): 512 bitmap px per tile = 512 screen px.
    val z = tz - ln(density.toDouble()) / ln(2.0)
    val img = ImageBitmap(TILE_BITMAP_PX, TILE_BITMAP_PX)
    val canvas = Canvas(img)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(TILE_BITMAP_PX.toFloat(), TILE_BITMAP_PX.toFloat())) {
        drawRect(st.land)
        fun px(layer: MvtLayer) = TILE_BITMAP_PX.toFloat() * (1 shl over) / layer.extent
        val ox = -(x and m) * TILE_BITMAP_PX.toFloat(); val oy = -(y and m) * TILE_BITMAP_PX.toFloat()
        fun fill(name: String, color: Color, pick: (MvtFeature) -> Boolean) {
            val l = layers[name] ?: return
            val p = Path(); val k = px(l)
            for (f in l.features) if (f.type == 3 && pick(f)) p.addGeometry(f.geom, k, ox, oy, true)
            drawPath(p, color)
        }
        fun lines(name: String, color: Color, widthDp: Float, effect: PathEffect? = null, pick: (MvtFeature) -> Boolean) {
            if (widthDp <= 0f) return
            val l = layers[name] ?: return
            val p = Path(); val k = px(l)
            for (f in l.features) if (f.type == 2 && pick(f)) p.addGeometry(f.geom, k, ox, oy, false)
            drawPath(p, color, style = Stroke(widthDp * density, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
        }
        val cls = { f: MvtFeature -> f.props["class"] as? String }
        st.residential?.let { c -> fill("landuse", c) { cls(it) in setOf("residential", "suburb", "neighbourhood", "commercial", "industrial", "retail") } }
        fill("landcover", st.wood) { cls(it) == "wood" || (z >= 13.0 && cls(it) == "grass") }
        fill("park", st.park) { cls(it) == "park" || z >= 12.0 }
        // Under the lakes (SBB draws no border across a lake). Country borders (not at sea): a band in the light map, a grey line in the dark one.
        val country = { f: MvtFeature -> (f.props["admin_level"] as? Long) == 2L && f.props["maritime"] != 1L && f.props["maritime"] != true }
        if (st.borderCore != null) {
            lines("boundary", st.border, zoomStops(z, 5.0, 3.0, 9.0, 4.0, 14.0, 5.0), pick = country)
            lines("boundary", st.borderCore, zoomStops(z, 5.0, 1.0, 9.0, 1.4, 14.0, 1.8), pick = country)
        } else lines("boundary", st.border, zoomStops(z, 5.0, 1.0, 9.0, 1.6, 14.0, 2.4), pick = country)
        // Cantons from town zooms, dashed (not at street zoom).
        if (z in 8.5..13.0) lines("boundary", st.border, 0.8f, PathEffect.dashPathEffect(floatArrayOf(4f * density, 3f * density))) {
            (it.props["admin_level"] as? Long) == 4L && it.props["maritime"] != 1L
        }
        fill("water", st.water) { it.props["brunnel"] != "tunnel" }
        lines("waterway", st.water, zoomStops(z, 8.0, 0.6, 12.0, 1.4, 16.0, 3.0)) { it.props["brunnel"] != "tunnel" && cls(it) in setOf("river", "canal") }
        if (z >= 13.5) {
            val l = layers["building"]
            if (l != null) {
                val p = Path(); val k = px(l)
                for (f in l.features) if (f.type == 3) p.addGeometry(f.geom, k, ox, oy, true)
                drawPath(p, st.building)
                if (z >= 14.5) drawPath(p, st.buildingEdge, style = Stroke(1f * density))
            }
        }
        // Roads (no tunnels), widening to SBB's street widths; paths from street zoom.
        val road = { f: MvtFeature, set: Set<String> -> f.props["brunnel"] != "tunnel" && cls(f) in set }
        if (z >= 14.5) lines("transportation", st.path, zoomStops(z, 14.5, 0.6, 17.0, 1.4)) { road(it, PATHS) }
        val wMinor = zoomStops(z, 11.5, 0.5, 14.0, 1.6, 16.0, 4.5, 18.0, 11.0)
        val wMid = zoomStops(z, 8.5, 0.4, 12.0, 1.2, 14.0, 3.0, 16.0, 8.0, 18.0, 18.0)
        val wMajor = zoomStops(z, 5.0, 0.4, 8.0, 0.8, 12.0, 1.8, 14.0, 4.0, 16.0, 9.5, 18.0, 22.0)
        val case = st.roadCase?.takeIf { z >= 12.0 }
        if (case != null) {
            if (z >= 11.5) lines("transportation", case, wMinor + 1.2f) { road(it, MINOR) }
            lines("transportation", case, wMid + 1.2f) { road(it, MID) }
            lines("transportation", case, wMajor + 1.2f) { road(it, MAJOR) }
        }
        if (z >= 11.5) lines("transportation", st.road, wMinor) { road(it, MINOR) }
        if (z >= 8.5) lines("transportation", st.road, wMid) { road(it, MID) }
        lines("transportation", st.road, wMajor) { road(it, MAJOR) }
        // From town zooms the railways come from here (every track, exact); the map's own network fades out. At street
        // zoom they are as quiet as SBB's.
        if (z >= RAIL_FROM_TILES) lines("transportation", if (z >= 14.0) st.railStreet else st.rail, zoomStops(z, 11.0, 1.3, 14.0, 1.6, 17.0, 2.0)) {
            cls(it) in setOf("rail", "transit") && (it.props["subclass"] as? String) in RAILS && (z >= 14.0 || it.props["service"] == null)
        }
    }
    return img
}

/** The tiles draw the railways from this zoom (the map's coarse network fades out over the zoom before). */
internal const val RAIL_FROM_TILES = 11.0
private val RAILS = setOf("rail", "narrow_gauge", "light_rail", "funicular", "preserved", "subway", "tram", null)
private val MAJOR = setOf("motorway", "trunk", "primary")
private val MID = setOf("secondary", "tertiary")
private val MINOR = setOf("minor", "service", "busway")
private val PATHS = setOf("path", "track", "pedestrian")
