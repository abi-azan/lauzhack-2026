package railstatus.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import railstatus.mobile.nav.Navigator
import railstatus.mobile.nav.SbbApp
import railstatus.mobile.nav.rememberNavigator
import railstatus.mobile.screens.easyride.PassengerOverlay
import railstatus.mobile.screens.easyride.IncidentStrip
import railstatus.mobile.ui.theme.SbbTheme
import railstatus.mobile.screens.incident.IncidentSheetHost

/** The whole app, the same on Android and the web. Platform entry points only create the window and pass deep links. */
@Composable
fun App(navigator: Navigator = rememberNavigator(), graph: AppGraph = rememberAppGraph()) {
    CompositionLocalProvider(LocalAppGraph provides graph) {
        SbbTheme {
            // The passenger side on every screen: the incident strip, and the heads-up, passive check-in, demo keys.
            // While the passenger's train has a disruption, SBB's warning band sits on top of every tab (Matej's call:
            // EasyRide is only for EasyRide journeys). It opens the incident as SBB's "Information" sheet.
            SbbApp(navigator, banner = { IncidentStrip() }, overlay = { PassengerOverlay(); IncidentSheetHost() })
        }
    }
}
