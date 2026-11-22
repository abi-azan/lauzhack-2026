@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.screens.incident

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import railstatus.mobile.incident.CheckInPhase
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.resources.p_not_on_train
import railstatus.mobile.ui.components.PillButton
import railstatus.mobile.incident.stationName
import railstatus.mobile.incident.NotificationPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.incident.ForYou
import railstatus.mobile.incident.Incident
import railstatus.mobile.incident.PublishedUpdate
import railstatus.mobile.incident.forYou
import railstatus.mobile.incident.hhmm
import railstatus.mobile.incident.tr
import railstatus.mobile.nav.Destination
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.inc_band_disruption
import railstatus.mobile.resources.inc_band_title
import railstatus.mobile.resources.inc_earlier
import railstatus.mobile.resources.inc_following
import railstatus.mobile.resources.inc_from_sbb
import railstatus.mobile.resources.inc_next_by
import railstatus.mobile.resources.inc_none
import railstatus.mobile.resources.inc_onboard
import railstatus.mobile.resources.inc_reason_unknown
import railstatus.mobile.resources.inc_since
import railstatus.mobile.resources.inc_your_trip
import railstatus.mobile.resources.plan_information
import railstatus.mobile.resources.title_your_train
import railstatus.mobile.screens.easyride.AskQuestionButton
import railstatus.mobile.screens.easyride.NextMoveContent
import railstatus.mobile.screens.easyride.StatusIcon
import railstatus.mobile.screens.easyride.warningColor
import railstatus.mobile.screens.easyride.TrainLine
import railstatus.mobile.screens.easyride.passengerStore
import railstatus.mobile.screens.easyride.rememberNow
import railstatus.mobile.screens.easyride.stageText
import railstatus.mobile.screens.easyride.state
import railstatus.mobile.screens.easyride.statusIconFor
import railstatus.mobile.ui.chrome.SbbScreen
import railstatus.mobile.ui.chrome.SbbSheet
import railstatus.mobile.ui.chrome.ScreenColumn
import railstatus.mobile.ui.components.Hairline
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.sbbClickable
import railstatus.mobile.ui.theme.SbbTheme
import railstatus.model.Alternative
import railstatus.model.Lang
import railstatus.model.Stage
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The incident opens like SBB's own disruption details ([08]/[r06] in docs/sbb-mobile-teardown): the "Information"
 * sheet over whatever screen you're on, not a page of its own. Every way in (red banner, heads-up, notification,
 * EasyRide card, deep link) calls [IncidentSheet.open]; [IncidentSheetHost] is mounted once by the app shell.
 */
object IncidentSheet {
    var trainId: String? by mutableStateOf(null)
        private set

    fun open(trainId: String) {
        this.trainId = trainId
    }

    fun close() {
        trainId = null
    }
}

@Composable
fun IncidentSheetHost() {
    val id = IncidentSheet.trainId ?: return
    SbbSheet(title = stringResource(Res.string.plan_information), onDismiss = IncidentSheet::close, tall = true) {
        // The sheet scrolls itself; a second vertical scroll in here can't be measured and the sheet never shows.
        IncidentContent(id)
    }
}

/** Kept for old deep links and tests: the same content as a screen. New code opens [IncidentSheet]. */
data class IncidentDestination(val trainId: String) : Destination {
    @Composable
    override fun Content() = IncidentScreen(trainId)
}

@Composable
fun IncidentScreen(trainId: String) {
    SbbScreen(title = stringResource(Res.string.title_your_train), showBack = true) {
        ScreenColumn { IncidentContent(trainId) }
    }
}

/**
 * UX-SPEC P3 / P5, in SBB's language: the BS-KI title on the red band ("Interrupted service: Renens VD – Allaman"),
 * then your trip, then "Information" (the latest published update, BS-KI wording for "further information at …"),
 * then our suggestion in a neutral card marked as an estimate, then earlier updates, then the outlined
 * "Ask a question" pill. Everything comes from [railstatus.mobile.incident.PassengerStore]; no logic here.
 */
