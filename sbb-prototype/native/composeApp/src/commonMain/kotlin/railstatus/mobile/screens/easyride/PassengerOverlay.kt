package railstatus.mobile.screens.easyride

import railstatus.mobile.ui.components.TextEntry
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.incident.CheckInPhase
import railstatus.mobile.incident.PublishedUpdate
import railstatus.mobile.incident.hhmm
import railstatus.mobile.incident.tr
import railstatus.mobile.nav.LocalNavigator
import railstatus.mobile.nav.Tab
import railstatus.mobile.screens.incident.IncidentDestination
import railstatus.mobile.ui.components.CardShape
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.sbbClickable
import railstatus.mobile.ui.theme.SbbPalette
import railstatus.mobile.ui.theme.SbbTheme
import kotlin.math.roundToInt
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import railstatus.model.Stage
import railstatus.mobile.screens.incident.IncidentSheet

// What the passenger side adds on top of every screen: the in-app heads-up for a new update, the incident strip
// under the status bar, the tab bar's ride indicators and the demo keys. Passive check-in itself runs in the app graph
// (`graph.passive`), not here. App.kt passes [PassengerOverlay] and [IncidentStrip] to
// SbbApp's `overlay` and `banner`.

/** Drawn over everything (SbbApp `overlay`). */
@Composable
fun PassengerOverlay() {
    HeadsUpBanner()
    DemoKeys()
}

/**
 * The in-app heads-up for a new update (HeadsUp.shown): a card that drops in under the status bar, with SBB's red
 * status icon, the train, the stage and the time, the update's title and what it means for this passenger. Tap it
 * for the incident page; swipe it up to dismiss. It hides itself after 8 s.
 */
@Composable
private fun HeadsUpBanner() {
    val graph = LocalAppGraph.current
    val headsUp = graph.headsUp
    val shown by headsUp.shown.state()
    // Keep the last update while the banner animates out.
    var last by remember { mutableStateOf<PublishedUpdate?>(null) }
    if (shown != null) last = shown
    val density = LocalDensity.current
    val top = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    AnimatedVisibility(
        visible = shown != null,
        enter = slideInVertically(tween(300)) { -it * 2 },
        exit = slideOutVertically(tween(200)) { -it * 2 } + fadeOut(tween(200)),
        modifier = Modifier.padding(top = top + 8.dp, start = 8.dp, end = 8.dp),
    ) {
        last?.let { HeadsUpCard(it) }
    }
}

