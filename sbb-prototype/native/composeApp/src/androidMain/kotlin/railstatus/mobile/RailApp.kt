package railstatus.mobile

import android.app.Application
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import railstatus.mobile.platform.Permissions
import railstatus.mobile.platform.RideNotifications
import railstatus.mobile.platform.RideService

/**
 * The process owns the [AppGraph]: the activity and the [RideService] share one PassengerStore and so one WebSocket.
 * The ride service runs whenever it's needed (checked in, or watching for the beacon after the opt-in).
 */
class RailApp : Application() {
    lateinit var services: AndroidPlatformServices
        private set
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(Permissions.tracker)
        RideNotifications.createChannels(this)
        services = AndroidPlatformServices(this)
        graph = AppGraph(services)
        MainScope().launch {
            combine(graph.passenger.state, graph.passive.state, RideService::needed).collect { needed ->
                if (needed) RideService.sync(this@RailApp)
            }
        }
    }

    companion object {
        lateinit var instance: RailApp
            private set
    }
}
