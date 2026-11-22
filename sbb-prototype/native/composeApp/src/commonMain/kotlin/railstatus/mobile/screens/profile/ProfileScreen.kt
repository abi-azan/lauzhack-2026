package railstatus.mobile.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.LocalAppGraph
import railstatus.mobile.nav.Destination
import railstatus.mobile.nav.LocalNavigator
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.app_settings
import railstatus.mobile.resources.help
import railstatus.mobile.resources.help_contact
import railstatus.mobile.resources.legal
import railstatus.mobile.resources.log_out
import railstatus.mobile.resources.notifications
import railstatus.mobile.resources.offers_advertising
import railstatus.mobile.resources.other_settings
import railstatus.mobile.resources.profile_offers_empty
import railstatus.mobile.resources.profile_ticket_purchase_empty
import railstatus.mobile.resources.profile_wallet_empty
import railstatus.mobile.resources.profile_help_empty
import railstatus.mobile.resources.prototype_note
import railstatus.mobile.resources.tab_profile
import railstatus.mobile.resources.theme_dark
import railstatus.mobile.resources.theme_light
import railstatus.mobile.resources.theme_system
import railstatus.mobile.resources.ticket_purchase_settings
import railstatus.mobile.resources.wallet
import railstatus.mobile.ui.chrome.SbbScreen
import railstatus.mobile.ui.chrome.SbbSheet
import railstatus.mobile.ui.chrome.ScreenColumn
import railstatus.mobile.ui.components.AccountCard
import railstatus.mobile.ui.components.ListCard
import railstatus.mobile.ui.components.ListRow
import railstatus.mobile.ui.components.PillButton
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.SectionHeader
import railstatus.mobile.ui.theme.AppAppearance
import railstatus.mobile.ui.theme.SbbDimens
import railstatus.mobile.ui.theme.SbbTheme
import railstatus.mobile.ui.theme.ThemePref

/** Profile tab root (ported from the web's ProfileScreen). Web: `#/profile`. */
data object ProfileDestination : Destination {
    @Composable
    override fun Content() = ProfileScreen()
}

@Composable
fun ProfileScreen() {
    val nav = LocalNavigator.current
    val app = LocalAppGraph.current.app
    var themeSheet by remember { mutableStateOf(false) }
    SbbScreen(title = stringResource(Res.string.tab_profile)) {
        ScreenColumn {
            // Logged in, as SBB Mobile 12.60 with the demo traveller: no "Profile Settings" section, no Login button.
            // Measured on a logged-in phone (22 Nov): the 76 dp account card lies over the top of a separate Wallet card,
            // whose row (wallet icon, no chevron) shows just under the account card's edge.
            Box {
                SbbCard(Modifier.padding(top = ACCOUNT_CARD - WALLET_TUCK)) {
                    Spacer(Modifier.height(WALLET_TUCK))
                    ListRow(
                        stringResource(Res.string.wallet),
                        icon = "wallet-small",
                        onClick = { nav.push(ProfileInfoDestination(Res.string.wallet, "wallet-small", Res.string.profile_wallet_empty)) },
                    )
                }
                AccountCard(height = ACCOUNT_CARD, avatar = "profile-avatar", avatarSize = 32.dp)
            }
            SectionHeader(stringResource(Res.string.app_settings))
            Rows(
                listOf(
                    "bell-small" to Res.string.notifications,
                    "newspaper-small" to Res.string.offers_advertising,
                    "ticket-route-small" to Res.string.ticket_purchase_settings,
                    "gears-small" to Res.string.other_settings,
                ),
                onClick = mapOf(
                    0 to { nav.push(NotificationsDestination) },
                    1 to { nav.push(ProfileInfoDestination(Res.string.offers_advertising, "newspaper-small", Res.string.profile_offers_empty)) },
                    2 to { nav.push(ProfileInfoDestination(Res.string.ticket_purchase_settings, "ticket-route-small", Res.string.profile_ticket_purchase_empty)) },
                    3 to { themeSheet = true },
                ),
            )
            SectionHeader(stringResource(Res.string.help))
            Rows(
                listOf("circle-question-mark-small" to Res.string.help_contact, "paragraph-small" to Res.string.legal),
                onClick = mapOf(
                    0 to { nav.push(ProfileInfoDestination(Res.string.help_contact, "circle-question-mark-small", Res.string.profile_help_empty)) },
                    1 to { nav.push(ProfileInfoDestination(Res.string.legal, "paragraph-small", Res.string.prototype_note)) },
                ),
            )
            // SBB's outlined "Log out" pill, left-aligned with the cards' edge (as in 12.60). The demo stays logged in.
            Box(Modifier.fillMaxWidth().padding(start = SbbDimens.Gutter, top = 16.dp), contentAlignment = Alignment.CenterStart) {
                PillButton(stringResource(Res.string.log_out), onClick = {}, horizontalPadding = 16.dp, textStyle = SbbTheme.type.body)
            }
            // In place of SBB's version line: the same small ⓘ near the edge and grey text, with our disclaimer.
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                SbbIcon("circle-information-small", size = 16.dp, tint = SbbTheme.colors.text2)
                SbbText(stringResource(Res.string.prototype_note), Modifier.weight(1f), style = SbbTheme.type.meta, color = SbbTheme.colors.text2)
            }
        }
    }
    if (themeSheet) {
        SbbSheet(stringResource(Res.string.other_settings), onDismiss = { themeSheet = false }) {
            ListCard(inset = true) {
                listOf(ThemePref.Auto to Res.string.theme_system, ThemePref.Light to Res.string.theme_light, ThemePref.Dark to Res.string.theme_dark)
                    .forEachIndexed { i, (pref, label) ->
                        ListRow(
                            stringResource(label),
                            divider = i > 0,
                            height = if (i > 0) 49.1.dp else 48.dp,
                            onClick = {
                                AppAppearance.pref = pref
                                // Saved like the web's store, so the choice survives a restart.
                                app.setTheme(
                                    when (pref) {
                                        ThemePref.Auto -> railstatus.mobile.data.ThemePref.AUTO
                                        ThemePref.Light -> railstatus.mobile.data.ThemePref.LIGHT
                                        ThemePref.Dark -> railstatus.mobile.data.ThemePref.DARK
                                    },
                                )
                            },
                            leading = { Radio(AppAppearance.pref == pref) },
                        )
                    }
            }
        }
    }
}

/** The account card's height, and how far the Wallet card reaches under it (SBB Mobile 12.60, measured). */
private val ACCOUNT_CARD = 76.dp
private val WALLET_TUCK = 16.dp

@Composable
private fun Rows(items: List<Pair<String, StringResource>>, onClick: Map<Int, () -> Unit> = emptyMap()) {
    ListCard {
        items.forEachIndexed { i, (icon, label) -> ListRow(stringResource(label), icon = icon, divider = i > 0, onClick = onClick[i] ?: {}) }
    }
}

/** Radio (web `.radio`): 22 dp ring, red with a 4 dp gap and a 10 dp red dot when on. */
@Composable
fun Radio(on: Boolean) {
    val c = SbbTheme.colors
    Box(
        Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (on) c.red else c.text2, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(c.red)) }
}
