@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.screens.plan

import railstatus.mobile.nav.LocalNavigator
import railstatus.mobile.ui.components.PillButton
import railstatus.mobile.resources.plan_follow_train
import railstatus.mobile.screens.station.StationDestination
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import railstatus.mobile.resources.plan_notify_on
import railstatus.mobile.resources.plan_notify_me
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.data.approxDelayLabel
import railstatus.mobile.data.getConnection
import railstatus.mobile.data.hhmm
import railstatus.mobile.data.isPassed
import railstatus.mobile.data.shownTime
import railstatus.mobile.nav.Destination
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.plan_no_journey_info
import railstatus.mobile.resources.seg_journey_info
import railstatus.mobile.resources.seg_train_formation
import railstatus.mobile.resources.title_journey_info
import railstatus.mobile.ui.chrome.SbbScreen
import railstatus.mobile.ui.chrome.ScreenColumn
import railstatus.mobile.ui.chrome.Segmented
import railstatus.mobile.ui.chrome.SegmentedPage
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.theme.SbbTheme
import kotlin.time.ExperimentalTime

/** Journey information (stops) | Train formation for one leg. Web: `#/plan/c/<id>/leg/<n>` (`?tab=formation`). */
/** [fromBoard]: opened from a departure board row; then it offers "Follow this train" (the station flow, T13). */
data class JourneyDestination(val connectionId: String, val leg: Int, val formation: Boolean = false, val fromBoard: Boolean = false) : Destination {
    @Composable
    override fun Content() = JourneyScreen(connectionId, leg, formation, fromBoard)
}

