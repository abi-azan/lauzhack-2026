package railstatus.mobile.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.nav.Destination
import railstatus.mobile.nav.Tab
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.stub_note
import railstatus.mobile.screens.easyride.EasyRideDestination
import railstatus.mobile.screens.plan.PlanDestination
import railstatus.mobile.screens.profile.ProfileDestination
import railstatus.mobile.screens.shop.ShopDestination
import railstatus.mobile.screens.tickets.TicketsDestination
import railstatus.mobile.screens.trips.TripsDestination
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.theme.SbbTheme

/** Each tab's root screen. */
fun rootDestination(tab: Tab): Destination = when (tab) {
    Tab.Plan -> PlanDestination
    Tab.Trips -> TripsDestination
    Tab.EasyRide -> EasyRideDestination
    Tab.Tickets -> TicketsDestination
    Tab.Shop -> ShopDestination
    Tab.Profile -> ProfileDestination
}

/** Placeholder body for stub screens: a card saying what goes here. Delete it when you build the screen. */
@Composable
fun StubCard(what: String) {
    SbbCard {
        SbbText(
            "$what\n${stringResource(Res.string.stub_note)}",
            Modifier.fillMaxWidth().padding(24.dp, 24.dp),
            color = SbbTheme.colors.text2,
            align = TextAlign.Center,
        )
    }
}
