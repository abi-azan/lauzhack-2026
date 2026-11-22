package railstatus.mobile.screens.easyride

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.nav.Destination
import railstatus.mobile.nav.LocalNavigator
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.tab_easyride
import railstatus.mobile.ui.chrome.SbbLoader
import railstatus.mobile.ui.chrome.SbbScreen

/**
 * Deep link `railstatus://checkin/<trainId>?car=N` (the QR code in the car; web `#/checkin/<id>?car=N`). Like the
 * web app, it records which train and car the code named and hands over to the EasyRide tab, where the passenger
 * confirms with one tap ("Get live updates"): that tap is also where the phone may ask for notifications.
 */
data class CheckInLinkDestination(val trainId: String, val car: Int?) : Destination {
    @Composable
    override fun Content() = CheckInLinkScreen(trainId, car)
}

@Composable
fun CheckInLinkScreen(trainId: String, car: Int?) {
    val store = passengerStore()
    val nav = LocalNavigator.current
    LaunchedEffect(trainId, car) {
        // Already checked in to this train (scanned twice): nothing to do.
        val s = store.state.value
        if (s.phase != railstatus.mobile.incident.CheckInPhase.CHECKED_IN || s.trainId != trainId) {
            SavedTripCheckIn.trainId = null
            store.foundByQr(trainId, car?.toString())
        }
        nav.popToRoot()
    }
    SbbScreen(title = stringResource(Res.string.tab_easyride), showBack = true) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SbbLoader() }
    }
}
