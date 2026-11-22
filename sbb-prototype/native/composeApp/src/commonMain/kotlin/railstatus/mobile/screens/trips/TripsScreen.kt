@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.screens.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import railstatus.mobile.data.Disruption
import railstatus.mobile.screens.plan.StatusIcon
import railstatus.mobile.screens.plan.planStyle
import railstatus.mobile.screens.plan.pullUp
import railstatus.mobile.ui.components.sbbClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.data.AppClock
import railstatus.mobile.data.Connection
import railstatus.mobile.data.DemoTrip
import railstatus.mobile.data.allDisruptions
import railstatus.mobile.data.isIncident
import railstatus.mobile.data.dayLabel
import railstatus.mobile.data.hhmm
import railstatus.mobile.data.isActive
import railstatus.mobile.data.now
import railstatus.mobile.data.nowFlow
import railstatus.mobile.data.savedTrips
import railstatus.mobile.data.shortDate
import railstatus.mobile.nav.Destination
import railstatus.mobile.nav.LocalNavigator
import railstatus.mobile.nav.Tab
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.btn_get_started
import railstatus.mobile.resources.btn_plan_journey
import railstatus.mobile.resources.btn_set_up_notifications
import railstatus.mobile.resources.commuting_body
import railstatus.mobile.resources.commuting_title
import railstatus.mobile.resources.seg_commuting
import railstatus.mobile.resources.seg_single_trips
import railstatus.mobile.resources.tab_trips
import railstatus.mobile.resources.trips_departure_at
import railstatus.mobile.resources.trips_empty_body
import railstatus.mobile.resources.trips_empty_title
import railstatus.mobile.resources.trips_journey_active
import railstatus.mobile.resources.trips_today
import railstatus.mobile.resources.trips_tomorrow
import railstatus.mobile.screens.incident.IncidentDestination
import railstatus.mobile.screens.plan.ConnectionDestination
import railstatus.mobile.screens.profile.NotificationsDestination
import railstatus.mobile.ui.chrome.SbbScreen
import railstatus.mobile.ui.chrome.Segmented
import railstatus.mobile.ui.chrome.SegmentedPage
import railstatus.mobile.ui.components.Landscape
import railstatus.mobile.ui.components.NoteCard
import railstatus.mobile.ui.components.PillButton
import railstatus.mobile.ui.components.PrimaryButton
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.Scene
import railstatus.mobile.ui.theme.SbbDimens
import railstatus.mobile.ui.theme.SbbTheme
import railstatus.mobile.screens.incident.IncidentSheet

/** Trips tab root: Single Trips | Commuting, on the SBB scenes, with the saved trips. Web: `#/trips`, `#/trips/commuting`. */
data object TripsDestination : Destination {
    @Composable
    override fun Content() = TripsScreen()
}

/** The CTA dock's height: 8 dp above the 44.2 dp button and 2.3 dp below it (the web's `.cta-dock`). */
private val CtaDockHeight = 8.dp + SbbDimens.PrimaryButtonHeight + 2.3.dp

@Composable
fun TripsScreen() {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val nav = LocalNavigator.current
    val app by LocalAppGraph.current.app.state.collectAsState()
    val t by remember { nowFlow() }.collectAsState(now())
    val passenger = LocalAppGraph.current.passenger
    // The checked-in trip rebuilds with the train's times (DemoTripSync keeps it current; null: not checked in).
    val demoLive by DemoTrip.live.collectAsState()
    val demoDisruption by DemoTrip.disruption.collectAsState()
    val trips = remember(app.saved, t, demoLive, demoDisruption) { savedTrips(app.saved, t) }
    SbbScreen(
        title = stringResource(Res.string.tab_trips),
        headerContent = {
            Segmented(listOf(stringResource(Res.string.seg_single_trips), stringResource(Res.string.seg_commuting)), page, { page = it })
        },
    ) {
        SegmentedPage(page) {
            val commuting = page == 1
            val scroll = rememberScrollState()
            val ptr = rememberPullToRefresh()
            // The web's `.content.illustrated`: the scene fills the viewport and scrolls with the content (it is
            // positioned in the scroller); the body scrolls, and the CTA stays at the bottom, `Under` above the
            // viewport's bottom (`position: sticky` there).
            Box(Modifier.fillMaxSize()) {
                // Placed as SBB Mobile places it (Scene's fits, measured on the same Pixel), in a box from the header down.
                Landscape(if (commuting) Scene.Commuting else Scene.Trips, Modifier.graphicsLayer { translationY = -scroll.value.toFloat() })
                Column(
                    Modifier
                        .fillMaxSize()
                        .pullToRefresh(ptr)
                        .verticalScroll(scroll)
                        .padding(top = 16.dp, bottom = SbbDimens.Under + CtaDockHeight),
                ) {
                    when {
                        commuting -> NoteCard(stringResource(Res.string.commuting_title), stringResource(Res.string.commuting_body))
                        trips.isNotEmpty() -> {
                            trips.forEach { c ->
                                TripCard(
                                    c, t,
                                    // Trips opens the travel companion; the banner opens the incident page.
                                    onClick = { nav.push(ConnectionDestination(c.id, fromTrips = true, map = true)) },
                                    onBanner = { (passenger.incident.value?.trainId ?: demoLive?.trainId)?.let(IncidentSheet::open) },
                                )
                            }
                            // `.pill-btn.left`: 8 dp in, 8 dp below the card's own 8 dp gap (inline margins don't collapse).
                            PillButton(
                                stringResource(Res.string.btn_set_up_notifications),
                                onClick = { nav.open(Tab.Profile, NotificationsDestination) },
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                            )
                        }
                        else -> NoteCard(stringResource(Res.string.trips_empty_title), stringResource(Res.string.trips_empty_body))
                    }
                }
                PullToRefreshIndicator(ptr)
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = SbbDimens.Under)
                        .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.3.dp),
                ) {
                    PrimaryButton(
                        stringResource(if (commuting) Res.string.btn_get_started else Res.string.btn_plan_journey),
                        onClick = { if (!commuting) nav.selectTab(Tab.Plan) },
                    )
                }
            }
        }
    }
}

