package railstatus.mobile.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.nav.Destination
import railstatus.mobile.ui.chrome.SbbScreen
import railstatus.mobile.ui.chrome.ScreenColumn
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.theme.SbbTheme

/**
 * A Profile row's page that the prototype doesn't fill (Co-passenger, Licence plates, Help & Contact, Legal): the
 * red header with the back arrow, as Notifications, and one card on the red band with the row's icon at 64 dp
 * over a short line (like the Tickets tab's "no ticket" card).
 */
data class ProfileInfoDestination(val title: StringResource, val icon: String, val text: StringResource) : Destination {
    @Composable
    override fun Content() = ProfileInfoScreen(title, icon, text)
}

@Composable
fun ProfileInfoScreen(title: StringResource, icon: String, text: StringResource) {
    SbbScreen(title = stringResource(title), showBack = true) {
        ScreenColumn {
            SbbCard {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SbbIcon(icon, size = 64.dp)
                    SbbText(stringResource(text), style = SbbTheme.type.body, color = SbbTheme.colors.text2, align = TextAlign.Center)
                }
            }
        }
    }
}
