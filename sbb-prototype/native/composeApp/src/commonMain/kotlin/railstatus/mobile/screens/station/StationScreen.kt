@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.screens.station

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.incident.hhmm
import railstatus.mobile.incident.mapTrain
import railstatus.mobile.nav.Destination
import railstatus.mobile.nav.LocalNavigator
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.st_departures
import railstatus.mobile.resources.st_follow
import railstatus.mobile.resources.st_get_off
import railstatus.mobile.resources.st_in_min
import railstatus.mobile.resources.st_intro
import railstatus.mobile.resources.st_loading
import railstatus.mobile.resources.st_none
import railstatus.mobile.resources.st_not_sure
import railstatus.mobile.resources.st_now
import railstatus.mobile.resources.st_offline
import railstatus.mobile.resources.st_platform
import railstatus.mobile.resources.st_retry
import railstatus.mobile.resources.st_unreachable
import railstatus.mobile.resources.st_title
import railstatus.mobile.resources.st_where
import railstatus.mobile.screens.easyride.LineBadge
import railstatus.mobile.screens.easyride.passengerStore
import railstatus.mobile.screens.easyride.rememberNow
import railstatus.mobile.screens.easyride.state
import railstatus.mobile.screens.incident.IncidentDestination
import railstatus.mobile.ui.chrome.SbbScreen
import railstatus.mobile.ui.chrome.ScreenColumn
import railstatus.mobile.ui.components.Hairline
import railstatus.mobile.ui.components.PillButton
import railstatus.mobile.ui.components.PrimaryButton
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.sbbClickable
import railstatus.mobile.ui.theme.SbbTheme
import railstatus.model.Station
import railstatus.model.Train
import kotlin.time.ExperimentalTime
import railstatus.mobile.screens.incident.IncidentSheet

/**
 * "At the station": the passenger on the platform, not on a train yet (the brief's opening scene). They pick
 * where they are and the departure they're waiting for, and follow it. From then on they get the same updates,
 * incident page and chat as a passenger on board; the server's next move speaks to someone still on the platform
 * (`CheckIn.boardingId`).
 */
data object StationDestination : Destination {
    @Composable
    override fun Content() = StationScreen()
}

/** The station picked first: where the 17 Sep demo's IR 90 is awaited from the closed section. */
private const val DEFAULT_STATION = "Lausanne"

/** Departures that left up to this many minutes ago are still shown (a late passenger, a late train). */
private const val JUST_LEFT_MIN = 2

private data class Departure(val train: Train, val stopIdx: Int, val dep: String, val inMin: Int)