@Composable
private fun HeadsUpCard(u: PublishedUpdate) {
    val graph = LocalAppGraph.current
    val store = graph.passenger
    val nav = LocalNavigator.current
    val incident by store.incident.state()
    val train by store.train.state()
    val lang = store.state.value.lang
    val c = SbbTheme.colors
    var dy by remember { mutableFloatStateOf(0f) }
    val notice = remember(u) { incident?.let { graph.headsUp.noticeFor(it, u) } }
    Row(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, dy.roundToInt().coerceAtMost(0)) }
            .draggable(
                rememberDraggableState { dy += it },
                Orientation.Vertical,
                onDragStopped = { if (dy < -40f) graph.headsUp.dismiss(); dy = 0f },
            )
            .shadow(12.dp, CardShape)
            .background(c.card, CardShape)
            .sbbClickable(CardShape) {
                graph.headsUp.dismiss()
                IncidentSheet.open(incident?.trainId ?: train.id)
            }
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusIcon(incident?.let(::statusIconFor) ?: "cancellation", size = 20.dp, modifier = Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f)) {
            MetaText("${trainName(train)} · ${stageText(u.stage)} · ${hhmm(u.at, store.zone)}", maxLines = 1)
            SbbText(
                tr(u.title, lang), Modifier.padding(top = 2.dp),
                style = SbbTheme.type.bodyBold.copy(lineHeight = SbbTheme.type.segment.lineHeight), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            SbbText(
                notice?.body ?: tr(u.body, lang), Modifier.padding(top = 2.dp),
                style = SbbTheme.type.small, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
        SbbIcon("chevron-small-right-small", Modifier.align(Alignment.CenterVertically), size = 24.dp)
    }
}

/** SBB's warning colour (Lyne `--sbb-color-warning` = peach): #FCBB00 light, #FFC727 dark; black text and icon on it. */
@Composable
internal fun warningColor(): Color = if (SbbTheme.colors.isDark) Color(0xFFFFC727) else Color(0xFFFCBB00)

/**
 * Strip at the top while the passenger's train has an open incident (SbbApp `banner`): SBB's warning band (Lyne
 * `sbb-alert` pattern) in peach with black ink: the circle exclamation icon, the train in bold, the latest title,
 * a chevron; a tap opens the incident page. Not on EasyRide's root (it shows the incident itself) or the incident
 * page, and gone once the incident is resolved. The status bar above it turns peach too, with dark icons, so the
 * whole top reads as one warning (compared on the phone against a peach band under the red status bar: that one
 * read as a stray stripe between two reds).
 */
@Composable
fun IncidentStrip() {
    val store = passengerStore()
    val nav = LocalNavigator.current
    val s by store.state.state()
    val incident by store.incident.state()
    val train by store.train.state()
    val inc = incident
    // Stays while the Information sheet is open (it sits behind it), so nothing jumps when the sheet opens or closes.
    val onIncidentPage = nav.current.destination is IncidentDestination
    // On every tab, EasyRide too: EasyRide is SBB's own page again and shows nothing of ours.
    // On every tab. The incident is shown only here (and on its Information page): it adds no red SBB banners to the
    // saved trip, the companion or the connection, so there is one warning, not two.
    val shown = s.phase == CheckInPhase.CHECKED_IN && inc != null && inc.stage != Stage.RESOLVED && !onIncidentPage
    DarkStatusBarIcons(shown)
    if (!shown || inc == null) return
    val peach = warningColor()
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
    Row(
        Modifier
            .fillMaxWidth()
            // Paint the status-bar band above the strip too (SbbApp draws it red just above us).
            .drawBehind { drawRect(peach, Offset(0f, -statusTop), Size(size.width, statusTop + 1f)) }
            .background(peach)
            .sbbClickable(overlay = Color(0x1F000000)) { IncidentSheet.open(inc.trainId) }
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SbbIcon("circle-exclamation-point-small", size = 20.dp, tint = Color.Black)
        // Just SBB's title of the latest update; the train is theirs, so it needs no naming.
        SbbText(
            tr(inc.latest.title, s.lang),
            Modifier.weight(1f), style = SbbTheme.type.small, weight = FontWeight.Bold, color = Color.Black, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        SbbIcon("chevron-small-right-small", size = 22.dp, tint = Color.Black)
    }
}

/**
 * Keeps the tab bar in step with the ride: the tick on Tickets and EasyRide's switch on while checked in or while an
 * EasyRide journey runs, as SBB Mobile shows them during an EasyRide journey.
 */
/**
 * Demo keys, as in the web app's desktop frame: c = check in now, n = send the next scripted update (fake mode),
 * r = restart the demo (also forgets the passive opt-in, so the demo starts from P0), p = next traveller. They act only while no text field has focus.
 */
@Composable
private fun DemoKeys() {
    val graph = LocalAppGraph.current
    val store = graph.passenger
    val nav = LocalNavigator.current
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // Take the keys back when something else had them (a closed sheet, a tap), except from a text field: the chat's,
    // or any screen or sheet with one ([TextEntry]: station search, the departure-board and touch-timetable editors).
    LaunchedEffect(focused, nav.current, nav.tab, chatSheetOpen, TextEntry.active) {
        if (focused || chatSheetOpen || TextEntry.active) return@LaunchedEffect
        delay(300)
        runCatching { focus.requestFocus() }
    }
    Box(
        Modifier.size(1.dp).focusRequester(focus).onFocusChanged { focused = it.isFocused }.focusable().onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (e.utf16CodePoint.toChar()) {
                'c' -> { store.quickCheckIn(); nav.open(Tab.EasyRide); true }
                'n' -> { if (!store.isLive) store.publishNext(); true }
                'r' -> { graph.passive.optOut(); SavedTripCheckIn.trainId = null; EasyRideJourney.startedAt = null; store.restartDemo(); nav.open(Tab.EasyRide); true }
                'p' -> { store.nextPersona(); true }
                else -> false
            }
        },
    )
}
