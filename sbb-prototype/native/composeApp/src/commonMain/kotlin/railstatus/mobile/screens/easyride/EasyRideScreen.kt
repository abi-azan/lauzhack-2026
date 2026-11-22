@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.screens.easyride

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.data.DemoTraveller
import railstatus.mobile.incident.PassiveState
import railstatus.mobile.incident.PassiveStatus
import railstatus.mobile.nav.Tab
import railstatus.mobile.resources.p_auto_no_perm
import railstatus.mobile.resources.p_auto_off
import railstatus.mobile.resources.p_auto_title
import railstatus.mobile.resources.p_car_inline
import railstatus.mobile.resources.p_on
import railstatus.mobile.resources.p_detected_car
import railstatus.mobile.screens.profile.NotificationsDestination
import railstatus.mobile.incident.CheckInPhase
import railstatus.mobile.incident.CheckInVia
import railstatus.mobile.incident.Conn
import railstatus.mobile.incident.IncidentUi
import railstatus.mobile.incident.PassengerState
import railstatus.mobile.incident.TrainView
import railstatus.mobile.incident.hhmm
import railstatus.mobile.incident.tr
import railstatus.mobile.nav.Destination
import railstatus.mobile.nav.LocalNavigator
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.p_connecting
import railstatus.mobile.resources.p_er_change
import railstatus.mobile.resources.p_er_class1
import railstatus.mobile.resources.p_er_class2
import railstatus.mobile.resources.p_er_dep
import railstatus.mobile.resources.p_er_travel_time
import railstatus.mobile.resources.p_get_updates
import railstatus.mobile.resources.p_live_on_body
import railstatus.mobile.resources.p_live_paused
import railstatus.mobile.resources.p_look_again
import railstatus.mobile.resources.p_not_on_train
import railstatus.mobile.resources.p_qr_found
import railstatus.mobile.resources.p_qr_hint
import railstatus.mobile.resources.p_saved_body
import railstatus.mobile.resources.p_saved_title
import railstatus.mobile.resources.p_searching
import railstatus.mobile.resources.p_to
import railstatus.mobile.resources.tab_easyride
import railstatus.mobile.screens.incident.IncidentDestination
import railstatus.mobile.ui.chrome.SbbLoader
import railstatus.mobile.ui.chrome.SbbScreen
import railstatus.mobile.ui.components.Landscape
import railstatus.mobile.ui.components.PillButton
import railstatus.mobile.ui.components.PrimaryButton
import railstatus.mobile.ui.components.RoundButton
import railstatus.mobile.ui.components.RoundButtonStyle
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.Scene
import railstatus.mobile.ui.components.sbbClickable
import railstatus.mobile.ui.components.Hairline
import railstatus.mobile.ui.theme.SbbDimens
import railstatus.mobile.ui.theme.SbbTheme
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import railstatus.mobile.screens.incident.IncidentSheet

/**
 * EasyRide tab root, as on the phone: the passenger card on the station scene, then the panel with "Dep:", the class
 * toggle and the "Swipe right to start" slider. Web: `#/easyride`.
 *
 * Our additions sit on the scene between the passenger card and the panel, as SBB-style cards: "On IR 90 1734 · car 7,
 * detected automatically" once the phone recognised the train (passive check-in, `graph.passive`, switched on in
 * Profile › Notifications), and the incident when there is one; before that, the passive check-in's status (a link to
 * its setting) and the saved-trip and QR fallbacks. Check-in is ours and separate from EasyRide's paid journey: the slider only starts
 * and stops EasyRide.
 */
data object EasyRideDestination : Destination {
    @Composable
    override fun Content() = EasyRideScreen()
}

/** EasyRide's own (paid) journey, swiped on and off in the EasyRide tab. Separate from our check-in. */
object EasyRideJourney {
    /** Epoch ms of the swipe, or null when EasyRide isn't running. */
    var startedAt by mutableStateOf<Long?>(null)
}

