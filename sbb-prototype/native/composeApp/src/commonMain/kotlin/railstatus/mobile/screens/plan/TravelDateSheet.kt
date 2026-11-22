@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.screens.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.data.AppClock
import railstatus.mobile.data.addMin
import railstatus.mobile.data.findConnections
import railstatus.mobile.data.findStation
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.plan_apply
import railstatus.mobile.resources.plan_arrival
import railstatus.mobile.resources.plan_departure
import railstatus.mobile.resources.plan_today
import railstatus.mobile.resources.plan_travel_date
import railstatus.mobile.ui.chrome.SbbSheet
import railstatus.mobile.ui.chrome.Segmented
import railstatus.mobile.ui.chrome.SegmentedStyle
import railstatus.mobile.ui.components.PrimaryButton
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.theme.SbbTheme
import kotlin.math.abs
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Days the date wheel offers around today. */
private const val DAYS_BACK = 7
private const val DAYS_AHEAD = 60

/** Rows of a wheel (measured: 224 dp tall, five 44.8 dp rows, the middle one selected). */
private val ROW = 44.8.dp
private const val VISIBLE = 5

/** Hour and minute wheels wrap; they are long lists started in the middle. */
private const val LOOPS = 200

/**
 * "Travel date" (measured from SBB Mobile, 12-datetime-picker): a Departure | Arrival segmented control, a card with
 * the date, hour and minute wheels (5-minute steps; 20 sp light, the neighbours fading out, 1.9 dp dividers around
 * the selection), and Apply. Arrival picks the last connection that arrives by then.
 */
@Composable
internal fun TravelDateSheet(onDismiss: () -> Unit) {
    val app = LocalAppGraph.current.app
    val s by app.state.collectAsState()
    val zone = AppClock.zone
    val now = rememberNow()
    val start = s.searchAt ?: now
    val local = remember { start.toLocalDateTime(zone) }
    val today = remember { now.toLocalDateTime(zone).date }
    var arrival by remember { mutableIntStateOf(0) }
    val dayState = rememberLazyListState(DAYS_BACK + (local.date.toEpochDays() - today.toEpochDays()).toInt().coerceIn(-DAYS_BACK, DAYS_AHEAD))
    val hourState = rememberLazyListState(24 * (LOOPS / 2) + local.hour)
    val minState = rememberLazyListState(12 * (LOOPS / 2) + local.minute / 5)
    val c = SbbTheme.colors
    val divider = if (c.isDark) Color(0xFF424242) else Color(0xFFE6E6E6)

    SbbSheet(title = stringResource(Res.string.plan_travel_date), onDismiss = onDismiss) {
        Segmented(
            listOf(stringResource(Res.string.plan_departure), stringResource(Res.string.plan_arrival)),
            arrival, { arrival = it },
            Modifier.padding(horizontal = 8.dp),
            style = SegmentedStyle.Sheet,
        )
        Box(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .fillMaxWidth()
                .height(ROW * VISIBLE)
                .clip(RoundedCornerShape(16.dp))
                .background(c.card)
                .padding(horizontal = 16.dp),
        ) {
            Row(Modifier.fillMaxSize()) {
                val todayLabel = stringResource(Res.string.plan_today)
                Wheel(dayState, DAYS_BACK + DAYS_AHEAD + 1, Modifier.weight(173.7f), divider) { i ->
                    val d = today.plus(i - DAYS_BACK, DateTimeUnit.DAY)
                    if (i == DAYS_BACK) todayLabel else wheelDayLabel(LocalDateTime(d.year, d.month, d.day, 12, 0).toInstant(zone))
                }
                Box(Modifier.width(16.dp))
                Wheel(hourState, 24 * LOOPS, Modifier.weight(70.9f), divider) { i -> (i % 24).toString() }
                Box(Modifier.width(16.dp).fillMaxSize(), contentAlignment = Alignment.Center) {
                    SbbText(":", Modifier.offset(y = 2.dp), style = planStyle(20f), maxLines = 1)
                }
                Wheel(minState, 12 * LOOPS, Modifier.weight(70.8f), divider) { i -> ((i % 12) * 5).toString().padStart(2, '0') }
            }
        }
        PrimaryButton(
            stringResource(Res.string.plan_apply),
            onClick = {
                val day = today.plus(dayState.firstVisibleItemIndexCentered() - DAYS_BACK, DateTimeUnit.DAY)
                val at = LocalDateTime(day.year, day.month, day.day, hourState.firstVisibleItemIndexCentered() % 24, (minState.firstVisibleItemIndexCentered() % 12) * 5)
                    .toInstant(zone)
                app.update { it.copy(searchAt = if (arrival == 1) departureForArrival(it.fromId, it.toId, at, zone) else at) }
                onDismiss()
            },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
    }
}

/** For "Arrival": the departure of the last connection that arrives by [at] (or [at] itself if none does). */
private fun departureForArrival(fromId: String?, toId: String?, at: Instant, zone: TimeZone): Instant {
    val from = findStation(fromId) ?: return at
    val to = findStation(toId) ?: return at
    return findConnections(from, to, addMin(at, -4 * 60), 16, zone).filter { it.arr <= at }.maxByOrNull { it.dep }?.dep ?: at
}

/** With two rows of padding above, the first visible item is the selected one once the list has snapped. */
private fun LazyListState.firstVisibleItemIndexCentered(): Int =
    firstVisibleItemIndex + if (firstVisibleItemScrollOffset > 0 && layoutInfo.visibleItemsInfo.firstOrNull()?.size?.let { firstVisibleItemScrollOffset > it / 2 } == true) 1 else 0

/** One wheel: snapping rows, the selected one between two dividers; rows fade out with their distance (1, .8, .27). */
@Composable
private fun Wheel(state: LazyListState, count: Int, modifier: Modifier, divider: Color, label: @Composable (Int) -> String) {
    val c = SbbTheme.colors
    val density = LocalDensity.current
    val rowPx = with(density) { ROW.toPx() }
    val center by remember { derivedStateOf { state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset / rowPx } }
    Box(
        modifier.fillMaxSize().drawBehind {
            val h = 1.9.dp.toPx()
            val top = rowPx * 2
            drawRect(divider, Offset(0f, top - h / 2), Size(size.width, h))
            drawRect(divider, Offset(0f, top + rowPx - h / 2), Size(size.width, h))
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(vertical = ROW * 2),
            flingBehavior = rememberSnapFlingBehavior(state),
        ) {
            items(count) { i ->
                val d = abs(i - center)
                val alpha = when {
                    d <= 1f -> 1f - 0.2f * d
                    d <= 2f -> 0.8f - 0.53f * (d - 1f)
                    else -> 0.27f
                }
                Box(Modifier.fillMaxWidth().height(ROW).graphicsLayer { this.alpha = alpha }, contentAlignment = Alignment.Center) {
                    SbbText(label(i), style = planStyle(20f), color = c.text, align = TextAlign.Center, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
