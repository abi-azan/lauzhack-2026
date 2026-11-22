package railstatus.mobile.platform

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import railstatus.mobile.RailApp
import railstatus.mobile.incident.CheckInPhase
import railstatus.mobile.incident.PassengerState
import railstatus.mobile.incident.PassiveState

/**
 * T5: the foreground service of a ride. While the passenger is checked in (or, after the passive check-in opt-in,
 * while we watch for the train's beacon) it keeps the process, and so the shared PassengerStore's WebSocket, alive
 * with the screen off, holds a partial wake lock while checked in, and keeps the ride notification up to date.
 * Its own re-renders never alert (`setOnlyAlertOnce`). Whether an incident update alerts is HeadsUp's call
 * (NotificationPolicy): [AndroidNotifier.show] alerts, [AndroidNotifier.update] doesn't, on the same notification.
 *
 * [RailApp] starts it when it's needed; it stops itself (and removes the notification) when it no longer is.
 */
class RideService : Service() {
    private val scope = MainScope()
    private var job: Job? = null
    private var stopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var last: RideNotifications.Ride? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = RailApp.instance.graph
        when (intent?.action) {
            ACTION_CHECK_OUT -> graph.passenger.checkOut()
            ACTION_STOP_WATCHING -> graph.passive.optOut()
        }
        // After startForegroundService() we must call startForeground(), even if we stop right after.
        val ride = RideNotifications.snapshot(graph)
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, RideNotifications.ID, RideNotifications.build(this, ride, alert = false), type)
            last = ride
        } catch (e: Exception) {
            // Android 12+ refuses a foreground start from the background (e.g. a restart): carry on as we can.
            Log.w(TAG, "startForeground failed", e)
        }
        if (job == null) job = scope.launch { follow() }
        return START_STICKY
    }

    private suspend fun follow() {
        val graph = RailApp.instance.graph
        val store = graph.passenger
        // Re-render on any change, and every 30 s for the journey's progress.
        val tick = flow { while (true) { emit(Unit); delay(30_000) } }
        combine(store.state, store.incident, store.train, graph.passive.state, tick) { _, _, _, _, _ -> }.collect {
            val ride = RideNotifications.snapshot(graph)
            if (!ride.needed) {
                // Not needed (checked out, opt-out). Wait a moment: a backend reset re-checks in within seconds.
                if (stopJob?.isActive != true) stopJob = scope.launch {
                    delay(3_000)
                    if (!RideNotifications.snapshot(graph).needed) stop()
                }
                return@collect
            }
            stopJob?.cancel()
            holdWakeLock(ride.checkedIn)
            if (ride != last) {
                RideNotifications.post(this, ride, alert = false)
                last = ride
            }
        }
    }

    /** CPU stays awake while checked in, so socket reads and the alert happen at once with the screen off. */
    private fun holdWakeLock(on: Boolean) {
        val held = wakeLock?.isHeld == true
        if (on && !held) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "railstatus:ride")
                .apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
        } else if (!on && held) {
            wakeLock?.release()
        }
    }

    private fun stop() {
        holdWakeLock(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        RideNotifications.cancel(this)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        holdWakeLock(false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RideService"
        const val ACTION_CHECK_OUT = "railstatus.mobile.CHECK_OUT"
        const val ACTION_STOP_WATCHING = "railstatus.mobile.STOP_WATCHING"

        @Volatile
        var running = false
            private set

        /** Checked in (or re-checking in after a backend reset), or watching for the beacon. */
        fun needed(p: PassengerState, passive: PassiveState): Boolean =
            p.phase == CheckInPhase.CHECKED_IN || (p.phase == CheckInPhase.FOUND && p.checkedInAt != null) || passive.optedIn

        /** Starts the service if it's needed and not running. Safe to call from anywhere. */
        fun sync(ctx: Context) {
            if (running) return
            val graph = RailApp.instance.graph
            if (!needed(graph.passenger.state.value, graph.passive.state.value)) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RideService::class.java))
            } catch (e: Exception) {
                // Android 12+: not from the background (ForegroundServiceStartNotAllowedException). The next time the
                // app is opened, MainActivity calls this again.
                Log.w(TAG, "could not start the ride service", e)
            }
        }
    }
}
