package railstatus.mobile.screens.trips

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.data.DemoTrip
import railstatus.mobile.data.now

/**
 * Keeps the saved trip ([DemoTrip]) in step with the passenger's check-in, app-wide (called once from the shell next
 * to [TabBadges]):
 * - saves the trip's id at start, so Trips lists it whenever there is one;
 * - the trip is the checked-in train (the backend's, or the offline demo's scripted one), from where the passenger
 *   boards (the station they follow it from, else the ticket's origin, else its first stop) to where they get off
 *   (the station they picked, else the ticket's or asked destination, else its last stop), with the train's times
 *   and platforms;
 * - not checked in: no trip, and Trips shows its empty state.
 */
@Composable
fun DemoTripSync() {
    val graph = LocalAppGraph.current
    val s by graph.passenger.state.collectAsState()
    val train by graph.passenger.train.collectAsState()
    val person by graph.passenger.person.collectAsState()

    LaunchedEffect(Unit) {
        if (DemoTrip.ID !in graph.app.value.saved) graph.app.update { it.copy(saved = listOf(DemoTrip.ID) + it.saved) }
    }

    val live = s.live?.train
    val offlineRide = s.api == null && s.checkedInAt != null
    // A station follower says where they get off (or "not sure": the last stop); never the ticket's destination.
    val following = s.boardingId != null
    val boardingName = if (following) null else person.persona.ticket?.from
    val destId = if (following) s.followDestId else null
    val destName = if (following) null else person.dest
    val ride = remember(live, offlineRide, train, s.boardingId, boardingName, destId, destName) {
        when {
            live != null -> DemoTrip.fromTrain(live, s.boardingId, boardingName, destId, destName, now())
            offlineRide -> DemoTrip.fromPlanned(train, s.boardingId, boardingName, destId, destName)
            else -> null
        }
    }
    LaunchedEffect(ride) { DemoTrip.setLive(ride) }

    // The incident is shown by the yellow band on every tab, not as an SBB disruption on the trip (that would be a
    // second, red warning for the same thing).
    LaunchedEffect(Unit) { DemoTrip.setDisruption(null) }
}