@Composable
fun ColumnScope.IncidentContent(trainId: String) {
    val store = passengerStore()
    val s by store.state.state()
    // The store only ever holds this passenger's incident; matching on its first train would hide it from an IC 1
    // passenger on a multi-train incident (review N6).
    val incident = store.incident.state().value
    val train by store.train.state()
    val person by store.person.state()
    val move by store.nextMove.state()
    val now by rememberNow(15_000)
    val lang = s.lang
    val zone = store.zone
    val c = SbbTheme.colors
    val t = SbbTheme.type

    if (incident == null) {
        SbbCard {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { TrainLine(train) }
            SbbText(stringResource(Res.string.inc_none), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), style = t.body, color = c.meta)
        }
        return
    }

    val latest = incident.latest
    val resolved = incident.stage == Stage.RESOLVED

    // 1. What's happening, in the industry's standard words.
    StageBand(incident, resolved, zone)

    // 2. Does this affect my trip?
    SectionLabel(stringResource(Res.string.inc_your_trip))
    SbbCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            TrainLine(train)
            s.boardingId?.let { b -> train.stops.firstOrNull { it.stationId == b }?.station }?.let { from ->
                SbbText(stringResource(Res.string.inc_following, from), Modifier.padding(top = 6.dp), style = t.small, color = c.meta)
            }
            // The arrival line (bold) and what the ticket allows (T18 #4: shown even before a delay is published, so the
            // passenger knows they may take the backup). After "resolved" both are stale, so leave them out.
            // The arrival as SBB shows a stop: time, red delay, station. Then what the ticket allows (T18 #4: shown even
            // before a delay is published, so the passenger knows they may take the backup). Stale after "resolved".
            if (!resolved) forYou(latest, person.persona, person.dest, train, zone).forEach {
                when (it) {
                    is ForYou.Arrival -> {
                        val stop = train.stops.firstOrNull { st -> st.station == person.dest }
                        val d = latest.delay
                        if (stop != null && d != null) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                            SbbText(hhmm(stop.planned, zone), style = t.bodyBold)
                            SbbText(" +${if (d.low == d.high) "${d.high}" else "${d.low}–${d.high}"}", style = t.bodyBold, color = c.red)
                            SbbText("  " + stationName(stop.station, lang), Modifier.weight(1f), style = t.body, maxLines = 1)
                        }
                    }
                    is ForYou.TicketLine -> Unit // shown with an alternative, in the advice below
                    ForYou.Ask -> Unit
                }
            }
            // Our advice for this journey, part of "Your trip" (not a card of its own next to SBB's).
            if (!resolved) move?.let { m ->
                val ticket = forYou(latest, person.persona, person.dest, train, zone).filterIsInstance<ForYou.TicketLine>().firstOrNull()
                NextMoveContent(m, Modifier.padding(top = 12.dp), lang = lang, now = now, zone = zone, ticketLine = ticket?.let { tr(it.text, lang) })
            }
            // Detected on the wrong train: check out here (EasyRide is SBB's own page now). Passive check-in then
            // leaves this train alone until its beacon is out of range.
            if (!resolved && s.phase == CheckInPhase.CHECKED_IN && s.boardingId == null) {
                val graph = LocalAppGraph.current
                PillButton(
                    stringResource(Res.string.p_not_on_train),
                    onClick = { graph.passive.pause(); store.checkOut(); IncidentSheet.close() },
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }

    // 3. The official word: SBB's "Information".
    val facts = s.live?.incident?.takeIf { it.id == incident.id }
    SectionLabel(stringResource(Res.string.inc_from_sbb))
    OfficialCard(
        latest,
        alternatives = facts?.alternatives ?: latest.alternatives.orEmpty(),
        onboard = facts?.onboard ?: latest.onboard,
        resolved = resolved, lang = lang, zone = zone,
    )


    // Earlier updates, newest first, collapsed.
    val earlier = incident.updates.dropLast(1).asReversed()
    if (earlier.isNotEmpty()) EarlierUpdates(earlier, lang, zone)

    // The way into the chat: SBB's outlined pill, not a filled red button (filled red is a screen's one main action).
    if (!resolved) AskQuestionButton(Modifier.padding(top = 20.dp, bottom = 8.dp))
}

