package railstatus.mobile.screens.easyride

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.incident.CheckInPhase
import railstatus.mobile.incident.CheckInVia
import railstatus.mobile.incident.PassengerState
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.p_car_inline
import railstatus.mobile.resources.p_chip_detected
import railstatus.mobile.resources.p_chip_on_board
import railstatus.mobile.resources.p_src_beacon
import railstatus.mobile.resources.p_src_qr
import railstatus.mobile.resources.p_chip_on_board_car
import railstatus.mobile.resources.p_src_saved
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.theme.SbbTheme

/**
 * "On board · car 7 · detected": the small chip that says the phone knows which train the passenger is on (spec P1).
 * For the travel companion, under its header. Renders nothing unless checked in. "detected" only when the train's
 * beacon found it (passive check-in); a QR or saved-trip check-in shows "On board · car 7".
 */
@Composable
fun OnBoardChip(modifier: Modifier = Modifier) {
    val s by LocalAppGraph.current.passenger.state.state()
    if (s.phase != CheckInPhase.CHECKED_IN) return
    val c = SbbTheme.colors
    val pill = RoundedCornerShape(percent = 50)
    val label = s.car?.let { stringResource(Res.string.p_chip_on_board_car, it) } ?: stringResource(Res.string.p_chip_on_board)
    Row(
        modifier
            .height(28.dp)
            .clip(pill)
            .background(c.card)
            .border(1.1.dp, c.ring, pill)
            .padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SbbIcon("circle-tick-small", size = 18.dp)
        SbbText(label, style = SbbTheme.type.pill, maxLines = 1)
    }
}

/**
 * The saved-trip fallback ("Are you on IR 90 1734?") checks in the way a QR code does (the train is known, no
 * beacon); this remembers which train it was, so the screens can say "from your saved trip" instead of "QR code".
 */
internal object SavedTripCheckIn {
    var trainId by mutableStateOf<String?>(null)
}

/** How the passenger's check-in came about, in words: "Detected automatically", "Checked in", ... */
internal fun checkInSource(s: PassengerState): StringResource = when {
    s.via == CheckInVia.BEACON -> Res.string.p_src_beacon
    SavedTripCheckIn.trainId != null && SavedTripCheckIn.trainId == s.trainId -> Res.string.p_src_saved
    else -> Res.string.p_src_qr
}