@Composable
fun StationScreen() {
    val store = passengerStore()
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val s by store.state.state()
    val now by rememberNow(15_000)
    val c = SbbTheme.colors
    val t = SbbTheme.type

    var trains by remember { mutableStateOf<List<Train>?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(s.api, attempt) { trains = null; trains = store.trains() }

    val stations: List<Station> = remember(trains) {
        trains.orEmpty().flatMap { tr -> tr.stops.filter { it.dep != null }.map { it.station } }.distinctBy { it.id }
    }
    var stationId by rememberSaveable { mutableStateOf<String?>(null) }
    val station = stations.firstOrNull { it.id == stationId }
        ?: stations.firstOrNull { it.name == DEFAULT_STATION }
        ?: stations.firstOrNull()
    var pickedTrain by rememberSaveable { mutableStateOf<String?>(null) }
    var destId by rememberSaveable { mutableStateOf<String?>(null) }

    val nowMin = minutesOf(hhmm(now, store.zone)) ?: 0
    val departures = station?.let { st ->
        trains.orEmpty().mapNotNull { tr ->
            val i = tr.stops.indexOfFirst { it.station.id == st.id && it.dep != null }
            if (i < 0) null else tr.stops[i].dep!!.let { d -> minutesOf(d)?.let { Departure(tr, i, d, rel(it, nowMin)) } }
        }.filter { it.inMin >= -JUST_LEFT_MIN }.sortedBy { it.inMin }
    }.orEmpty()

    SbbScreen(title = stringResource(Res.string.st_title), showBack = true) {
        ScreenColumn {
            // No backend configured (the offline demo): departures need the live service.
            if (!store.isLive) {
                SbbCard { SbbText(stringResource(Res.string.st_offline), Modifier.padding(16.dp), style = t.body, color = c.text2) }
                return@ScreenColumn
            }
            // Live, but the request failed (tunnel down, no network): say so, and offer a retry.
            if (trains != null && trains!!.isEmpty()) {
                SbbCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        SbbText(stringResource(Res.string.st_unreachable), style = t.body, color = c.text2)
                        PillButton(stringResource(Res.string.st_retry), onClick = { attempt++ }, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                return@ScreenColumn
            }
            if (trains == null || station == null) {
                SbbCard { SbbText(stringResource(Res.string.st_loading), Modifier.padding(16.dp), style = t.body, color = c.text2) }
                return@ScreenColumn
            }

            // Where are you?
            SbbCard {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                    // The intro lives in the first card: plain text here would sit on the red header band.
                    SbbText(stringResource(Res.string.st_intro), Modifier.padding(horizontal = 16.dp), style = t.body)
                    SbbText(stringResource(Res.string.st_where), Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), style = t.bodyBold)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        stations.forEach { st ->
                            val on = st.id == station.id
                            PillButton(
                                st.name,
                                onClick = { stationId = st.id; pickedTrain = null; destId = null },
                                border = if (on) c.red else c.outline,
                                background = if (on) c.card2 else androidx.compose.ui.graphics.Color.Transparent,
                            )
                        }
                    }
                }
            }

            // A follow that didn't reach the server (CHECK_IN_ERROR).
            s.error?.let { err ->
                SbbCard(Modifier.padding(top = 8.dp)) { SbbText(err, Modifier.padding(16.dp), style = t.body, color = c.red) }
            }

            // Departures from here.
            SbbCard(Modifier.padding(top = 8.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SbbText(
                        stringResource(Res.string.st_departures, station.name),
                        Modifier.padding(16.dp),
                        style = t.bodyBold,
                    )
                    if (departures.isEmpty()) {
                        SbbText(stringResource(Res.string.st_none), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), style = t.body, color = c.text2)
                    }
                    departures.forEach { d ->
                        val view = mapTrain(d.train, now, store.zone)
                        val selected = d.train.id == pickedTrain
                        Hairline()
                        Row(
                            Modifier.fillMaxWidth()
                                .sbbClickable { pickedTrain = if (selected) null else d.train.id; destId = null }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            SbbText(d.dep, Modifier.width(48.dp), style = t.bodyBold)
                            LineBadge(view.product, view.line)
                            Column(Modifier.weight(1f)) {
                                SbbText(d.train.direction, style = t.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                SbbText(
                                    if (d.inMin <= 0) stringResource(Res.string.st_now) else stringResource(Res.string.st_in_min, d.inMin.toString()),
                                    style = t.small, color = c.text2,
                                )
                            }
                            d.train.stops[d.stopIdx].platform?.let { SbbText(stringResource(Res.string.st_platform, it), style = t.small, color = c.text2) }
                        }
                        if (selected) FollowPanel(
                            later = d.train.stops.drop(d.stopIdx + 1).map { it.station },
                            destId = destId,
                            onDest = { destId = it },
                            onFollow = {
                                // Open the incident page only once the server has the follow; on failure, stay
                                // here and show the error (below) instead of a page about the wrong train.
                                scope.launch {
                                    store.follow(d.train.id, station.id, destId).join()
                                    if (store.state.value.live?.train?.id == d.train.id) IncidentSheet.open(d.train.id)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Under the picked departure: where do you get off (optional), then follow it. */
@Composable
private fun FollowPanel(later: List<Station>, destId: String?, onDest: (String?) -> Unit, onFollow: () -> Unit) {
    val c = SbbTheme.colors
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        SbbText(stringResource(Res.string.st_get_off), style = SbbTheme.type.small, color = c.text2, weight = FontWeight.Bold)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillButton(
                stringResource(Res.string.st_not_sure), onClick = { onDest(null) },
                border = if (destId == null) c.red else c.outline,
            )
            later.forEach { st ->
                PillButton(st.name, onClick = { onDest(st.id) }, border = if (destId == st.id) c.red else c.outline)
            }
        }
        PrimaryButton(stringResource(Res.string.st_follow), onClick = onFollow, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
    }
}

private fun minutesOf(s: String): Int? {
    val m = Regex("""^(\d{1,2}):(\d{2})$""").find(s.trim()) ?: return null
    return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
}

/** [t] relative to [now] in minutes, in −12 h … +12 h (times just past midnight count as later). */
private fun rel(t: Int, now: Int): Int = ((t - now + 720) % 1440 + 1440) % 1440 - 720