@Composable
fun EasyRideScreen() {
    val store = passengerStore()
    val s by store.state.state()
    val train by store.train.state()
    val er = erColors()
    val density = LocalDensity.current
    val riding = EasyRideJourney.startedAt != null
    val checkedIn = s.phase == CheckInPhase.CHECKED_IN
    var busy by remember { mutableStateOf(false) }
    var cls by rememberSaveable { mutableIntStateOf(2) }
    var panelH by remember { mutableIntStateOf(0) }
    val now by rememberNow(if (riding) 1000 else 30_000)

    // The swipe: SBB's circle spins for about a second, then EasyRide runs (or stops).
    LaunchedEffect(busy) {
        if (!busy) return@LaunchedEffect
        delay(900)
        EasyRideJourney.startedAt = if (riding) null else store.clock().toEpochMilliseconds()
        busy = false
    }

    SbbScreen(title = stringResource(Res.string.tab_easyride)) {
        Box(Modifier.fillMaxSize()) {
            // As in the real app (measured on the same Pixel): the scene is full-bleed, from right under the header
            // down to the panel (and 16 dp under it, so it shows in the panel's rounded corners). The red band runs
            // on 24 dp under the header, behind the passenger card's top; the cards float on the scene.
            val sceneBottom = with(density) { panelH.toDp() } - 16.dp
            Box(Modifier.fillMaxSize().padding(bottom = sceneBottom.coerceAtLeast(0.dp))) {
                // On the train (riding or checked in): the train interior, cross-faded over 600 ms.
                val inside by animateFloatAsState(if (riding || checkedIn) 1f else 0f, tween(600, easing = LinearEasing))
                if (inside < 1f) Landscape(Scene.EasyRide, Modifier.graphicsLayer { alpha = 1f - inside })
                if (inside > 0f) Landscape(Scene.EasyRideCheckedIn, Modifier.graphicsLayer { alpha = inside })
            }
            Box(Modifier.fillMaxWidth().height(SbbDimens.Band).background(SbbTheme.colors.red))
            Column(Modifier.fillMaxSize()) {
                // The passenger card at the top, as SBB has it; our cards at the bottom, just above the panel, so the
                // scene's focal point (the woman with her phone) stays visible between them. Scrolls when they don't fit.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        PassengerCard()
                        OurCards(s, train)
                    }
                }
                // The panel.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { panelH = it.height }
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .background(er.panel)
                        .padding(start = 16.dp, end = 16.dp, bottom = SbbDimens.Under + 2.5.dp),
                ) {
                    AnimatedContent(
                        riding,
                        transitionSpec = { fadeIn(tween(200, 330)) togetherWith fadeOut(tween(80)) using SizeTransform(clip = true) { _, _ -> tween(250, 80) } },
                        label = "fold",
                    ) { run ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                            if (run) {
                                val since = EasyRideJourney.startedAt ?: now.toEpochMilliseconds()
                                SbbText("${stringResource(Res.string.p_er_travel_time)}  ${travelClock(now.toEpochMilliseconds() - since)}", style = SbbTheme.type.segment)
                                SbbText(stringResource(if (cls == 1) Res.string.p_er_class1 else Res.string.p_er_class2), style = SbbTheme.type.small, color = SbbTheme.colors.meta)
                            } else {
                                val dep = train.depStop(s.checkedInAt?.let(Instant::fromEpochMilliseconds) ?: now)
                                DepRow(dep.station)
                                ClassToggle(cls) { cls = it }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PayCard()
                        EasyRideSlider(riding, busy, onStart = { busy = true }, onStop = { busy = true }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** "03:48": minutes and seconds since the swipe, as SBB counts it (hours in front after an hour). */
private fun travelClock(ms: Long): String {
    val s = maxOf(0L, ms / 1000)
    fun p(n: Long) = n.toString().padStart(2, '0')
    val h = s / 3600
    return "${if (h > 0) "$h:" else ""}${p(s / 60 % 60)}:${p(s % 60)}"
}

/** Passenger card (measured): 80.4 dp, 16 dp padding, the 16 sp bold name over the dog and bicycle counts, the pen. */
@Composable
private fun PassengerCard() {
    val c = SbbTheme.colors
    SbbCard {
        Row(Modifier.fillMaxWidth().height(80.4.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                SbbText(DemoTraveller.NAME, style = SbbTheme.type.bodyBold.copy(lineHeight = SbbTheme.type.segment.lineHeight), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.2.dp).height(22.1.dp), horizontalArrangement = Arrangement.spacedBy(13.5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Count("dog-small")
                    Count("bicycle-small")
                }
            }
            RoundButton("pen-small", "Edit passenger", onClick = {}, style = RoundButtonStyle.SheetClose, iconSize = 24.dp)
        }
    }
}

/** "0 × [dog]": SBB draws the "×" as its own small glyph (6.9 dp). */
@Composable
private fun Count(icon: String) {
    val meta = SbbTheme.colors.meta
    Row(verticalAlignment = Alignment.CenterVertically) {
        SbbText("0", style = SbbTheme.type.segment, color = meta)
        Canvas(Modifier.padding(start = 2.dp, top = 4.dp).size(7.dp)) {
            val w = size.width
            drawLine(meta, Offset(0.5f / 7 * w, 0.5f / 7 * w), Offset(6.5f / 7 * w, 6.5f / 7 * w), 1.dp.toPx())
            drawLine(meta, Offset(6.5f / 7 * w, 0.5f / 7 * w), Offset(0.5f / 7 * w, 6.5f / 7 * w), 1.dp.toPx())
        }
        SbbIcon(icon, Modifier.padding(start = 1.5.dp).offset(y = (-2).dp), size = 23.4.dp, tint = meta)
    }
}

@Composable
private fun DepRow(station: String) {
    val er = erColors()
    val dep = stringResource(Res.string.p_er_dep)
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SbbText(
            buildAnnotatedString { append("$dep "); withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(station) } },
            Modifier.weight(1f), style = SbbTheme.type.segment, maxLines = 1,
        )
        PillButton(
            stringResource(Res.string.p_er_change), onClick = {}, horizontalPadding = 15.2.dp, border = er.btnEdge,
            background = SbbTheme.colors.card, textStyle = SbbTheme.type.body,
        )
    }
}

/**
 * Class toggle (measured): 44.2 dp; the selected half is a full-height pill with its own complete ring. The track's
 * ring is drawn behind the halves (a `border` on the row would paint over the selected ring's outer edges).
 */
@Composable
private fun ClassToggle(cls: Int, onChange: (Int) -> Unit) {
    val er = erColors()
    val pill = RoundedCornerShape(percent = 50)
    Row(
        Modifier.padding(top = 16.dp).fillMaxWidth().height(44.2.dp)
            .drawBehind {
                val stroke = 1.14.dp.toPx()
                val r = CornerRadius(size.height / 2)
                drawRoundRect(er.seg, cornerRadius = r)
                drawRoundRect(er.segEdge, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), CornerRadius(size.height / 2 - stroke / 2), Stroke(stroke))
            },
    ) {
        for (k in listOf(2, 1)) {
            val on = cls == k
            Box(
                Modifier.weight(1f).fillMaxSize().clip(pill)
                    .then(if (on) Modifier.background(er.thumb).border(1.14.dp, er.thumbEdge, pill) else Modifier)
                    .sbbClickable(pill) { onChange(k) },
                contentAlignment = Alignment.Center,
            ) {
                SbbText(stringResource(if (k == 1) Res.string.p_er_class1 else Res.string.p_er_class2), Modifier.padding(top = 1.dp), style = SbbTheme.type.segment)
            }
        }
    }
}

// ---------- ours: the train we're on (passive check-in), its incident, the fallbacks ----------

/**
 * EasyRide is SBB's own page: ours adds nothing to it, except confirming a QR check-in link that was just opened.
 * Passive check-in runs in the background (switched in Profile > Notifications); a disruption shows as the warning
 * band on every tab and in the push.
 */
@Composable
private fun OurCards(s: PassengerState, train: TrainView) {
    if (s.phase == CheckInPhase.FOUND && s.via == CheckInVia.QR) QrFoundCard(s, train)
}

/** Card text block: 16 dp padding, a 16 sp bold title on a 22.1 line, then 14 sp meta lines. */
@Composable
private fun CardBlock(
    title: String?,
    body: String?,
    icon: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    top: (@Composable () -> Unit)? = null,
    bottom: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        icon?.invoke()
        Column(Modifier.weight(1f)) {
            top?.invoke()
            if (title != null) SbbText(title, style = SbbTheme.type.bodyBold.copy(lineHeight = SbbTheme.type.segment.lineHeight))
            if (body != null) MetaText(body, Modifier.padding(top = 2.dp))
            bottom?.invoke()
        }
        trailing?.invoke(this)
    }
}

/**
 * Passive check-in, reduced to its status and a link to the setting (Profile › Notifications), where it's switched
 * on and explained. Watching: "We recognise your train as soon as you board", with the loader while a scan runs.
 * Paused (the passenger said "Not on this train?"): "Look for my train". The QR code is always the way out.
 */
@Composable
private fun AutoDetectCard(passive: PassiveState, searching: Boolean) {
    val nav = LocalNavigator.current
    val graph = LocalAppGraph.current
    // While paused, the loop still scans now and then (to notice the train has gone): keep showing "paused" through
    // those scans instead of flashing the loader.
    var settled by remember { mutableStateOf(passive.status) }
    if (passive.status != PassiveStatus.SCANNING) settled = passive.status
    val paused = passive.optedIn && settled == PassiveStatus.PAUSED
    val status = when {
        !passive.optedIn -> stringResource(Res.string.p_auto_off)
        passive.status == PassiveStatus.NO_PERMISSION -> stringResource(Res.string.p_auto_no_perm)
        paused -> stringResource(Res.string.p_live_paused)
        else -> stringResource(Res.string.p_live_on_body)
    }
    SbbCard(onClick = { nav.open(Tab.Profile, NotificationsDestination) }) {
        CardBlock(
            stringResource(Res.string.p_auto_title),
            status + "\n" + stringResource(Res.string.p_qr_hint),
            icon = { SbbIcon("train-small", size = 24.dp) },
            // 12 dp from the card's edge, like the incident card's and the chat entry's chevrons.
            trailing = { SbbIcon("chevron-small-right-small", Modifier.align(Alignment.CenterVertically).offset(x = 4.dp), size = 24.dp) },
            bottom = {
                if (paused) {
                    PillButton(stringResource(Res.string.p_look_again), onClick = { graph.passive.resume() }, modifier = Modifier.padding(top = 12.dp))
                } else if (passive.optedIn && searching) {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SbbLoader()
                        MetaText(stringResource(Res.string.p_searching))
                    }
                }
            },
        )
    }
}

