package railstatus.mobile.ui.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.withLock
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.time.TimeSource

// The basemap under the network and route maps: OpenFreeMap's vector tiles (OpenStreetMap, no key) drawn into 512 px
// bitmaps in SBB Mobile's map style (VectorTiles.kt), kept in a small LRU, parent tiles drawn scaled while the children
// load. Offline (no tile ever arrived), the map falls back to its baked OSM layers.

internal const val BASEMAP_CREDIT = "© OpenMapTiles © OpenStreetMap contributors"

/** Bitmap tiles go this deep (beyond z14 they are drawn from a part of a z14 vector tile). */
private const val MAX_TILE_ZOOM = 18
private const val TILE_PX = TILE_BITMAP_PX
private const val CACHE_SIZE = 90 // 512 px tiles, 1 MiB each decoded

/** OpenFreeMap's current tile URL template, from its TileJSON (the dated path changes with each planet build). */
private const val TILEJSON = "https://tiles.openfreemap.org/planet"
private const val TILES_FALLBACK = "https://tiles.openfreemap.org/planet/20261115_080022_pt/{z}/{x}/{y}.pbf"

private fun key(dark: Boolean, z: Int, x: Int, y: Int): Long =
    ((if (dark) 1L else 0L) shl 60) or (z.toLong() shl 52) or (x.toLong() shl 26) or y.toLong()

private fun kDark(k: Long) = (k shr 60) and 1L == 1L
private fun kZ(k: Long) = ((k shr 52) and 0xFF).toInt()
private fun kX(k: Long) = ((k shr 26) and 0x3FFFFFF).toInt()
private fun kY(k: Long) = (k and 0x3FFFFFF).toInt()

/** The decoded tiles, shared by every map in the app. */
internal object Tiles {
    private val http by lazy { HttpClient() }
    private val gate = Semaphore(8)
    private val lru = LinkedHashMap<Long, ImageBitmap>()
    private val raw = LinkedHashMap<String, ByteArray>()
    /** The names of each data tile (theme-independent), by data tile key. */
    private val labels = LinkedHashMap<Long, List<TileLabel>>()
    var labelVersion = 0
        private set

