package railstatus.mobile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.url.URLSearchParams
import railstatus.mobile.nav.DeepLinks
import railstatus.mobile.nav.Tab
import railstatus.mobile.ui.theme.AppAppearance
import railstatus.mobile.ui.theme.ThemePref
import railstatus.mobile.nav.open
import railstatus.mobile.nav.rememberNavigator

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsModule("@js-joda/timezone")
external object JsJodaTimeZoneModule

/** Referenced at start-up so kotlinx-datetime has the zone database (TimeZone.of("Europe/Zurich")). */
val jsJodaTz = JsJodaTimeZoneModule

/**
 * SBB Mobile's sizes are dp measured on a 411.43 dp wide phone (1080 px at density 2.625). Like the web app's
 * PhoneZoom, scale the UI so the page is always that many dp wide, whatever the browser's width.
 */
private const val DESIGN_WIDTH_DP = 1080f / 2.625f

/** Drops the URL's hash without a reload or a hashchange event. */
private fun clearHash() = window.history.replaceState(null, "", window.location.pathname + window.location.search)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    jsJodaTz
    // ?theme=light|dark as in the web app (default: follow the system).
    when (URLSearchParams(window.location.search.toJsString()).get("theme")) {
        "light" -> AppAppearance.pref = ThemePref.Light
        "dark" -> AppAppearance.pref = ThemePref.Dark
    }
    ComposeViewport(document.body!!) {
        val base = LocalDensity.current
        var widthPx by remember { mutableStateOf(window.innerWidth * window.devicePixelRatio.toFloat()) }
        LaunchedEffect(Unit) {
            window.addEventListener("resize") { widthPx = window.innerWidth * window.devicePixelRatio.toFloat() }
        }
        val nav = rememberNavigator()
        val graph = remember { AppGraph(platformServices()).also { g -> notifiedTrain = { g.passenger.state.value.trainId } } }
        LaunchedEffect(Unit) {
            // A notification click sets the hash (#/incident/<id>) of the running app. Once routed, the hash is
            // cleared, so the next click (the same hash again) fires hashchange and opens it again.
            window.addEventListener("hashchange") { DeepLinks.parse(window.location.href)?.let { nav.open(it); clearHash() } }
            // Same links as the web app: /#/checkin/<id>?car=N, /#/incident/<id>, and /#/<tab>.
            val link = DeepLinks.parse(window.location.href)
            if (link != null) { nav.open(link); clearHash() }
            else Tab.entries.firstOrNull { it.name.equals(window.location.hash.removePrefix("#/").substringBefore('/'), ignoreCase = true) }
                ?.let(nav::selectTab)
        }
        CompositionLocalProvider(LocalDensity provides Density(widthPx / DESIGN_WIDTH_DP, base.fontScale)) {
            App(nav, graph)
        }
    }
}
