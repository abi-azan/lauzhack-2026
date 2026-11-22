package railstatus.mobile.screens.plan

import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.foundation.layout.Box
import railstatus.mobile.data.AppStore
import railstatus.mobile.data.StationField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

// Touch timetable gestures, after SBB Mobile's "Touch-Fahrplan" (ubique). See the research notes in the report of
// 22 Nov; in short: tap a tile = from here to there; drag from one tile onto another = that connection, with a line
// following the finger; drag back over a tile you passed to take it out again; let go outside the board to cancel.
//
// The logic ([TouchGestureTracker]) is plain state, unit-tested in commonTest; [TouchGestureBoard] feeds it pointer
// events and draws the line on top of the tiles. Taps are left to the cells' own click handlers, so the board only
// takes over once the finger has moved past the touch slop.

/** Something on the board a gesture can start on or end on. */
sealed interface TouchTarget {
    /** A station tile. */
    data class Station(val id: String) : TouchTarget

    /** The "Current location" cell. */
    data object CurrentLocation : TouchTarget

    /** The "from" cell: drop a tile here to make it the From station. */
    data object FromCell : TouchTarget

    /** The "to" cell: drop a tile here to make it the To station. */
    data object ToCell : TouchTarget
}

/** What a finished gesture asks for. */
sealed interface TouchOutcome {
    /** A drag from [from] to [to], through [via] (station ids, in the order the finger passed them). */
    data class Route(val from: TouchTarget, val to: TouchTarget, val via: List<String> = emptyList()) : TouchOutcome

    /** Held still on [target] for the long-press time. */
    data class LongPress(val target: TouchTarget) : TouchOutcome

    /** A tap (no drag): the cell's own click handler takes it; the board does nothing. */
    data class Tap(val target: TouchTarget) : TouchOutcome

    /** Dragged, but let go outside the board, on the start tile, or on nothing that makes a route. */
    data object Cancelled : TouchOutcome
}

/** What a pointer move changed, for haptics. */
enum class TouchStep { NONE, DRAG_STARTED, ENTERED, BACKED_OUT }

/**
 * The gesture logic, free of pointer input: [down], [move]s, then [up] (or [cancel]).
 *
 * - The drag starts once the finger is [slop] px from where it went down, and only if it went down on a target.
 * - The path is the targets the finger has passed, in order. A target counts as entered only once the finger is
 *   [enterInset] (a fraction of the target's shorter side) inside it, so cutting a corner on the way does not pick
 *   up the neighbour. Gaps between tiles change nothing.
 * - Going back onto a target already in the path drops everything after it (drag back to undo).
 * - With [allowVia] false, the path is always start + the last target entered (no vias).
 * - Current location / from / to cells are ends only: passing over one and on to a tile drops it from the path.
 * - [up]: a path of two or more is a [TouchOutcome.Route] from the first to the last, unless the finger was let go
 *   outside every target's bounds (plus gaps) of the board ([releaseOutsideCancels]).
 */
@Stable
class TouchGestureTracker(
    var slop: Float,
    val allowVia: Boolean = true,
    private val enterInset: Float = 0.12f,
    private val releaseOutsideCancels: Boolean = true,
) {
    /** The targets' bounds, in the board's coordinates. Read once per gesture, at [down]. */
    var boundsProvider: () -> Map<TouchTarget, Rect> = { emptyMap() }

    /** The targets passed so far, the start first. Empty when idle. */
    val path = mutableStateListOf<TouchTarget>()

    /** Where the finger is, while dragging. */
    var finger: Offset? by mutableStateOf(null)
        private set

    var dragging: Boolean by mutableStateOf(false)
        private set

    private var bounds: Map<TouchTarget, Rect> = emptyMap()
    private var downAt = Offset.Zero

    /** The target at [p], if any; [inset] shrinks each target by that fraction of its shorter side first. */
    fun hit(p: Offset, inset: Float = 0f, within: Map<TouchTarget, Rect> = bounds): TouchTarget? =
        within.entries.firstOrNull { (_, r) ->
            val d = minOf(r.width, r.height) * inset
            p.x >= r.left + d && p.x < r.right - d && p.y >= r.top + d && p.y < r.bottom - d
        }?.key

    fun boundsOf(t: TouchTarget): Rect? = bounds[t]

    /** Finger down at [p]. Returns whether it is on a target (otherwise the board ignores this gesture). */
    fun down(p: Offset): Boolean {
        reset()
        bounds = boundsProvider()
        val t = hit(p) ?: return false
        downAt = p
        path.add(t)
        return true
    }

    fun move(p: Offset): TouchStep {
        if (path.isEmpty()) return TouchStep.NONE
        var step = TouchStep.NONE
        if (!dragging) {
            if ((p - downAt).getDistance() < slop) return TouchStep.NONE
            dragging = true
            step = TouchStep.DRAG_STARTED
        }
        finger = p
        val t = hit(p, enterInset) ?: return step
        if (t == path.last()) return step
        val at = path.indexOf(t)
        if (at >= 0) {
            while (path.size > at + 1) path.removeAt(path.lastIndex)
            return TouchStep.BACKED_OUT
        }
        if (!allowVia || (path.size > 1 && path.last() !is TouchTarget.Station)) {
            while (path.size > 1) path.removeAt(path.lastIndex)
        }
        path.add(t)
        return TouchStep.ENTERED
    }

    /** Finger up at [p] (the last position if null). */
    fun up(p: Offset? = finger): TouchOutcome {
        val wasDragging = dragging
        val stops = path.toList()
        val outside = p != null && !boardBox().inflate(4f).contains(p)
        reset()
        if (stops.isEmpty()) return TouchOutcome.Cancelled
        if (!wasDragging) return TouchOutcome.Tap(stops.first())
        if (stops.size < 2 || (releaseOutsideCancels && outside)) return TouchOutcome.Cancelled
        val from = stops.first()
        val to = stops.last()
        if (!routeMakesSense(from, to)) return TouchOutcome.Cancelled
        val via = stops.subList(1, stops.size - 1).mapNotNull { (it as? TouchTarget.Station)?.id }
        return TouchOutcome.Route(from, to, via)
    }

    /** The long-press time passed with the finger still (not dragging). */
    fun longPress(): TouchOutcome? {
        if (dragging || path.isEmpty()) return null
        val t = path.first()
        reset()
        return TouchOutcome.LongPress(t)
    }

    fun cancel() = reset()

    private fun reset() {
        path.clear()
        finger = null
        dragging = false
    }

    /** The union of all targets: the board's tiles area. */
    private fun boardBox(): Rect = bounds.values.reduceOrNull { a, b ->
        Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
    } ?: Rect.Zero

    private fun routeMakesSense(from: TouchTarget, to: TouchTarget): Boolean = when {
        from == to -> false
        // A cell onto a cell (Current location -> from, from -> to, ...) names no station.
        from !is TouchTarget.Station && to !is TouchTarget.Station -> false
        else -> true
    }
}