    fun labelsOf(dk: Long): List<TileLabel>? = labels[dk]
    private var template: String? = null
    private val templateLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun template(): String = templateLock.withLock {
        template ?: run {
            val t = try {
                val res = http.get(TILEJSON)
                Regex("\"tiles\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").find(res.bodyAsText())?.groupValues?.get(1)
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { null }
            (t ?: TILES_FALLBACK).also { template = it }
        }
    }
    private val failedAt = HashMap<Long, TimeSource.Monotonic.ValueTimeMark>()

    /** Bumped when a tile arrives: the maps read it while drawing, so they redraw. */
    var version by mutableIntStateOf(0)
        private set

    /** True once a tile has arrived (the basemap is reachable); until then the maps draw their baked layers. */
    var online by mutableStateOf(false)
        private set

    fun peek(k: Long): ImageBitmap? = lru[k]

    fun has(k: Long) = lru.containsKey(k)

    fun recentlyFailed(k: Long): Boolean = failedAt[k]?.let { it.elapsedNow().inWholeSeconds < 15 } ?: false

    private fun touch(k: Long) { lru.remove(k)?.let { lru[k] = it } }

    suspend fun fetch(k: Long, density: Float) = gate.withPermit {
        try {
            val z = kZ(k); val x = kX(k); val y = kY(k)
            val over = (z - VECTOR_MAX_ZOOM).coerceAtLeast(0)
            val url = template().replace("{z}", "${z - over}").replace("{x}", "${x shr over}").replace("{y}", "${y shr over}")
            // Deep zooms cut several bitmaps from one z14 tile: keep the last few downloads.
            val bytes = raw[url] ?: run {
                val res = http.get(url)
                if (!res.status.isSuccess()) throw IllegalStateException("HTTP ${res.status.value}")
                res.bodyAsBytes().also { raw[url] = it; while (raw.size > 24) raw.remove(raw.keys.first()) }
            }
            val dk = key(false, z - over, x shr over, y shr over)
            val needLabels = !labels.containsKey(dk)
            val (img, names) = withContext(Dispatchers.Default) {
                val layers = decodeMvt(bytes, TILE_LAYERS + (if (needLabels) LABEL_LAYERS else emptySet()))
                renderVectorTile(layers, z, x, y, kDark(k), density) to (if (needLabels) extractLabels(layers, z - over, x shr over, y shr over) else null)
            }
            if (names != null) {
                labels[dk] = names
                while (labels.size > 64) labels.remove(labels.keys.first())
                labelVersion++
            }
            lru[k] = img
            while (lru.size > CACHE_SIZE) lru.remove(lru.keys.first())
            failedAt.remove(k)
            online = true
            version++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("map: tile ${kZ(k)}/${kX(k)}/${kY(k)} failed: $e")
            failedAt[k] = TimeSource.Monotonic.markNow()
        }
    }

    /** Marks the tiles a map is about to draw as recently used. */
    fun used(keys: Collection<Long>) = keys.forEach { touch(it) }
}

/** The tile zoom for a view: 512 px bitmaps at about one bitmap pixel per screen pixel (0.66 to 1.3 screen px). */
internal fun tileZoom(zoom: Double, density: Float): Int =
    floor(zoom + ln(density.toDouble()) / ln(2.0) + 0.6).toInt().coerceIn(0, MAX_TILE_ZOOM)

/** The tile ranges a view covers at [tz], with [ring] extra tiles around. */
private inline fun forTiles(c: MapCamera, w: Float, h: Float, d: Float, tz: Int, ring: Int, f: (x: Int, y: Int) -> Unit) {
    val s = c.scale(d)
    val n = 1 shl tz
    val x0 = floor((c.cx - w / 2 / s) * n).toInt() - ring
    val x1 = floor((c.cx + w / 2 / s) * n).toInt() + ring
    val y0 = floor((c.cy - h / 2 / s) * n).toInt() - ring
    val y1 = floor((c.cy + h / 2 / s) * n).toInt() + ring
    for (y in y0.coerceAtLeast(0)..y1.coerceAtMost(n - 1)) for (x in x0.coerceAtLeast(0)..x1.coerceAtMost(n - 1)) f(x, y)
}

/** Loads the tiles one map wants (visible plus one ring, centre first) and cancels the ones it no longer wants. */
internal class TileLoader(private val scope: CoroutineScope) {
    private val jobs = HashMap<Long, Job>()

    fun want(c: MapCamera, w: Float, h: Float, d: Float, dark: Boolean) {
        if (w <= 0f || h <= 0f) return
        val tz = tileZoom(c.zoom, d)
        val n = 1 shl tz
        val cx = c.cx * n; val cy = c.cy * n
        val wanted = ArrayList<Long>()
        val dist = HashMap<Long, Double>()
        forTiles(c, w, h, d, tz, 1) { x, y ->
            val k = key(dark, tz, x, y)
            wanted += k
            val dx = x + 0.5 - cx; val dy = y + 0.5 - cy
            dist[k] = dx * dx + dy * dy
        }
        // A cheap backdrop for fast zoom-outs: the whole view three levels up.
        if (tz >= 3) forTiles(c, w, h, d, tz - 3, 0) { x, y -> key(dark, tz - 3, x, y).let { wanted += it; dist[it] = -1.0 } }
        val set = wanted.toHashSet()
        val it = jobs.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (e.key !in set || !e.value.isActive) { if (e.key !in set) e.value.cancel(); it.remove() } }
        for (k in wanted.sortedBy { dist[it] }) {
            if (Tiles.has(k) || jobs.containsKey(k) || Tiles.recentlyFailed(k)) continue
            jobs[k] = scope.launch { Tiles.fetch(k, d) }
        }
    }
}

