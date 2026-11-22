package railstatus.mobile.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.LocalDensity
import railstatus.mobile.screens.rootDestination
import railstatus.mobile.screens.trips.DemoTripSync
import railstatus.mobile.screens.trips.TabBadges
import railstatus.mobile.ui.chrome.SheetHostLayout
import railstatus.mobile.ui.chrome.TabBar
import railstatus.mobile.ui.theme.SbbDimens
import railstatus.mobile.ui.theme.SbbTheme

/** Creates the app's navigator, with each tab's root screen from [rootDestination]. */
@Composable
fun rememberNavigator(): Navigator = remember { Navigator(::rootDestination) }

/**
 * The app shell, shared by Android and the web: the red status-bar area, the screens (with the push/back slide), the
 * tab bar and modal sheets on top. Call it inside [railstatus.mobile.ui.theme.SbbTheme].
 *
 * @param banner drawn between the status bar and the screens (e.g. an incident strip); empty by default
 * @param overlay drawn over everything (e.g. heads-up notifications)
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SbbApp(
    navigator: Navigator = rememberNavigator(),
    banner: @Composable () -> Unit = {},
    overlay: @Composable () -> Unit = {},
) {
    val c = SbbTheme.colors
    val density = LocalDensity.current
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    // The tab bar's ticks and switch (their only owner), and the demo trip kept in step with the passenger side.
    TabBadges()
    DemoTripSync()
    CompositionLocalProvider(LocalNavigator provides navigator) {
        SheetHostLayout {
            Box(Modifier.fillMaxSize().background(c.bg)) {
                Column(Modifier.fillMaxSize()) {
                    // Edge to edge: the app draws the red behind the system status bar.
                    Box(Modifier.fillMaxWidth().height(statusTop).background(c.red))
                    banner()
                    // The viewport runs on `Under` below the tab bar's top, so screens show through its notch.
                    NavHost(
                        navigator,
                        Modifier.weight(1f).fillMaxWidth().padding(bottom = SbbDimens.TabBar + navBottom - SbbDimens.Under),
                    )
                }
                TabBar(
                    active = navigator.tab,
                    onSelect = navigator::selectTab,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    badges = TabIndicators.badges.filterValues { it }.keys,
                    on = TabIndicators.on.filterValues { it }.keys,
                )
                overlay()
            }
            BackHandler(enabled = navigator.canGoBack) { navigator.back() }
        }
    }
}
