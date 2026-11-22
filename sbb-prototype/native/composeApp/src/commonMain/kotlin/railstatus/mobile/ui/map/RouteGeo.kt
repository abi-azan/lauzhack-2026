@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.ui.map

import railstatus.mobile.data.Connection
import railstatus.mobile.data.LineKind
import railstatus.mobile.data.Mode
import railstatus.mobile.data.currentLeg
import railstatus.mobile.data.mapLines
import railstatus.mobile.data.mapStations
import railstatus.mobile.data.trainMarker
import kotlin.math.hypot
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// The journey as map geometry: one polyline per stop-to-stop segment (along the tracks for trains, when the rail
// network has a plausible path), the stops with their marker roles, and where the train is.

internal enum class StopRole { END, TRANSFER, VIA }

internal class RouteStop(val name: String, val x: Float, val y: Float, val role: StopRole, val labelWest: Boolean)

internal class RouteLeg(val walk: Boolean, val local: Boolean, val segments: List<FloatArray>)

internal class RouteGeo(val legs: List<RouteLeg>, val stops: List<RouteStop>)

/** The train on the route: the leg, the segment, the vertex before it, its position and its heading (local units). */
internal class TrainAt(val leg: Int, val seg: Int, val vertex: Int, val x: Float, val y: Float, val hx: Float, val hy: Float)

/**
 * Which side a station's label goes: away from the line when the line leaves sideways; to the right (east) when it
 * leaves mostly up or down, as SBB does (Genève on Genève-Lausanne).
 */
internal fun labelWest(lon: Double, lat: Double, nextLon: Double, nextLat: Double): Boolean {
    val dx = (nextLon - lon) * kotlin.math.cos(lat * kotlin.math.PI / 180)
    val dy = nextLat - lat
    return kotlin.math.abs(dx) >= kotlin.math.abs(dy) && dx > 0
}

internal fun buildRoute(conn: Connection, rails: RailGraph?): RouteGeo {
    val lines = conn.mapLines()
    val legs = lines.map { l ->
        val walk = l.kind == LineKind.WALK
        val pts = l.coords.map { Merc.lx(it.lon) to Merc.ly(it.lat) }
        val segs = (0 until pts.size - 1).map { i ->
            val (ax, ay) = pts[i]; val (bx, by) = pts[i + 1]
            (if (l.mode == Mode.TRAIN && rails != null) rails.route(ax, ay, bx, by) else null) ?: floatArrayOf(ax, ay, bx, by)
        }
        RouteLeg(walk, l.mode != null && l.mode != Mode.TRAIN && l.mode != Mode.WALK, segs)
    }
    val stations = conn.mapStations()
    fun key(lon: Double, lat: Double) = "${(lon * 1e5).toLong()},${(lat * 1e5).toLong()}"
    val changes = lines.drop(1).mapNotNull { it.coords.firstOrNull() }.map { key(it.lon, it.lat) }.toSet()
    val stops = stations.mapIndexed { i, s ->
        val end = i == 0 || i == stations.size - 1
        val role = when {
            end -> StopRole.END
            key(s.lon, s.lat) in changes -> StopRole.TRANSFER
            else -> StopRole.VIA
        }
        // The label goes on the side away from the line.
        val next = stations.getOrNull(if (i == 0) 1 else i - 1)
        RouteStop(s.name, Merc.lx(s.lon), Merc.ly(s.lat), role, labelWest = next != null && labelWest(s.lon, s.lat, next.lon, next.lat))
    }
    return RouteGeo(legs, stops)
}

/** Where the train is at [now], along the drawn geometry; null when the trip is not under way. */
internal fun RouteGeo.trainAt(conn: Connection, now: Instant): TrainAt? {
    if (conn.trainMarker(now) == null) return null
    val li = conn.currentLeg(now)
    val leg = legs.getOrNull(li) ?: return null
    val s = conn.legs[li].stops
    if (leg.segments.isEmpty()) return null
    var seg = leg.segments.size - 1
    var f = 1.0
    if (now <= s[0].dep!!) { seg = 0; f = 0.0 } else {
        for (i in 0 until s.size - 1) {
            val a = s[i].dep ?: s[i].arr!!
            val b = s[i + 1].arr ?: s[i + 1].dep!!
            if (now <= b) {
                val span = maxOf(1L, b.toEpochMilliseconds() - a.toEpochMilliseconds()).toDouble()
                f = ((now.toEpochMilliseconds() - a.toEpochMilliseconds()) / span).coerceIn(0.0, 1.0)
                seg = i
                break
            }
        }
    }
    seg = seg.coerceIn(0, leg.segments.size - 1)
    val xy = leg.segments[seg]
    val n = xy.size / 2
    var total = 0.0
    for (i in 0 until n - 1) total += hypot(xy[2 * i + 2] - xy[2 * i], xy[2 * i + 3] - xy[2 * i + 1])
    var want = total * f
    for (i in 0 until n - 1) {
        val dx = xy[2 * i + 2] - xy[2 * i]; val dy = xy[2 * i + 3] - xy[2 * i + 1]
        val len = hypot(dx, dy).toDouble()
        if (want <= len || i == n - 2) {
            val t = if (len > 0) (want / len).coerceIn(0.0, 1.0).toFloat() else 0f
            return TrainAt(li, seg, i, xy[2 * i] + dx * t, xy[2 * i + 1] + dy * t, dx, dy)
        }
        want -= len
    }
    return TrainAt(li, seg, 0, xy[0], xy[1], 0f, 0f)
}