/** The saved-trip fallback: "Are you on IR 90 1734?" */
@Composable
private fun SavedTripCard(train: TrainView) {
    val store = passengerStore()
    SbbCard {
        CardBlock(
            stringResource(Res.string.p_saved_title, trainName(train)),
            stringResource(Res.string.p_saved_body, train.direction),
            icon = { SbbIcon("star-small", size = 24.dp) },
            bottom = {
                PillButton(stringResource(Res.string.p_get_updates), onClick = {
                    // The store has no "saved trip" check-in; the QR path is the same (train known, no beacon).
                    SavedTripCheckIn.trainId = train.id
                    store.foundByQr(train.id, null)
                    store.checkIn()
                }, modifier = Modifier.padding(top = 12.dp))
            },
        )
    }
}

@Composable
private fun QrFoundCard(s: PassengerState, train: TrainView) {
    val store = passengerStore()
    val connecting = s.conn == Conn.CONNECTING
    SbbCard {
        CardBlock(
            null, stringResource(Res.string.p_qr_found, s.car ?: "–"),
            top = { TrainTitle(train) },
            bottom = {
                if (s.error != null && !connecting) MetaText(s.error, Modifier.padding(top = 8.dp), color = SbbTheme.colors.changed)
                Box(Modifier.padding(top = 16.dp)) {
                    PrimaryButton(stringResource(if (connecting) Res.string.p_connecting else Res.string.p_get_updates), onClick = { store.checkIn() }, enabled = !connecting)
                }
            },
        )
    }
}