/** Registers the composable as [target] on [tracker]'s board (its bounds, for hit-testing and the line). */
fun Modifier.touchTarget(board: TouchBoardRegistry, target: TouchTarget): Modifier =
    onGloballyPositioned { board.register(target, it) }

/** Where each target is laid out; the board turns these into bounds in its own coordinates at each gesture start. */
@Stable
class TouchBoardRegistry {
    internal var board: LayoutCoordinates? = null
    private val coords = mutableMapOf<TouchTarget, LayoutCoordinates>()

    fun register(target: TouchTarget, c: LayoutCoordinates) { coords[target] = c }

    fun unregister(target: TouchTarget) { coords.remove(target) }

    /** Every attached target's bounds, relative to the board. */
    fun bounds(): Map<TouchTarget, Rect> {
        val b = board?.takeIf { it.isAttached } ?: return emptyMap()
        return coords.filterValues { it.isAttached }.mapValues { (_, c) -> b.localBoundingBoxOf(c, clipBounds = false) }
    }
}

/** The look of the drag, SBB style: a white line with round ends, a ring on the start, a dot under the finger. */
object TouchGestureStyle {
    val Line = Color.White
    val LineWidth = 4.dp
    val RingRadius = 9.dp
    val DotRadius = 6.dp
    /** Over a tile in the path. */
    val Highlight = Color(0x33FFFFFF)
    /** Under the line, so it stays readable on the bright parts of the map tiles. */
    val Shadow = Color(0x66000000)
}

/**
 * Wraps the tile grid. Give each tile and cell `Modifier.touchTarget(registry, ...)`; taps still go to their own
 * click handlers. A drag from one target onto another calls [onRoute]; a long press calls [onLongPress] (if set).
 *
 * [allowVia] is off by default: the store has no via yet, and a straight swipe across the board would otherwise pick
 * up every tile it crosses and bend the line through them. Off, the line runs straight from the start to the finger
 * and the tile under the finger lights up; on, it runs through each passed tile.
 */
