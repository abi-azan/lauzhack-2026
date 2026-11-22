package railstatus.admin

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import org.w3c.dom.url.URLSearchParams

/**
 * The backend address: `?api=https://…` if given, else the page's own origin (when the server serves
 * this app at /admin). On the webpack dev server (port 8081) it falls back to http://localhost:8080.
 */
private fun backendUrl(): String {
    val api = URLSearchParams(window.location.search.toJsString()).get("api")
    if (api != null) return api
    return if (window.location.port == "8081") "http://localhost:8080" else window.location.origin
}

/** The admin key from `/admin/?key=…`, remembered in this browser so later visits don't need it (R3). */
private fun adminKey(): String? {
    val fromUrl = URLSearchParams(window.location.search.toJsString()).get("key")
    if (fromUrl != null) runCatching { window.localStorage.setItem("railstatus.adminKey", fromUrl) }
    return fromUrl ?: runCatching { window.localStorage.getItem("railstatus.adminKey") }.getOrNull()
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val store = AdminStore(AdminApi(backendUrl(), adminKey()), MainScope())
    store.start()
    // Deep link: /admin/#/incident/<id>
    val initial = window.location.hash.removePrefix("#/incident/").takeIf { window.location.hash.startsWith("#/incident/") }
    ComposeViewport(document.body!!) { App(store, initial) }
}
