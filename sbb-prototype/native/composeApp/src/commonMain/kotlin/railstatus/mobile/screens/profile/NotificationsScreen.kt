package railstatus.mobile.screens.profile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.incident.CheckInPhase
import railstatus.mobile.incident.CheckInVia
import railstatus.mobile.incident.PassiveStatus
import railstatus.mobile.nav.Destination
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.notif_commuting_body
import railstatus.mobile.resources.notif_detect_body
import railstatus.mobile.resources.notif_detect_denied
import railstatus.mobile.resources.notif_detect_lead
import railstatus.mobile.resources.notif_detect_no_perm
import railstatus.mobile.resources.notif_detect_now
import railstatus.mobile.resources.notif_detect_sheet_title
import railstatus.mobile.resources.notif_detect_title
import railstatus.mobile.resources.notif_not_now
import railstatus.mobile.resources.notif_reason_bt_body
import railstatus.mobile.resources.notif_reason_bt_title
import railstatus.mobile.resources.notif_reason_notif_body
import railstatus.mobile.resources.notif_reason_notif_title
import railstatus.mobile.resources.notif_train_group
import railstatus.mobile.resources.notif_train_section
import railstatus.mobile.resources.notif_turn_on
import railstatus.mobile.resources.notif_disruption_body
import railstatus.mobile.resources.notif_disruption_title
import railstatus.mobile.resources.notif_saved_journeys
import railstatus.mobile.resources.notif_transfer_body
import railstatus.mobile.resources.notif_transfer_title
import railstatus.mobile.resources.notifications
import railstatus.mobile.resources.seg_commuting
import railstatus.mobile.resources.title_notifications
import railstatus.mobile.ui.chrome.SbbScreen
import railstatus.mobile.ui.chrome.SbbSheet
import railstatus.mobile.ui.chrome.ScreenColumn
import railstatus.mobile.ui.components.AccountCard
import railstatus.mobile.ui.components.Hairline
import railstatus.mobile.ui.components.ListCard
import railstatus.mobile.ui.components.PrimaryButton
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.SectionHeader
import railstatus.mobile.ui.components.sbbClickable
import railstatus.mobile.ui.theme.SbbTheme
import railstatus.mobile.ui.theme.sbbStyle

/** Profile > Notifications settings. Web: `#/profile/notifications`. */
data object NotificationsDestination : Destination {
    @Composable
    override fun Content() = NotificationsScreen()
}

/**
 * Laid out as on the phone (19e): the account card on the red band, bold 16 sp section titles, and a card per group
 * with a 14 sp bold heading and switch rows. Ours, in the same look, comes first: "On the train" with the passive
 * check-in switch (spec P0). "Service disruption and transfer information" includes the plain
 * disruption information, so while it is on, the first switch shows on and greyed out, as in SBB Mobile.
 */
@Composable
fun NotificationsScreen() {
    var disruptions by rememberSaveable { mutableStateOf(true) }
    var transfers by rememberSaveable { mutableStateOf(true) }
    var commuting by rememberSaveable { mutableStateOf(false) }
    SbbScreen(title = stringResource(Res.string.title_notifications), showBack = true) {
        ScreenColumn {
            AccountCard()
            TrainDetection()
            SectionHeader(stringResource(Res.string.notif_saved_journeys), strong = true, top = 8.dp)
            Spacer(Modifier.height(8.dp))
            SbbCard {
                GroupHead(stringResource(Res.string.notifications))
                ToggleRow(
                    stringResource(Res.string.notif_disruption_title),
                    stringResource(Res.string.notif_disruption_body),
                    on = disruptions || transfers,
                    enabled = !transfers,
                    divider = false,
                ) { disruptions = it }
                ToggleRow(stringResource(Res.string.notif_transfer_title), stringResource(Res.string.notif_transfer_body), transfers) { transfers = it }
            }
            SectionHeader(stringResource(Res.string.seg_commuting), strong = true, top = 8.dp)
            Spacer(Modifier.height(8.dp))
            SbbCard {
                GroupHead(stringResource(Res.string.seg_commuting))
                ToggleRow(
                    stringResource(Res.string.notif_disruption_title),
                    stringResource(Res.string.notif_commuting_body),
                    commuting,
                    divider = false,
                ) { commuting = it }
            }
        }
    }
}

/**
 * "Detect my train automatically (Bluetooth)": the passive check-in's opt-in (`graph.passive`, persisted there).
 * Switching it on the first time explains what it does in a sheet; its "Turn on" asks for Bluetooth and
 * notifications. Off forgets the opt-in (a check-in that's running stays). While on, the row says which train the
 * phone recognised, or that the Bluetooth permission was taken away (a tap asks again).
 */
