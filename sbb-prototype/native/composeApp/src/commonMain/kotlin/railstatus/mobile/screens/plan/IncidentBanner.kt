package railstatus.mobile.screens.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.data.Connection
import railstatus.mobile.data.Disruption
import railstatus.mobile.data.Leg
import railstatus.mobile.data.isIncident
import railstatus.mobile.incident.TrainView
import railstatus.mobile.nav.LocalNavigator
import railstatus.mobile.screens.incident.IncidentDestination
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.sbbClickable
import railstatus.mobile.ui.theme.SbbTheme
import railstatus.mobile.screens.incident.IncidentSheet

// SBB's red disruption banner for the open incident on the passenger's train (spec P3/P5). The incident reaches the
// trip as a leg disruption ([isIncident]: DemoTripSync on the Trips side sets it from `passenger.incident` and drops it
// on resolve), so the banner comes and goes with the incident.

/**
 * Whether [this] leg is the passenger's train: the same train number, or the same product and line running the same
 * way (its first stop before its last on the train's route).
 */
internal fun Leg.isTrain(train: TrainView): Boolean {
    if (product?.code != train.product || line != train.line) return false
    if (number != null && number == train.number) return true
    val names = train.stops.map { it.station }
    val a = names.indexOf(stops.first().station.name)
    val b = names.indexOf(stops.last().station.name)
    return a >= 0 && b > a
}

/** The open incident's disruption on the connection, if any. */
internal fun Connection.incidentDisruption(): Disruption? =
    legs.firstNotNullOfOrNull { l -> l.disruptions.firstOrNull { it.isIncident } }

/**
 * The red banner for an incident disruption; tapping it opens the incident page of the passenger's train. [tuck]: how
 * far its top hides under the card above (the companion's step card); then only the bottom corners are rounded.
 */
@Composable
internal fun IncidentBanner(d: Disruption, modifier: Modifier = Modifier, tuck: Dp = 0.dp) {
    val nav = LocalNavigator.current
    val passenger = LocalAppGraph.current.passenger
    val r = 16.dp
    val shape = if (tuck > 0.dp) RoundedCornerShape(bottomStart = r, bottomEnd = r) else RoundedCornerShape(r)
    // One clear message, as on r05: the incident's title (the cause line repeats the section).
    RedBannerBody(listOf(d.summary), shape, modifier, tuck, disruptionIconName(d)) {
        (passenger.incident.value?.trainId ?: passenger.state.value.trainId)?.let(IncidentSheet::open)
    }
}

/** SBB marks an operator message (our incident) with the red ⓘ square ("info"); other notes keep their kind's icon. */
internal fun disruptionIconName(d: Disruption): String = if (d.isIncident) "info" else statusIconName(d.kind)

/** The disruption's red square icon; [ring]: the white outline it gets on red chips and banners. */
@Composable
internal fun DisruptionIcon(d: Disruption, size: Dp, modifier: Modifier = Modifier, ring: Boolean = false) {
    Box(modifier.size(size)) {
        // [ring]: on a red chip or banner, just the white symbol, with no box around it.
        if (ring) SbbIcon(railstatus.mobile.screens.easyride.glyphOnRed(disruptionIconName(d)), size = size, tint = Color.White)
        else SbbIcon(disruptionIconName(d), size = size)
    }
}

/** Opens the incident page of the passenger's train. */
@Composable
internal fun rememberOpenIncident(): () -> Unit {
    val nav = LocalNavigator.current
    val passenger = LocalAppGraph.current.passenger
    return { (passenger.incident.value?.trainId ?: passenger.state.value.trainId)?.let(IncidentSheet::open) }
}

/**
 * The red banner's body, measured on the real app (same phone): #EB0000, 35.4 dp for one line under the card; an 18 dp
 * icon with a white ring 15 dp in, centred; 14 sp bold white text 8 dp after it; a white chevron 16 dp from the right.
 * [tuck] is pulled up under the card above and added to the top padding.
 */
@Composable
internal fun RedBannerBody(
    lines: List<String>,
    shape: RoundedCornerShape,
    modifier: Modifier,
    tuck: Dp,
    icon: String,
    onClick: () -> Unit,
) {
    Row(
        (if (tuck > 0.dp) Modifier.pullUp(tuck) else Modifier)
            .then(modifier)
            .fillMaxWidth()
            .clip(shape)
            .background(SbbTheme.colors.red)
            .sbbClickable(shape, onClick = onClick)
            .padding(start = 15.dp, end = 12.dp, top = tuck + 7.9.dp, bottom = 7.9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp)) {
            SbbIcon(icon, size = 18.dp)
            Box(Modifier.size(18.dp).border(1.1.dp, Color.White, RoundedCornerShape(2.dp)))
        }
        Column(Modifier.weight(1f)) {
            val st = planStyle(14f, FontWeight.Bold, lh = 19.6f)
            for (l in lines) SbbText(l, style = st, color = Color.White)
        }
        SbbIcon("chevron-small-right-small", Modifier.align(Alignment.CenterVertically), size = 24.dp, tint = Color.White)
    }
}
