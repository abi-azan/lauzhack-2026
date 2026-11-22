package railstatus.mobile.ui.map

import kotlin.math.hypot

/**
 * The rail network as a graph, to draw a journey along the tracks instead of straight from stop to stop (as SBB Mobile
 * does). Vertices are snapped to ~80 m cells; the network data is cut at tile edges, so line ends are stitched to
 * anything within a kilometre.
 */
internal class RailGraph(lines: List<Polyline>) {
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private val adj = ArrayList<MutableMap<Int, Float>>()
    private val ids = HashMap<Long, Int>()

    private fun vertex(x: Float, y: Float): Int {
        val key = (kotlin.math.round(x / CELL).toLong() shl 32) xor (kotlin.math.round(y / CELL).toLong() and 0xffffffffL)
        return ids.getOrPut(key) { xs += x; ys += y; adj += HashMap(); xs.size - 1 }
    }

    private fun edge(a: Int, b: Int, w: Float) {
        if (a == b) return
        val old = adj[a][b]
        if (old == null || w < old) { adj[a][b] = w; adj[b][a] = w }
    }

    init {
        for (l in lines) {
            var prev = vertex(l.x(0), l.y(0))
            for (i in 1 until l.size) {
                val v = vertex(l.x(i), l.y(i))
                edge(prev, v, dist(prev, v))
                prev = v
            }
        }
        // Stitch loose ends to nearby vertices (a grid of STITCH-sized buckets).
        val grid = HashMap<Long, MutableList<Int>>()
        fun cell(i: Int) = (kotlin.math.floor(xs[i] / STITCH).toLong() shl 32) xor (kotlin.math.floor(ys[i] / STITCH).toLong() and 0xffffffffL)
        for (i in xs.indices) grid.getOrPut(cell(i)) { ArrayList() } += i
        val ends = xs.indices.filter { adj[it].size == 1 }
        for (e in ends) {
            val cx = kotlin.math.floor(xs[e] / STITCH).toLong(); val cy = kotlin.math.floor(ys[e] / STITCH).toLong()
            for (dx in -1L..1L) for (dy in -1L..1L) {
                val bucket = grid[((cx + dx) shl 32) xor ((cy + dy) and 0xffffffffL)] ?: continue
                for (o in bucket) {
                    if (o == e || adj[e].containsKey(o)) continue
                    val d = dist(e, o)
                    if (d < STITCH) edge(e, o, d * 1.5f)
                }
            }
        }
    }

    private fun dist(a: Int, b: Int) = hypot(xs[a] - xs[b], ys[a] - ys[b])

    private fun nearest(x: Float, y: Float): Int? {
        var best = -1; var bd = Float.MAX_VALUE
        for (i in xs.indices) {
            val d = hypot(xs[i] - x, ys[i] - y)
            if (d < bd) { bd = d; best = i }
        }
        return if (best >= 0 && bd < SNAP) best else null
    }

    /**
     * The track from (ax, ay) to (bx, by) as x, y pairs (ending exactly at both points), or null when the network has
     * no plausible path (then the caller draws a straight line).
     */
    fun route(ax: Float, ay: Float, bx: Float, by: Float): FloatArray? {
        val s = nearest(ax, ay) ?: return null
        val t = nearest(bx, by) ?: return null
        val straight = hypot(bx - ax, by - ay)
        val limit = straight * 1.6f + DETOUR
        if (s == t) return null
        val n = xs.size
        val d = FloatArray(n) { Float.MAX_VALUE }
        val prev = IntArray(n) { -1 }
        val heap = Heap()
        d[s] = 0f; heap.push(s, 0f)
        while (heap.isNotEmpty()) {
            val (u, du) = heap.pop()
            if (du > d[u]) continue
            if (u == t || du > limit) break
            for ((v, w) in adj[u]) {
                val nd = du + w
                if (nd < d[v]) { d[v] = nd; prev[v] = u; heap.push(v, nd) }
            }
        }
        if (d[t] > limit) return null
        val path = ArrayList<Int>()
        var v = t
        while (v != -1) { path += v; v = prev[v] }
        path.reverse()
        val out = FloatArray((path.size + 2) * 2)
        out[0] = ax; out[1] = ay
        path.forEachIndexed { i, p -> out[2 * i + 2] = xs[p]; out[2 * i + 3] = ys[p] }
        out[out.size - 2] = bx; out[out.size - 1] = by
        return out
    }

    /** A plain binary min-heap of (vertex, distance). */
    private class Heap {
        private var v = IntArray(64); private var k = FloatArray(64); private var n = 0
        fun isNotEmpty() = n > 0
        fun push(vertex: Int, key: Float) {
            if (n == v.size) { v = v.copyOf(n * 2); k = k.copyOf(n * 2) }
            var i = n++
            while (i > 0) {
                val p = (i - 1) / 2
                if (k[p] <= key) break
                v[i] = v[p]; k[i] = k[p]; i = p
            }
            v[i] = vertex; k[i] = key
        }
        fun pop(): Pair<Int, Float> {
            val top = v[0] to k[0]
            n--
            if (n > 0) {
                val lv = v[n]; val lk = k[n]
                var i = 0
                while (true) {
                    var c = 2 * i + 1
                    if (c >= n) break
                    if (c + 1 < n && k[c + 1] < k[c]) c++
                    if (k[c] >= lk) break
                    v[i] = v[c]; k[i] = k[c]; i = c
                }
                v[i] = lv; k[i] = lk
            }
            return top
        }
    }

    private companion object {
        /** One Mercator unit is ~27 400 km at Swiss latitudes. */
        const val KM = 1f / 27_400f
        const val CELL = 0.08f * KM
        const val STITCH = 1f * KM
        const val SNAP = 2f * KM
        const val DETOUR = 3f * KM
    }
}
