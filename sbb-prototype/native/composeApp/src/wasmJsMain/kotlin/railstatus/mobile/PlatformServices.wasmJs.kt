@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package railstatus.mobile

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.Js
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.url.URLSearchParams
import railstatus.mobile.incident.FakeBeaconScanner
import railstatus.mobile.incident.Notifier
import railstatus.mobile.incident.PassengerConfig
import railstatus.mobile.incident.PersonaId
import railstatus.mobile.incident.SilentNotifier
import railstatus.mobile.incident.UpdateNotice
import kotlin.js.Promise
import railstatus.mobile.data.KeyValueStore as AppKeyValueStore
import railstatus.mobile.incident.KeyValueStore as PassengerKeyValueStore

/** The checked-in train, for the notification's link; main() points it at the graph's passenger store. */
internal var notifiedTrain: () -> String? = { null }

/** The web: localStorage, browser notifications, the fake beacon (no Web Bluetooth here), the page's own backend. */
actual fun platformServices(): PlatformServices = WebPlatformServices

private object WebPlatformServices : PlatformServices {
    private val params = URLSearchParams(window.location.search.toJsString())

    override fun httpClient(config: HttpClientConfig<*>.() -> Unit) = HttpClient(Js) { config() }
    override val notifier = BrowserNotifier
    // `?train=IC1-725`: which of the backend's trains the fake beacon search finds.
    override val scanner = FakeBeaconScanner(params.get("train")?.let { "$it-Car7" } ?: railstatus.mobile.incident.FAKE_BEACON)
    override val appStorage = LocalStorage
    override val passengerStorage = LocalStorage
    override val passengerConfig = PassengerConfig(
        // `?api=<url>` for this visit ("off": the offline demo); without it, the page's own origin if it serves the API.
        api = params.get("api").also { if (it == null) forgetRememberedApi() },
        defaultApi = window.location.origin.takeIf { it.startsWith("http") },
        persona = params.get("persona")?.let { p -> PersonaId.entries.firstOrNull { it.name.equals(p, true) } },
    )
}

/**
 * Without `?api=`, the server that serves this page is the backend. A URL remembered from an earlier visit (an old
 * quick tunnel gets a new address on every restart) would otherwise win over the page's origin, and the app would
 * call a dead address and never notify. So forget it, and the store probes the origin instead.
 */
private fun forgetRememberedApi() {
    try { window.localStorage.removeItem("sbb-passenger-api") } catch (_: Throwable) { }
}

/** localStorage, for both stores. Blocked storage (private mode, iframes) throws; the stores carry on without. */
private object LocalStorage : AppKeyValueStore, PassengerKeyValueStore {
    override fun get(key: String): String? = window.localStorage.getItem(key)
    override fun put(key: String, value: String) = window.localStorage.setItem(key, value)
    override fun remove(key: String) = window.localStorage.removeItem(key)
    override fun getString(key: String): String? = try { get(key) } catch (_: Throwable) { null }
    override fun putString(key: String, value: String) { try { put(key, value) } catch (_: Throwable) { } }
}

/**
 * The browser's Notification API. Each update of an incident uses the incident's tag, so it replaces the last one.
 * [show] (a big change, NotificationPolicy) alerts again (`renotify`, vibration); [update] replaces it silently.
 * A click focuses the tab and opens the incident (`#/incident/<trainId>`).
 */
private object BrowserNotifier : Notifier, SilentNotifier {
    override suspend fun requestPermission(): Boolean {
        if (notificationPermission() == "default") {
            try { requestNotificationPermission().await<JsString>() } catch (_: Throwable) { }
        }
        return canNotify()
    }

    /**
     * Set once `new Notification()` threw (Chrome on Android only notifies from a service worker). From then on we
     * report that we can't notify, so HeadsUp shows its in-app banner instead of nothing at all.
     */
    private var broken = false

    override fun canNotify(): Boolean = !broken && notificationPermission() == "granted"

    override fun show(notice: UpdateNotice) = post(notice, alert = true)

    override fun update(notice: UpdateNotice) = post(notice, alert = false)

    private fun post(notice: UpdateNotice, alert: Boolean) {
        if (!canNotify()) return
        val trainId = notifiedTrain() ?: railstatus.mobile.incident.TRAIN.id
        if (!showNotification(title = notice.title, body = notice.body, tag = notice.tag, hash = "#/incident/$trainId", alert = alert)) broken = true
    }
}

private fun notificationPermission(): String =
    js("(typeof Notification === 'undefined') ? 'unsupported' : Notification.permission")

private fun requestNotificationPermission(): Promise<JsString> =
    js("(typeof Notification === 'undefined') ? Promise.resolve('unsupported') : Notification.requestPermission()")

// A silent notification must not have a vibration pattern (the constructor throws). False when it couldn't be shown.
// A click opens the incident: main() routes the hash and then clears it, so the next click (same hash) opens again.
private fun showNotification(title: String, body: String, tag: String, hash: String, alert: Boolean): Boolean = js(
    """{
    try {
        const n = new Notification(title, alert
            ? { body: body, tag: tag, renotify: true, vibrate: [300, 150, 300] }
            : { body: body, tag: tag, renotify: false, silent: true });
        n.onclick = () => { window.focus(); window.location.hash = hash; n.close(); };
        return true;
    } catch (e) { return false; /* e.g. Chrome on Android, which only notifies from a service worker */ }
}""",
)
