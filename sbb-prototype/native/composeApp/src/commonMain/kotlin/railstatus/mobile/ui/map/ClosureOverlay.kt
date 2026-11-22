package railstatus.mobile.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.data.STATIONS
import railstatus.model.Stage

// The closed section of an open incident on the maps (the network map and the companion's route map): a dashed red
// line along the tracks with a white casing, and a ✕ at both ends. No open incident, nothing drawn.

/** The closed section as map points (local Web Mercator x, y pairs), or null. */
internal class Closure(val xy: FloatArray, val from: String? = null, val to: String? = null)

/**
 * The open incident's closed section, from the server's published `location` (live mode) or the scripted incident's
 * section (offline demo). Null when there is no incident, it is resolved, or a station isn't in the app's list.
 */
@Composable
internal fun rememberClosure(base: MapBase?): Closure? {
    val store = LocalAppGraph.current.passenger
    val state by store.state.collectAsState()
    val incident by store.incident.collectAsState()
    val live = state.live?.incident
    val ends: Pair<String, String>? = when {
        // Live: only the published location. The incident's `section` is the whole affected line there.
        state.api != null -> live?.takeIf { it.stage != Stage.RESOLVED }?.location?.let { it.from.name to it.to.name }
        else -> incident?.takeIf { it.stage != Stage.RESOLVED }?.section?.split(" – ")?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }
    // Along the tracks like the companion's journeys (the same rail graph, so equally close when zoomed far in).
    return remember(ends, base) { ends?.let { closureOf(it.first, it.second, listOfNotNull(base?.rails)) } }
}

/** Along the tracks of the first of [graphs] with a path between the two stations, else a straight line. */
internal fun closureOf(from: String, to: String, graphs: List<RailGraph> = emptyList()): Closure? {
    val a = STATIONS.firstOrNull { it.name == from } ?: return null
    val b = STATIONS.firstOrNull { it.name == to } ?: return null
    val ax = Merc.lx(a.lon); val ay = Merc.ly(a.lat)
    val bx = Merc.lx(b.lon); val by = Merc.ly(b.lat)
    return Closure(graphs.firstNotNullOfOrNull { it.route(ax, ay, bx, by) } ?: floatArrayOf(ax, ay, bx, by), a.name, b.name)
}

private val closureRed = Color(0xFFEB0000)

internal fun DrawScope.drawClosure(c: Closure, v: View) {
    val z = v.zoom
    val w = v.dp(zoomStops(z, 6.0, 3.5, 12.0, 4.5, 17.0, 5.5))
    val path = Path().apply { addLine(c.xy, v) }
    // White casing first, so the dashes read on top of the red train route and on the grey rail network alike.
    drawPath(path, Color.White, style = Stroke(w + v.dp(3f), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, closureRed, style = Stroke(w, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(v.dp(6f), v.dp(4f)))))
    // A ✕ at both ends: where the line is closed.
    val r = w * 1.1f
    val n = c.xy.size / 2
    for (i in listOf(0, n - 1)) {
        val x = v.x(c.xy[2 * i]); val y = v.y(c.xy[2 * i + 1])
        drawCircle(Color.White, r * 1.7f, Offset(x, y))
        drawCircle(closureRed, r * 1.7f, Offset(x, y), style = Stroke(v.dp(1.5f)))
        drawLine(closureRed, Offset(x - r, y - r), Offset(x + r, y + r), v.dp(2f), cap = StrokeCap.Round)
        drawLine(closureRed, Offset(x - r, y + r), Offset(x + r, y - r), v.dp(2f), cap = StrokeCap.Round)
    }
}