/** "Journey information": the train's stops, and its formation on the second tab. */
@Composable
fun JourneyScreen(connectionId: String, leg: Int, formationFirst: Boolean = false, fromBoard: Boolean = false) {
    var tab by rememberSaveable { mutableIntStateOf(if (formationFirst) 1 else 0) }
    val nav = LocalNavigator.current
    val conn = remember(connectionId) { getConnection(connectionId) }
    val l = conn?.legs?.getOrNull(leg)
    val t = rememberNow()
    val c = SbbTheme.colors
    SbbScreen(
        title = stringResource(Res.string.title_journey_info),
        showBack = true,
        headerContent = { Segmented(listOf(stringResource(Res.string.seg_journey_info), stringResource(Res.string.seg_train_formation)), tab, { tab = it }) },
    ) {
        SegmentedPage(tab) {
            ScreenColumn {
                if (l == null) {
                    SbbCard {
                        SbbText(
                            stringResource(Res.string.plan_no_journey_info),
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp),
                            style = planStyle(16f), color = c.text2, align = TextAlign.Center,
                        )
                    }
                    return@ScreenColumn
                }
                // Train card: 13 / 17.5 / 14 dp padding (94 dp tall, as in the real app), badge row, direction, attributes.
                SbbCard(Modifier.padding(start = 17.5.dp, end = 17.5.dp, top = 13.dp, bottom = 14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                            ModeIcon(l.mode, 22.dp)
                            LegBadge(l, 23.dp, Modifier.padding(end = 6.dp))
                            SbbText(l.number ?: "", style = planStyle(16f, FontWeight.Bold), maxLines = 1)
                        }
                        SbbText(directionLabel(l.direction), style = planStyle(14f, lh = 21f))
                        Attrs(l.attrs, 13.dp, Modifier.height(16.dp))
                    }
                }
                if (tab == 0) {
                    SbbCard(Modifier.padding(start = 18.6.dp, end = 17.5.dp)) {
                        l.stops.forEachIndexed { i, s ->
                            val first = i == 0
                            val lastStop = i == l.stops.lastIndex
                            val endpoint = first || lastStop
                            val delay = s.delay?.takeIf { it != 0 }
                            Box(Modifier.fillMaxWidth().clipToBounds().alpha(if (s.isPassed(t)) 0.55f else 1f)) {
                                // Stops 107 dp apart, the first 6 dp higher than the others' inset (real app, [69]).
                                Column(Modifier.padding(top = if (first) 13.7.dp else 16.2.dp, bottom = 16.2.dp)) {
                                    val t21 = planStyle(16f, lh = 21f)
                                    // The arrival above the bold departure, at stops passed through (the terminus shows it once, bold).
                                    if (s.arr != null && i > 0 && s.dep != null) SbbText(hhmm(s.arr), Modifier.height(21.dp), style = t21)
                                    if (s.arr != null && i > 0 && delay != null) SbbText(approxDelayLabel(delay), style = t21, color = c.late)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.width(59.dp)) { SbbText(hhmm(s.shownTime), style = planStyle(16f, FontWeight.Bold), maxLines = 1, softWrap = false) }
                                        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                                            Box(
                                                Modifier
                                                    .size(8.dp)
                                                    .drawBehind {
                                                        // The cord: 1.5 dp, up and down from the dot, clipped by the row.
                                                        val w = 1.5.dp.toPx()
                                                        val long = 400.dp.toPx()
                                                        if (!first) drawRect(c.text, Offset((size.width - w) / 2, -long), Size(w, long))
                                                        if (!lastStop) drawRect(c.text, Offset((size.width - w) / 2, size.height), Size(w, long))
                                                    }
                                                    .clip(CircleShape)
                                                    .background(if (endpoint) c.text else c.card)
                                                    .border(1.5.dp, c.text, CircleShape),
                                            )
                                        }
                                        SbbText(
                                            s.station.name,
                                            Modifier.weight(1f).padding(vertical = 4.2.dp).padding(start = 13.dp).height(24.dp),
                                            style = planStyle(16f, if (first) FontWeight.Bold else FontWeight.Light, lh = 24f),
                                            maxLines = 1,
                                        )
                                        if (!s.platform.isNullOrEmpty()) SbbText(
                                            platformLabel(s.platform),
                                            style = planStyle(16f, if (first) FontWeight.Bold else FontWeight.Light),
                                            color = if (s.platformChanged) c.changed else c.text,
                                            maxLines = 1, softWrap = false,
                                        )
                                    }
                                    if (delay != null || s.occupancy != null) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.width(59.dp)) {
                                                if (delay != null) SbbText(approxDelayLabel(delay), style = planStyle(16f, FontWeight.Bold, lh = 21f), color = c.late, maxLines = 1, softWrap = false)
                                            }
                                            Box(Modifier.width(18.dp))
                                            s.occupancy?.let { OccPair(it, 14.dp, Modifier.padding(start = 13.dp).height(21.dp)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // From a departure board, for a train the status service knows: SBB's "notify me" for a trip you
                    // are waiting for. One tap; its updates then arrive as for a passenger on board.
                    if (fromBoard) NotifyMeButton(l)
                } else {
                    l.formation?.let { coaches ->
                        FormationDirection(l.direction ?: "", Modifier.height(38.5.dp).padding(horizontal = 19.dp))
                        SbbCard(Modifier.padding(top = 14.dp, bottom = 16.dp)) { Formation(coaches) }
                    }
                    Legend(listOf(l), withAttrs = false, formation = true)
                }
            }
        }
    }
}

/**
 * "Notify me about this train" (bell) under a departure's journey, only when the live service knows the train. It
 * follows the train from this board's station; then it reads "Notifications on".
 */
@Composable
private fun NotifyMeButton(l: railstatus.mobile.data.Leg) {
    val store = railstatus.mobile.screens.easyride.passengerStore()
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var target by remember(l.id) { mutableStateOf<Pair<String, String>?>(null) } // train id, boarding station id
    LaunchedEffect(s.api, l.id) {
        target = null
        if (s.api == null) return@LaunchedEffect
        val digits = l.line?.filter { it.isDigit() } ?: return@LaunchedEffect
        val product = l.product?.code ?: return@LaunchedEffect
        val from = l.stops.firstOrNull()?.station?.name ?: return@LaunchedEffect
        target = store.trains().firstOrNull { tr ->
            tr.product.equals(product, ignoreCase = true) && tr.line.filter { it.isDigit() } == digits &&
                (tr.number == l.number || tr.direction == l.direction)
        }?.let { tr -> tr.stops.firstOrNull { it.station.name == from }?.let { st -> tr.id to st.station.id } }
    }
    val (trainId, boardingId) = target ?: return
    val on = s.live?.train?.id == trainId
    Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        PillButton(
            stringResource(if (on) Res.string.plan_notify_on else Res.string.plan_notify_me),
            onClick = { if (!on) scope.launch { store.follow(trainId, boardingId, null).join() } },
            icon = "bell-small",
        )
    }
}
