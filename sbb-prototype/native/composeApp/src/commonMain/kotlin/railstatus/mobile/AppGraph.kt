package railstatus.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import railstatus.mobile.data.AppClock
import railstatus.mobile.data.AppStore
import railstatus.mobile.incident.BeaconScanner
import railstatus.mobile.incident.HeadsUp
import railstatus.mobile.incident.KtorPassengerBackend
import railstatus.mobile.incident.Notifier
import railstatus.mobile.incident.PassengerConfig
import railstatus.mobile.incident.PassengerStore
import railstatus.mobile.incident.PassiveCheckIn
import railstatus.mobile.incident.PassivePlatform
import railstatus.mobile.incident.chat.ChatStore
import railstatus.mobile.data.KeyValueStore as AppKeyValueStore
import railstatus.mobile.incident.KeyValueStore as PassengerKeyValueStore

/**
 * What a platform gives the shared app. Everything else is common code.
 * Android and the web each provide one in [platformServices].
 */
interface PlatformServices {
    /** With an engine that supports WebSockets (OkHttp on Android, Js on the web). */
    fun httpClient(config: io.ktor.client.HttpClientConfig<*>.() -> Unit): HttpClient
    val notifier: Notifier
    val scanner: BeaconScanner
    val appStorage: AppKeyValueStore
    val passengerStorage: PassengerKeyValueStore
    val passengerConfig: PassengerConfig
    /** What passive check-in needs besides the scanner (Android: the Bluetooth permission). */
    val passivePlatform: PassivePlatform get() = PassivePlatform.Always
}

/** The platform's services; replaced by real ones (Bluetooth, notifications, storage) in androidMain / wasmJsMain. */
expect fun platformServices(): PlatformServices

/** The app's long-lived state, created once and shared by every screen through [LocalAppGraph]. */
class AppGraph(platform: PlatformServices, scope: CoroutineScope = MainScope()) {
    private val http = platform.httpClient { install(WebSockets) }
    private val backend = KtorPassengerBackend(http)

    /** Saved trips, recent stations, search state (the web's state/store.ts). */
    val app = AppStore(platform.appStorage)

    /** Passive check-in by the train's beacon, after a one-time opt-in (`passive.optIn()`, `passive.state`). */
    val passive = PassiveCheckIn(scope, platform.passengerStorage, platform.scanner, platform.passivePlatform, platform.notifier)

    /** Check-in, the incident and the train (usePassenger / useIncident / useTrain / usePerson). */
    val passenger = PassengerStore(
        scope, backend, platform.notifier, platform.passengerStorage, passive.scanner, platform.passengerConfig.copy(lang = deviceLang()),
        clock = { AppClock.now() }, zone = AppClock.zone,
    )

    /** The support chat (useChat / ask). */
    val chat = ChatStore(scope, passenger, backend, platform.passengerStorage)

    /** The in-app banner for a new update, alongside the system notification. */
    val headsUp = HeadsUp(passenger, platform.notifier, scope)

    init {
        passive.attach(passenger)
    }
}

val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("No AppGraph: App() provides it") }

@Composable
fun rememberAppGraph(): AppGraph = remember { AppGraph(platformServices()) }

/** The phone's language as one of the four the updates are written in (German, French, Italian), else English. */
fun deviceLang(): railstatus.model.Lang = when (androidx.compose.ui.text.intl.Locale.current.language.lowercase()) {
    "de" -> railstatus.model.Lang.DE
    "fr" -> railstatus.model.Lang.FR
    "it" -> railstatus.model.Lang.IT
    else -> railstatus.model.Lang.EN
}