/** "[IR 90] 1734 to Brig" */
@Composable
private fun TrainTitle(train: TrainView) {
    Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TrainLine(train)
        SbbText(" " + stringResource(Res.string.p_to, train.direction), style = SbbTheme.type.body, color = SbbTheme.colors.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Checked in: "[IR 90] 1734", then how we know ("Car 7, detected automatically"). While the train has a disruption,
 * SBB's disruption row sits inside the same card and opens the Information page. "Not on this train?" checks out;
 * passive check-in then leaves this train alone until its beacon is out of range.
 */
@Composable
private fun OnTrainCard(s: PassengerState, train: TrainView, incident: railstatus.mobile.incident.Incident?) {
    val store = passengerStore()
    val graph = LocalAppGraph.current
    val how = if (s.via == CheckInVia.BEACON && s.car != null) stringResource(Res.string.p_detected_car, s.car)
    else stringResource(checkInSource(s))
    SbbCard {
        Column {
            CardBlock(
                null,
                how,
                icon = { SbbIcon(if (s.via == CheckInVia.BEACON) "circle-tick-small" else "train-small", size = 24.dp) },
                top = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LineBadge(train.product, train.line)
                        SbbText(train.number, style = SbbTheme.type.bodyBold, maxLines = 1)
                    }
                },
            )
            incident?.let { inc ->
                Hairline(Modifier.padding(start = 52.dp))
                DisruptionRow(inc, onClick = { IncidentSheet.open(inc.trainId) })
            }
            // Pause first (it reads the train we're on), so passive check-in doesn't take them straight back.
            PillButton(
                stringResource(Res.string.p_not_on_train),
                onClick = { graph.passive.pause(); store.checkOut() },
                modifier = Modifier.padding(start = 52.dp, top = if (incident == null) 0.dp else 12.dp, bottom = 16.dp),
            )
        }
    }
}

