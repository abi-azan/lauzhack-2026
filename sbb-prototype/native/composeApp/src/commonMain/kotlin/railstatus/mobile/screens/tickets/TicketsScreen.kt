package railstatus.mobile.screens.tickets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.data.DemoTraveller
import railstatus.mobile.nav.Destination
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.seg_expired
import railstatus.mobile.resources.seg_valid
import railstatus.mobile.resources.tab_tickets
import railstatus.mobile.resources.tickets_half_fare
import railstatus.mobile.resources.tickets_none_expired
import railstatus.mobile.resources.tickets_show_swisspass
import railstatus.mobile.resources.tickets_valid_until
import railstatus.mobile.ui.chrome.SbbScreen
import railstatus.mobile.ui.chrome.ScreenColumn
import railstatus.mobile.ui.chrome.Segmented
import railstatus.mobile.ui.chrome.SegmentedPage
import railstatus.mobile.ui.components.Hairline
import railstatus.mobile.ui.components.PillButton
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.theme.SbbTheme

/** Tickets & Travelcards tab root: Valid | Expired. Web: `#/tickets`. */
data object TicketsDestination : Destination {
    @Composable
    override fun Content() = TicketsScreen()
}

@Composable
fun TicketsScreen() {
    var page by rememberSaveable { mutableIntStateOf(0) }
    SbbScreen(
        title = stringResource(Res.string.tab_tickets),
        headerContent = { Segmented(listOf(stringResource(Res.string.seg_valid), stringResource(Res.string.seg_expired)), page, { page = it }) },
    ) {
        SegmentedPage(page) {
            ScreenColumn {
                if (page == 0) {
                    // The demo traveller: logged in, SwissPass with a Half Fare travelcard (no "no ticket" card).
                    SwissPassCard()
                } else {
                    EmptyTicketCard(stringResource(Res.string.tickets_none_expired))
                }
            }
        }
    }
}

/** The Half Fare travelcard's last day. */
private const val HALF_FARE_UNTIL = "31.12.2027"

/**
 * The SwissPass card of a logged-in traveller: a 46.1 dp heading row ("Swiss**Pass**" in 18 sp, a chevron), the
 * holder in 16 sp bold, then the travelcard as a 67.4 dp list row between hairlines (24 dp icon, 16 sp title, 14 sp
 * validity) and a 32 dp pill button, 16 dp padding.
 */
@Composable
private fun SwissPassCard() {
    val c = SbbTheme.colors
    SbbCard {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().height(46.1.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                SbbText(
                    buildAnnotatedString {
                        append("Swiss")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Pass") }
                    },
                    Modifier.weight(1f),
                    style = SbbTheme.type.title,
                )
                SbbIcon("chevron-small-up-small", size = 24.dp)
            }
            SbbText(DemoTraveller.NAME, Modifier.padding(horizontal = 16.dp), style = SbbTheme.type.bodyBold)
            Hairline(Modifier.padding(top = 12.dp))
            Row(
                Modifier.fillMaxWidth().height(67.4.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SbbIcon("half-fare-card-small", size = 24.dp)
                Column(Modifier.weight(1f)) {
                    SbbText(stringResource(Res.string.tickets_half_fare), style = SbbTheme.type.body)
                    SbbText(stringResource(Res.string.tickets_valid_until, HALF_FARE_UNTIL), style = SbbTheme.type.small, color = c.meta)
                }
            }
            Hairline()
            PillButton(stringResource(Res.string.tickets_show_swisspass), onClick = {}, modifier = Modifier.padding(start = 16.dp, top = 16.dp))
        }
    }
}

/**
 * "No ticket" card (web `.empty-ticket`): 135.6 dp, fading from the card colour into the page, a 64 dp QR code and
 * a 14 sp line at the bottom, 12 dp apart, 16 dp padding.
 */
@Composable
private fun EmptyTicketCard(text: String) {
    val c = SbbTheme.colors
    SbbCard(
        Modifier.background(Brush.verticalGradient(listOf(c.card, c.bg))),
        color = Color.Transparent,
    ) {
        Column(
            Modifier.fillMaxWidth().height(135.6.dp).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
        ) {
            SbbIcon("qrcode-small", size = 64.dp)
            SbbText(text, style = SbbTheme.type.pill, align = TextAlign.Center)
        }
    }
}
