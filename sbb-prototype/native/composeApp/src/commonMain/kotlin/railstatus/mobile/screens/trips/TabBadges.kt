package railstatus.mobile.screens.trips

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import railstatus.mobile.nav.Tab
import railstatus.mobile.nav.TabIndicators
import railstatus.mobile.screens.easyride.EasyRideJourney

/**
 * The one owner of the tab bar's indicators ([TabIndicators]), called once from the shell.
 *
 * As in SBB Mobile: the red tick on Tickets and EasyRide's switch turned on appear only while an EasyRide journey
 * (the paid one, started with the slider) runs. Our passive check-in is not an EasyRide journey, so it sets neither,
 * and SBB shows no tick on Trips for saved trips.
 */
@Composable
fun TabBadges() {
    val riding = EasyRideJourney.startedAt != null
    LaunchedEffect(Unit) { TabIndicators.badge(Tab.Trips, false) }
    LaunchedEffect(riding) {
        TabIndicators.badge(Tab.Tickets, riding)
        TabIndicators.switchOn(Tab.EasyRide, riding)
    }
}