/**
 * A saved trip (web `.trip-card`): a 78 dp card, 16 dp side padding; "From → To" in 16 sp roman (bold while the
 * journey is active) over a 14 sp meta line ("Today, Departure at 07:05", or a tick and "Journey is active"), and a
 * 22 dp chevron 12 dp after the text. While its train has an open incident, SBB's red disruption banner hangs under
 * it (as under the companion's step cards, [r05]): its top tucked 16 dp under the card; tapping it calls [onBanner].
 */
@Composable
private fun TripCard(c: Connection, now: Instant, onClick: () -> Unit, onBanner: () -> Unit) {
    val active = c.isActive(now)
    val title = SbbTheme.type.bodyRoman.copy(lineHeight = 22.1.sp)
    val incident = c.allDisruptions().firstOrNull { it.isIncident }
    Column(Modifier.padding(start = SbbDimens.Gutter, end = SbbDimens.Gutter, bottom = 8.dp)) {
        SbbCard(Modifier.zIndex(1f), onClick = onClick, gutter = false) {
            Row(
                Modifier.fillMaxWidth().height(78.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f).offset(y = 1.1.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val w = if (active) FontWeight.Bold else null
                        SbbText(c.from.name, Modifier.weight(1f, fill = false), style = title, weight = w, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TripArrow(Modifier.size(width = 14.dp, height = 22.1.dp))
                        SbbText(c.to.name, Modifier.weight(1f, fill = false), style = title, weight = w, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(
                        Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val sub = SbbTheme.type.small
                        if (active) {
                            SbbIcon("circle-tick-small", size = 19.dp, tint = SbbTheme.colors.meta)
                            SbbText(stringResource(Res.string.trips_journey_active), style = sub, color = SbbTheme.colors.meta, maxLines = 1)
                        } else {
                            SbbText(
                                stringResource(Res.string.trips_departure_at, tripDay(c.dep, now), hhmm(c.dep)),
                                style = sub,
                                color = SbbTheme.colors.meta,
                                maxLines = 1,
                            )
                        }
                    }
                }
                SbbIcon("chevron-small-right-small", size = 22.dp)
            }
        }
        if (incident != null) IncidentBanner(incident, onBanner)
    }
}

/**
 * The thin "→" between the stations, drawn: the SBB web fonts have no U+2192, so the web build would show a missing
 * glyph (Android fell back to the system font). 14 × 1.1 dp shaft, 4 dp head, on the text's x-height centre.
 */
@Composable
private fun TripArrow(modifier: Modifier) {
    val color = SbbTheme.colors.text
    Canvas(modifier) {
        val w = 1.1.dp.toPx()
        val y = size.height * 0.56f
        val head = 4.dp.toPx()
        drawLine(color, Offset(0f, y), Offset(size.width, y), w)
        drawLine(color, Offset(size.width - head, y - head), Offset(size.width, y), w, StrokeCap.Round)
        drawLine(color, Offset(size.width - head, y + head), Offset(size.width, y), w, StrokeCap.Round)
    }
}

/** The red banner (the companion's, [r05]): ringed status icon, 14 sp bold white lines, chevron; 16 dp under the card. */
@Composable
private fun IncidentBanner(d: Disruption, onClick: () -> Unit) {
    val shape = RoundedCornerShape(bottomStart = SbbDimens.Radius, bottomEnd = SbbDimens.Radius)
    Row(
        Modifier
            .pullUp(16.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(SbbTheme.colors.red)
            .sbbClickable(shape, onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusIcon(d.kind, 16.dp, Modifier.padding(top = 2.dp), ring = true)
        Column(Modifier.weight(1f)) {
            val st = planStyle(14f, FontWeight.Bold, lh = 19.6f)
            SbbText(d.summary, style = st, color = Color.White)
            d.cause?.let { SbbText(it, style = st, color = Color.White) }
        }
        SbbIcon("chevron-small-right-small", Modifier.align(Alignment.CenterVertically), size = 22.dp, tint = Color.White)
    }
}

/** "Today", "Tomorrow" (translated), else the short date ([dayLabel]'s logic). */
@Composable
private fun tripDay(d: Instant, ref: Instant): String = when (val label = dayLabel(d, ref)) {
    "Today" -> stringResource(Res.string.trips_today)
    "Tomorrow" -> stringResource(Res.string.trips_tomorrow)
    else -> label.ifEmpty { shortDate(d, AppClock.zone) }
}