@Composable
private fun TrainDetection() {
    val graph = LocalAppGraph.current
    val passive by graph.passive.state.collectAsState()
    val ride by graph.passenger.state.collectAsState()
    val train by graph.passenger.train.collectAsState()
    val scope = rememberCoroutineScope()
    var explain by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val turnOn: () -> Unit = {
        scope.launch {
            denied = !graph.passive.optIn()
        }
    }
    val status = when {
        denied && !passive.optedIn -> stringResource(Res.string.notif_detect_denied)
        !passive.optedIn -> null
        passive.status == PassiveStatus.NO_PERMISSION -> stringResource(Res.string.notif_detect_no_perm)
        ride.phase == CheckInPhase.CHECKED_IN && ride.via == CheckInVia.BEACON ->
            stringResource(Res.string.notif_detect_now, "${train.product} ${train.line} ${train.number}", ride.car ?: "–")
        else -> null
    }
    SectionHeader(stringResource(Res.string.notif_train_section), strong = true)
    Spacer(Modifier.height(8.dp))
    SbbCard {
        GroupHead(stringResource(Res.string.notif_train_group))
        ToggleRow(
            stringResource(Res.string.notif_detect_title),
            stringResource(Res.string.notif_detect_body),
            on = passive.optedIn,
            divider = false,
            status = status,
            statusColor = if (passive.status == PassiveStatus.NO_PERMISSION || (denied && !passive.optedIn)) SbbTheme.colors.changed else SbbTheme.colors.text,
        ) { on ->
            when {
                // Opted in but the permission is gone: the tap asks again rather than switching off.
                passive.optedIn && passive.status == PassiveStatus.NO_PERMISSION -> turnOn()
                on -> { denied = false; explain = true }
                else -> graph.passive.optOut()
            }
        }
    }
    if (explain) DetectSheet(onTurnOn = { explain = false; turnOn() }, onDismiss = { explain = false })
}

/** The one-time explanation before the system asks: what we use, why, and that it's only on the train. */
@Composable
private fun DetectSheet(onTurnOn: () -> Unit, onDismiss: () -> Unit) {
    val t = SbbTheme.type
    SbbSheet(stringResource(Res.string.notif_detect_sheet_title), onDismiss = onDismiss) {
        SbbText(
            stringResource(Res.string.notif_detect_lead),
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            style = t.body.copy(lineHeight = t.segment.lineHeight),
        )
        ListCard(inset = true) {
            Reason("train-small", stringResource(Res.string.notif_reason_bt_title), stringResource(Res.string.notif_reason_bt_body))
            Hairline()
            Reason("bell-small", stringResource(Res.string.notif_reason_notif_title), stringResource(Res.string.notif_reason_notif_body))
        }
        SbbText(
            stringResource(Res.string.notif_detect_body),
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
            style = t.small,
            color = SbbTheme.colors.meta,
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PrimaryButton(stringResource(Res.string.notif_turn_on), onClick = onTurnOn)
            SbbText(
                stringResource(Res.string.notif_not_now),
                Modifier.fillMaxWidth().height(44.dp).sbbClickable(onClick = onDismiss).padding(top = 12.dp),
                style = t.body,
                align = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Reason(icon: String, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        SbbIcon(icon, size = 24.dp)
        Column(Modifier.weight(1f)) {
            SbbText(title, style = SbbTheme.type.bodyBold.copy(lineHeight = SbbTheme.type.segment.lineHeight))
            SbbText(body, Modifier.padding(top = 2.dp), style = SbbTheme.type.small, color = SbbTheme.colors.meta)
        }
    }
}

/** The group heading inside a card, measured: 14 sp bold, its cap line 21.7 dp below the card top; 35.8 dp in all. */
@Composable
private fun GroupHead(text: String) {
    Box(Modifier.fillMaxWidth().height(35.8.dp).padding(start = 16.dp, end = 16.dp, top = 16.dp), contentAlignment = Alignment.TopStart) {
        SbbText(text, style = SbbTheme.type.small, weight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * A switch row (phone 19e): 16 sp title over a 13 sp meta description on 15.6 dp lines, the [SbbSwitch] 16 dp from
 * the right, centred; a hairline on top between rows. Disabled: title and text in the meta colour, the switch at 50 %.
 */
@Composable
private fun ToggleRow(
    title: String,
    text: String,
    on: Boolean,
    enabled: Boolean = true,
    divider: Boolean = true,
    status: String? = null,
    statusColor: Color = SbbTheme.colors.text,
    onChange: (Boolean) -> Unit,
) {
    val c = SbbTheme.colors
    Column {
        if (divider) Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .sbbClickable(enabled = enabled, role = Role.Switch) { onChange(!on) }
                .padding(start = 16.dp, end = 16.dp, top = 13.6.dp, bottom = 12.7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                SbbText(title, style = SbbTheme.type.body, color = if (enabled) c.text else c.meta)
                SbbText(text, Modifier.padding(top = 5.6.dp), style = sbbStyle(SbbTheme.type.family, 13.sp, 15.6.sp), color = c.meta)
                if (status != null) SbbText(status, Modifier.padding(top = 8.dp), style = sbbStyle(SbbTheme.type.family, 13.sp, 15.6.sp), color = statusColor)
            }
            SbbSwitch(on, enabled)
        }
    }
}

/**
 * SBB Mobile's switch (measured on the phone): a 60.2 × 30.1 dp pill, red when on, a 26 dp white thumb 2 dp from
 * the ends; off, the track takes the line colour (web `.switch`). Disabled: the track at 50 % over the card.
 */
@Composable
fun SbbSwitch(on: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val c = SbbTheme.colors
    val track by animateColorAsState(
        when {
            !on -> c.line
            enabled -> c.red
            else -> c.red.copy(alpha = 0.5f)
        },
        tween(150),
    )
    val x by animateDpAsState(if (on) 60.2.dp - 26.dp - 2.05.dp else 2.05.dp, tween(150))
    Box(modifier.width(60.2.dp).height(30.1.dp).clip(RoundedCornerShape(percent = 50)).background(track)) {
        Box(Modifier.offset(x = x, y = 2.05.dp).size(26.dp).clip(CircleShape).background(Color.White))
    }
}