@Composable
fun TouchGestureBoard(
    registry: TouchBoardRegistry,
    onRoute: (TouchOutcome.Route) -> Unit,
    modifier: Modifier = Modifier,
    allowVia: Boolean = false,
    onLongPress: ((TouchTarget) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val routeCb by rememberUpdatedState(onRoute)
    val longCb by rememberUpdatedState(onLongPress)
    val tracker = remember(allowVia) { TouchGestureTracker(slop = 18f, allowVia = allowVia) }
    Box(
        modifier
            .onGloballyPositioned { registry.board = it }
            .pointerInput(tracker) {
                tracker.slop = viewConfiguration.touchSlop
                val longMs = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    tracker.boundsProvider = registry::bounds
                    if (!tracker.down(down.position)) return@awaitEachGesture
                    val id = down.id
                    var held = 0L
                    while (true) {
                        // Until it is a drag, wait at most until the long press would fire.
                        val ev = if (!tracker.dragging && longCb != null) {
                            withTimeoutOrNull(maxOf(1L, longMs - held)) { awaitPointerEvent(PointerEventPass.Initial) }
                        } else awaitPointerEvent(PointerEventPass.Initial)
                        if (ev == null) {
                            val lp = tracker.longPress() as? TouchOutcome.LongPress ?: break
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            longCb?.invoke(lp.target)
                            // Swallow the rest of this touch, so the cell's click does not fire on release.
                            do {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                e.changes.forEach { it.consume() }
                            } while (e.changes.any { it.pressed })
                            break
                        }
                        held = (ev.changes.firstOrNull()?.uptimeMillis ?: 0L) - down.uptimeMillis
                        val ch = ev.changes.firstOrNull { it.id == id }
                        if (ch == null) { tracker.cancel(); break }
                        if (!ch.pressed) {
                            if (tracker.dragging) {
                                ch.consume()
                                when (val out = tracker.up(ch.position)) {
                                    is TouchOutcome.Route -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                        routeCb(out)
                                    }
                                    else -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                }
                            } else tracker.cancel() // a tap: the cell's clickable handles it
                            break
                        }
                        when (tracker.move(ch.position)) {
                            TouchStep.DRAG_STARTED -> haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            TouchStep.ENTERED, TouchStep.BACKED_OUT -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            TouchStep.NONE -> Unit
                        }
                        // Once it is a drag, the board owns the touch: the cells' press state and the scroll let go.
                        if (tracker.dragging) ev.changes.forEach { it.consume() }
                    }
                }
            }
            .drawWithContent {
                drawContent()
                drawTouchGesture(tracker)
            },
    ) { content() }
}

/** The highlight over the tiles in the path, and the line from the start (through any vias) to the finger. */
private fun DrawScope.drawTouchGesture(t: TouchGestureTracker) {
    if (!t.dragging || t.path.isEmpty()) return
    val finger = t.finger ?: return
    val boxes = t.path.mapNotNull { t.boundsOf(it) }
    if (boxes.isEmpty()) return
    for (b in boxes) drawRect(TouchGestureStyle.Highlight, b.topLeft, b.size)
    val start = boxes[0].center
    val corners = if (t.allowVia) boxes.drop(1).map { it.center } else emptyList()
    val line = Path().apply {
        moveTo(start.x, start.y)
        for (c in corners) lineTo(c.x, c.y)
        lineTo(finger.x, finger.y)
    }
    val w = TouchGestureStyle.LineWidth.toPx()
    val ring = TouchGestureStyle.RingRadius.toPx()
    // The line starts at the ring's edge: nothing of it inside the start ring.
    val inside = Path().apply { addOval(androidx.compose.ui.geometry.Rect(start, ring)) }
    clipPath(inside, ClipOp.Difference) {
        drawPath(line, TouchGestureStyle.Shadow, style = Stroke(w + 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(line, TouchGestureStyle.Line, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    drawCircle(TouchGestureStyle.Shadow, ring + 1.dp.toPx(), start)
    drawCircle(TouchGestureStyle.Line, ring, start, style = Stroke(w))
    for (c in corners) drawCircle(TouchGestureStyle.Line, w, c)
    drawCircle(TouchGestureStyle.Line, TouchGestureStyle.DotRadius.toPx(), finger)
}

// ------------------------------------------------------------------ applying a route

/** Where the app goes after [applyTouchRoute]. */
enum class TouchNext { CONNECTIONS, SEARCH_FROM, SEARCH_TO, STAY }

/**
 * Applies a dragged route to the store, the way the tiles and cells already work ([AppStore.pickTile],
 * [AppStore.chooseStation]); [hereId] stands for "Current location" (the demo's fixed position).
 *
 * - tile/Current location -> tile/Current location: that connection, now.
 * - tile -> "from" / "to" cell: the tile fills that field (connections if the other field is set).
 * - "from" cell -> tile: the tile is the destination, then pick the start; "to" cell -> tile: the reverse.
 *
 * Vias are not applied: the store has no via yet (they are in [TouchOutcome.Route.via] for when it does).
 */
fun AppStore.applyTouchRoute(r: TouchOutcome.Route, hereId: String = "epfl"): TouchNext {
    fun id(t: TouchTarget): String? = when (t) {
        is TouchTarget.Station -> t.id
        TouchTarget.CurrentLocation -> hereId
        else -> null
    }
    val from = id(r.from)
    val to = id(r.to)
    return when {
        from != null && to != null -> {
            if (from == to) return TouchNext.STAY
            pushRecent(to)
            update { it.copy(fromId = from, toId = to, searchAt = null) }
            TouchNext.CONNECTIONS
        }
        from != null && r.to == TouchTarget.ToCell -> if (chooseStation(StationField.TO, from)) TouchNext.CONNECTIONS else TouchNext.STAY
        from != null && r.to == TouchTarget.FromCell -> if (chooseStation(StationField.FROM, from)) TouchNext.CONNECTIONS else TouchNext.STAY
        to != null && r.from == TouchTarget.FromCell -> { chooseStation(StationField.TO, to); TouchNext.SEARCH_FROM }
        to != null && r.from == TouchTarget.ToCell -> { chooseStation(StationField.FROM, to); TouchNext.SEARCH_TO }
        else -> TouchNext.STAY
    }
}