/** SBB's disruption row: the red status icon, the title, a chevron. Nothing else; the details are one tap away. */
@Composable
private fun DisruptionRow(incident: railstatus.mobile.incident.Incident, onClick: () -> Unit) {
    val lang = passengerStore().state.value.lang
    Row(
        Modifier.fillMaxWidth().sbbClickable(RoundedCornerShape(0.dp)) { onClick() }
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { StatusIcon(statusIconFor(incident), size = 20.dp) }
        SbbText(tr(incident.latest.title, lang), Modifier.weight(1f), style = SbbTheme.type.bodyBold.copy(lineHeight = SbbTheme.type.segment.lineHeight))
        SbbIcon("chevron-small-right-small", size = 24.dp)
    }
}

/** The incident on their train, in SBB's disruption-row look: the red status icon, the title, when we know more. */
@Composable
internal fun IncidentCard(incident: railstatus.mobile.incident.Incident, onClick: () -> Unit) {
    val store = passengerStore()
    val lang = store.state.value.lang
    val u = incident.latest
    SbbCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // A 24 dp slot like the other cards' icons, so the text column starts at the same x.
            Box(Modifier.width(24.dp).padding(top = 1.dp), contentAlignment = Alignment.TopCenter) {
                StatusIcon(statusIconFor(incident), size = 20.dp)
            }
            Column(Modifier.weight(1f)) {
                MetaText("${stageText(incident.stage)} · ${hhmm(u.at, store.zone)}")
                SbbText(tr(u.title, lang), Modifier.padding(top = 2.dp), style = SbbTheme.type.bodyBold.copy(lineHeight = SbbTheme.type.segment.lineHeight))
                u.nextBy?.let { MetaText("${tr(IncidentUi.nextBy, lang)} ${hhmm(it, store.zone)}", Modifier.padding(top = 4.dp)) }
            }
            SbbIcon("chevron-small-right-small", Modifier.align(Alignment.CenterVertically), size = 24.dp)
        }
    }
}
