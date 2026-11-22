package railstatus.mobile.screens.plan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.nav.Destination
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.tab_plan
import railstatus.mobile.ui.chrome.SbbScreen
import railstatus.mobile.ui.chrome.sbbRedBand
import railstatus.mobile.ui.map.NetworkMap
import railstatus.mobile.ui.map.NetworkMapButtons
import railstatus.mobile.ui.theme.SbbDimens

/** Network map with the From/To card and map buttons. Web: `#/plan/map`. */
data object NetworkMapDestination : Destination {
    @Composable
    override fun Content() = NetworkMapScreen()
}

/**
 * The Plan tab's network map ([61] in the teardown): Switzerland's rail network and cities from Freiburg i. Br. to
 * Milano, the From/To card on top, the locate button on the right. Pan and pinch like the companion's map.
 */
@Composable
fun NetworkMapScreen() {
    SbbScreen(title = stringResource(Res.string.tab_plan), showBack = true) {
        NetworkMap(Modifier.fillMaxSize())
        Column(Modifier.fillMaxWidth().sbbRedBand()) {
            FromTo()
        }
        NetworkMapButtons(Modifier.align(Alignment.TopEnd).padding(top = 96.dp + SbbDimens.Band + 16.dp, end = 8.dp))
    }
}
