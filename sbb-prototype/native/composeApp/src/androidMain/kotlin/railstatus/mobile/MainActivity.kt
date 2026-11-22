package railstatus.mobile

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import railstatus.mobile.nav.DeepLinks
import railstatus.mobile.platform.DemoConfig
import railstatus.mobile.platform.RideService
import railstatus.mobile.nav.Navigator
import railstatus.mobile.nav.open
import railstatus.mobile.nav.rememberNavigator
import railstatus.mobile.ui.theme.isDarkTheme

/**
 * Android entry point: edge-to-edge window, deep links, demo settings from intent extras ([DemoConfig]).
 * Everything else is the shared [App], on the process-wide [AppGraph] that [RailApp] owns.
 */
class MainActivity : ComponentActivity() {
    private var navigator: Navigator? = null
    private var pendingLink: String? = null
    private val graph: AppGraph get() = (application as RailApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            pendingLink = intent?.dataString
            DemoConfig.apply(this, intent, graph)
        }
        setContent {
            val nav = rememberNavigator()
            navigator = nav
            val dark = isDarkTheme()
            LaunchedEffect(dark) {
                // White status-bar icons on the red header; gesture handle contrasting with the tab bar.
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
            }
            LaunchedEffect(Unit) { pendingLink?.let(::openLink); pendingLink = null }
            App(nav, graph)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DemoConfig.apply(this, intent, graph)
        intent.dataString?.let(::openLink)
    }

    override fun onResume() {
        super.onResume()
        // Opted in to passive check-in: look for the train now, not at the next round.
        graph.passive.scanNow()
        // The ride service may not have been allowed to start in the background.
        RideService.sync(this)
    }

    private fun openLink(uri: String) {
        val link = DeepLinks.parse(uri) ?: return
        navigator?.open(link)
    }
}