/** Red band: "Interrupted service: Renens VD – Allaman" / "since 17:22 · The reason is not yet known." Resolved: a plain card. */
@Composable
private fun StageBand(incident: Incident, resolved: Boolean, zone: TimeZone) {
    val since = incident.updates.firstOrNull()?.at ?: incident.latest.at
    val title = if (resolved) stageText(Stage.RESOLVED)
    else if (incident.section.isNotBlank()) stringResource(Res.string.inc_band_title, incident.section)
    else stringResource(Res.string.inc_band_disruption)
    // One quiet line under the title: since when (or, resolved, when it ended). The cause is in SBB's text below.
    val sub = if (resolved) hhmm(incident.latest.at, zone) else stringResource(Res.string.inc_since, hhmm(since, zone))
    // The same yellow and black as the band on top of every tab: one disruption, one colour. Red stays SBB's
    // realtime colour (delays, the status squares).
    val ink = if (resolved) SbbTheme.colors.text else Color.Black
    SbbCard(color = if (resolved) SbbTheme.colors.card else warningColor()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (resolved) StatusIcon(statusIconFor(incident), size = 20.dp)
            else SbbIcon("circle-exclamation-point-small", size = 20.dp, tint = Color.Black)
            Column {
                SbbText(title, style = SbbTheme.type.bodyBold, color = ink)
                sub?.let { SbbText(it, Modifier.padding(top = 2.dp), style = SbbTheme.type.small, color = ink) }
            }
        }
    }
}

/** "Information": the latest update as published, as SBB's info sheet sets it (16 sp bold over 16 sp light). */
@Composable
private fun OfficialCard(
    u: PublishedUpdate,
    alternatives: List<Alternative>,
    onboard: railstatus.model.Text?,
    resolved: Boolean,
    lang: Lang,
    zone: TimeZone,
) {
    val c = SbbTheme.colors
    val t = SbbTheme.type
    SbbCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                SbbText(tr(u.title, lang), Modifier.weight(1f), style = t.bodyBold)
                SbbText(hhmm(u.at, zone), Modifier.padding(start = 12.dp), style = t.meta, color = c.meta)
            }
            // The text's own "further information at …" goes: an AI draft can copy an older time (it did, 09:01 vs 09:02).
            // The page adds the update's own time below, once.
            val body = if (resolved) tr(u.body, lang) else NotificationPolicy.withoutNextUpdate(tr(u.body, lang))
            SbbText(body, Modifier.padding(top = 4.dp), style = t.body)

            // Alternatives as sentences, like the standard texts ("Zwischen X und Y fahren Ersatzbusse."). Not after "resolved".
            if (!resolved) alternatives.forEach { a -> SbbText(tr(a.text, lang), Modifier.padding(top = 8.dp), style = t.body) }
            if (!resolved) onboard?.let { ob ->
                SbbText(stringResource(Res.string.inc_onboard), Modifier.padding(top = 12.dp), style = t.bodyBold)
                SbbText(tr(ob, lang), Modifier.padding(top = 2.dp), style = t.body)
            }

            // "Further information at approximately 17:45.": always the update's own time, once.
            if (!resolved) u.nextBy?.let { next ->
                Hairline(Modifier.padding(top = 14.dp, bottom = 10.dp))
                SbbText(stringResource(Res.string.inc_next_by, hhmm(next, zone)), style = t.body)
            }
        }
    }
}

/** "Earlier updates (n)", collapsed; each row: stage · time, title, text. */
@Composable
private fun EarlierUpdates(updates: List<PublishedUpdate>, lang: Lang, zone: TimeZone) {
    var open by rememberSaveable { mutableStateOf(false) }
    val c = SbbTheme.colors
    val t = SbbTheme.type
    // SbbCard puts its modifier inside the card, so the gap above it is a spacer, not padding (padding centred the row low).
    Spacer(Modifier.height(8.dp))
    SbbCard {
        Row(
            Modifier.fillMaxWidth().sbbClickable { open = !open }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SbbText(stringResource(Res.string.inc_earlier, updates.size.toString()), Modifier.weight(1f), style = t.bodyBold)
            // The icon set has only the up chevron; turned 180° it points down (collapsed).
            SbbIcon("chevron-small-up-small", Modifier.rotate(if (open) 0f else 180f), size = 24.dp)
        }
        if (open) updates.forEach { u ->
            Hairline()
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                SbbText(hhmm(u.at, zone), style = t.meta, color = c.meta)
                SbbText(tr(u.title, lang), Modifier.padding(top = 4.dp), style = t.bodyBold)
                SbbText(tr(u.body, lang), Modifier.padding(top = 4.dp), style = t.small, color = c.meta)
            }
        }
    }
}

/** A section title above a card, as SBB sets them: normal case, light, grey; never all caps. */
@Composable
private fun SectionLabel(text: String) {
    SbbText(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp), style = SbbTheme.type.small, color = SbbTheme.colors.meta)
}
