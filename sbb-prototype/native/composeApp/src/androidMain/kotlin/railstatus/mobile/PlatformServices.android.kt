package railstatus.mobile

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import android.content.Context
import railstatus.mobile.incident.PassengerConfig
import railstatus.mobile.platform.AndroidNotifier
import railstatus.mobile.platform.BeaconPermissions
import railstatus.mobile.platform.DemoBeaconScanner
import railstatus.mobile.platform.DemoConfig
import railstatus.mobile.platform.PrefsStore
import java.util.concurrent.TimeUnit

/** Android: SharedPreferences, system notifications (the ride service's live notification), a Bluetooth LE scan. */
class AndroidPlatformServices(context: Context) : PlatformServices {
    private val ctx = context.applicationContext

    override fun httpClient(config: HttpClientConfig<*>.() -> Unit) = HttpClient(OkHttp) {
        // Pings keep NAT/tunnel mappings open with the screen off and notice a dead socket within ~20 s.
        engine { config { pingInterval(20, TimeUnit.SECONDS) } }
        config()
    }

    override val notifier = AndroidNotifier(ctx)
    override val scanner = DemoBeaconScanner(ctx)
    override val appStorage = PrefsStore(ctx, "railstatus-app")
    override val passengerStorage = PrefsStore(ctx, "railstatus-passenger")
    override val passengerConfig = PassengerConfig(defaultApi = DemoConfig.defaultApi())
    override val passivePlatform = BeaconPermissions(ctx)
}

/** The process-wide services; [RailApp] creates them (and the one [AppGraph]) at start. */
actual fun platformServices(): PlatformServices = RailApp.instance.services
