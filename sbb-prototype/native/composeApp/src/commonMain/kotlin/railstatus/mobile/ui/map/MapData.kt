package railstatus.mobile.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import railstatus.mobile.resources.Res
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.tan

// The map's offline data (native/assets/map, copied into composeResources/files/map by the build):
//  - rail-network.json  railways at network zooms ("main" lines and "all"), from the web app
//  - basemap.json       lakes, country/canton borders and city/town labels (build-basemap.mjs)
// Both are OpenStreetMap data (ODbL) via OpenFreeMap / OpenMapTiles. Coordinates are kept in Web Mercator relative
// to a Swiss origin, as floats: small numbers keep full float precision down to street zooms.

/** Web Mercator, world = [0, 1] x [0, 1]. */
internal object Merc {
    fun x(lon: Double): Double = (lon + 180.0) / 360.0
    fun y(lat: Double): Double {
        val r = lat * PI / 180.0
        return (1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0
    }
    fun lon(x: Double): Double = x * 360.0 - 180.0
    fun lat(y: Double): Double = atan(kotlin.math.sinh(PI * (1 - 2 * y))) * 180.0 / PI

    /** Origin of the local coordinates (central Switzerland). */
    val OX: Double = x(8.2)
    val OY: Double = y(46.8)

    fun lx(lon: Double): Float = (x(lon) - OX).toFloat()
    fun ly(lat: Double): Float = (y(lat) - OY).toFloat()
}

/** A line or ring in local Mercator coordinates (x0, y0, x1, y1, ...) with its bounding box. */
internal class Polyline(val xy: FloatArray) {
    val size: Int get() = xy.size / 2
    val minX: Float; val minY: Float; val maxX: Float; val maxY: Float

    init {
        var a = Float.MAX_VALUE; var b = Float.MAX_VALUE; var c = -Float.MAX_VALUE; var d = -Float.MAX_VALUE
        for (i in 0 until size) {
            val x = xy[2 * i]; val y = xy[2 * i + 1]
            if (x < a) a = x; if (y < b) b = y; if (x > c) c = x; if (y > d) d = y
        }
        minX = a; minY = b; maxX = c; maxY = d
    }

    fun x(i: Int) = xy[2 * i]
    fun y(i: Int) = xy[2 * i + 1]
}

/** A city or town label of the basemap. */
internal class Place(val name: String, val city: Boolean, val rank: Int, val capital: Int, val x: Float, val y: Float, val lon: Double, val lat: Double) {
    /** The network view's big names (Zürich, Bern, Genève, Milano, ...): bold dot with a dark core. */
    val big: Boolean get() = city && (rank <= 6 || capital <= 5)
}

internal class MapBase(
    val railMain: List<Polyline>,
    val railAll: List<Polyline>,
    val railTunnel: List<Polyline>,
    /** Lakes: each polygon's rings (outer first). */
    val water: List<List<Polyline>>,
    val borderCountry: List<Polyline>,
    val borderCanton: List<Polyline>,
    /** Sorted by importance (cities first, then rank). */
    val places: List<Place>,
) {
    val rails: RailGraph by lazy { RailGraph(railMain + railAll + railTunnel) }
}

/** Loads the map data once per process; [base] is null until it is in. */
internal object MapAssets {
    var base: MapBase? by mutableStateOf(null)
        private set
    private val lock = Mutex()
    private var failed = false

    suspend fun load() = lock.withLock {
        if (base != null || failed) return@withLock
        base = try {
            parse(Res.readBytes("files/map/rail-network.json").decodeToString(), Res.readBytes("files/map/basemap.json").decodeToString())
        } catch (e: Exception) {
            failed = true
            println("map: could not load the map data: $e")
            null
        }
    }

    fun parse(railJson: String, baseJson: String): MapBase {
        val main = ArrayList<Polyline>(); val all = ArrayList<Polyline>(); val tunnel = ArrayList<Polyline>()
        for (f in features(railJson)) {
            val p = f["properties"]!!.jsonObject
            val set = p["set"]?.jsonPrimitive?.content
            val into = when {
                set == "main" -> main
                p.containsKey("tunnel") -> tunnel
                else -> all
            }
            lines(f["geometry"]!!.jsonObject).forEach { into += it }
        }
        val water = ArrayList<List<Polyline>>(); val b2 = ArrayList<Polyline>(); val b4 = ArrayList<Polyline>(); val places = ArrayList<Place>()
        for (f in features(baseJson)) {
            val p = f["properties"]!!.jsonObject
            val g = f["geometry"]!!.jsonObject
            when (p["layer"]?.jsonPrimitive?.content) {
                "water" -> water += g["coordinates"]!!.jsonArray.map { polyline(it.jsonArray) }
                "border" -> (if (p["level"]?.jsonPrimitive?.int == 2) b2 else b4) += lines(g)
                "place" -> {
                    val c = g["coordinates"]!!.jsonArray
                    val lon = c[0].jsonPrimitive.double; val lat = c[1].jsonPrimitive.double
                    places += Place(
                        name = p["name"]!!.jsonPrimitive.content,
                        city = p["class"]?.jsonPrimitive?.content == "city",
                        rank = p["rank"]?.jsonPrimitive?.intOrNull ?: 99,
                        capital = p["capital"]?.jsonPrimitive?.intOrNull ?: 99,
                        x = Merc.lx(lon), y = Merc.ly(lat), lon = lon, lat = lat,
                    )
                }
            }
        }
        places.sortWith(compareBy({ if (it.city) 0 else 1 }, { it.rank }))
        return MapBase(main, all, tunnel, water, b2, b4, places)
    }

    private fun features(json: String): List<JsonObject> =
        Json.parseToJsonElement(json).jsonObject["features"]!!.jsonArray.map { it.jsonObject }

    private fun lines(g: JsonObject): List<Polyline> {
        val c = g["coordinates"]!!.jsonArray
        return when (g["type"]!!.jsonPrimitive.content) {
            "LineString" -> listOf(polyline(c))
            "MultiLineString" -> c.map { polyline(it.jsonArray) }
            else -> emptyList()
        }.filter { it.size >= 2 }
    }

    private fun polyline(c: JsonArray): Polyline {
        val xy = FloatArray(c.size * 2)
        c.forEachIndexed { i, p: JsonElement ->
            val a = p.jsonArray
            xy[2 * i] = Merc.lx(a[0].jsonPrimitive.double)
            xy[2 * i + 1] = Merc.ly(a[1].jsonPrimitive.double)
        }
        return Polyline(xy)
    }
}

/** The map data, loading it on first use. Null until it is in (the map then shows its background and the route). */
@Composable
internal fun rememberMapBase(): MapBase? {
    LaunchedEffect(Unit) { MapAssets.load() }
    return MapAssets.base
}