/**
 * Draws the basemap tiles for the view. A missing tile is covered by the nearest cached ancestor (scaled up) or by
 * its four children, so panning and zooming never flash blank. Returns false when nothing could be drawn.
 */
internal fun DrawScope.drawTiles(c: MapCamera, v: View, dark: Boolean): Boolean {
    @Suppress("UNUSED_VARIABLE") val tick = Tiles.version // redraw when tiles arrive
    val tz = tileZoom(c.zoom, v.d)
    val s = c.scale(v.d)
    val used = ArrayList<Long>()
    var any = false
    fun sx(m: Double) = (v.w / 2 + (m - c.cx) * s).roundToInt()
    fun sy(m: Double) = (v.h / 2 + (m - c.cy) * s).roundToInt()
    fun put(img: ImageBitmap, z: Int, x: Int, y: Int, srcX: Int, srcY: Int, srcW: Int) {
        val n = (1 shl z).toDouble()
        val l = sx(x / n); val r = sx((x + 1) / n); val t = sy(y / n); val b = sy((y + 1) / n)
        drawImage(
            img, IntOffset(srcX, srcY), IntSize(srcW, srcW), IntOffset(l, t), IntSize(r - l, b - t),
            filterQuality = FilterQuality.Low,
        )
    }
    forTiles(c, v.w, v.h, v.d, tz, 0) { x, y ->
        val k = key(dark, tz, x, y)
        val img = Tiles.peek(k)
        if (img != null) { put(img, tz, x, y, 0, 0, TILE_PX); used += k; any = true; return@forTiles }
        // Ancestors, nearest first: the matching part of the parent tile, scaled up.
        var drawn = false
        for (up in 1..6) {
            val pz = tz - up
            if (pz < 0) break
            val pk = key(dark, pz, x shr up, y shr up)
            val p = Tiles.peek(pk) ?: continue
            val sub = TILE_PX shr up
            val m = (1 shl up) - 1
            put(p, tz, x, y, (x and m) * sub, (y and m) * sub, sub)
            used += pk; drawn = true; any = true
            break
        }
        if (drawn || tz >= MAX_TILE_ZOOM) return@forTiles
        // Children (just zoomed out): each one where it is.
        for (cy in 0..1) for (cx in 0..1) {
            val ck = key(dark, tz + 1, 2 * x + cx, 2 * y + cy)
            Tiles.peek(ck)?.let { put(it, tz + 1, 2 * x + cx, 2 * y + cy, 0, 0, TILE_PX); used += ck; any = true }
        }
    }
    Tiles.used(used)
    return any
}

/** The names for the view: from the data tiles under the visible tiles (or their nearest loaded ancestor), merged. */
internal class LabelGather {
    private var keys: List<Long> = emptyList()
    private var ver = -1
    private var merged: List<TileLabel> = emptyList()

    fun get(c: MapCamera, w: Float, h: Float, d: Float): List<TileLabel> {
        val tz = tileZoom(c.zoom, d)
        val want = LinkedHashSet<Long>()
        forTiles(c, w, h, d, tz, 0) { x, y ->
            val over = (tz - VECTOR_MAX_ZOOM).coerceAtLeast(0)
            var dz = tz - over; var dx = x shr over; var dy = y shr over
            for (up in 0..4) {
                val dk = key(false, dz, dx, dy)
                if (Tiles.labelsOf(dk) != null) { want += dk; break }
                if (dz == 0) break
                dz--; dx = dx shr 1; dy = dy shr 1
            }
        }
        val list = want.toList()
        if (list == keys && ver == Tiles.labelVersion) return merged
        keys = list; ver = Tiles.labelVersion
        val seen = HashSet<String>()
        val out = ArrayList<TileLabel>()
        for (k in list) Tiles.labelsOf(k)?.forEach { if (seen.add(it.id)) out += it }
        out.sortWith(compareBy({ it.kind.ordinal }, { if (it.big) 0 else 1 }, { it.rank }, { -it.len }))
        merged = out
        return out
    }
}
