package railstatus.mobile.incident

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Passive check-in: after a one-time opt-in, the app looks for the train's Bluetooth beacon by itself and checks the
// passenger in, without the EasyRide swipe. On Android the ride service keeps the process (and so this loop) alive in
// the background; on the web there is no Bluetooth, so the platform's scanner is the fake one.

/** What a beacon scan needs from the platform, besides the [BeaconScanner] itself. */
interface PassivePlatform {
    /** Asks for what a scan needs (Android: the Bluetooth scan permission). Call it inside a user gesture. */
    suspend fun requestScanPermission(): Boolean

    /** Whether a scan can run now (permission still granted). */
    fun canScan(): Boolean

    /** Nothing to ask (the web, tests). */
    object Always : PassivePlatform {
        override suspend fun requestScanPermission() = true
        override fun canScan() = true
    }
}

enum class PassiveStatus {
    /** Not opted in. */
    OFF,

    /** Opted in, waiting for the next scan. */
    WATCHING,

    /** A scan is running. */
    SCANNING,

    /** Checked in (passively or not). Watching resumes after check-out. */
    CHECKED_IN,

    /** The passenger checked out of this train on purpose: no passive check-in until its beacon is out of range. */
    PAUSED,

    /** Opted in, but the Bluetooth permission was taken away. [PassiveCheckIn.optIn] asks again. */
    NO_PERMISSION,
}

data class PassiveState(
    val optedIn: Boolean = false,
    val status: PassiveStatus = PassiveStatus.OFF,
    /** The beacon the last passive check-in came from, e.g. "IR90-1734-Car7". */
    val lastBeacon: String? = null,
)

/**
 * The opt-in and the background scan loop. The UI calls [optIn] once (in a tap: it asks for the Bluetooth and
 * notification permissions) and reads [state]; from then on, whenever the passenger isn't checked in, a scan runs
 * every [intervalMs], and a beacon it finds checks them in through the [PassengerStore], exactly as the EasyRide
 * search would (`via = beacon`).
 *
 * Wiring: give [scanner] (not the platform's) to the [PassengerStore], then [attach] the store.
 */
class PassiveCheckIn(
    private val scope: CoroutineScope,
    private val storage: KeyValueStore,
    private val platformScanner: BeaconScanner,
    private val platform: PassivePlatform,
    private val notifier: Notifier,
    private val intervalMs: Long = 20_000,
) {
    private var store: PassengerStore? = null

    /** A beacon a passive scan just found, handed to the store's search so it doesn't scan a second time. */
    private var handoff: String? = null

    /** The scanner for the [PassengerStore]: the platform's, except right after a passive hit. */
    val scanner: BeaconScanner = BeaconScanner { handoff?.also { handoff = null } ?: platformScanner.find() }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<PassiveState> = _state.asStateFlow()

    /**
     * Train id of a deliberate check-out: its beacon doesn't check them in again until it's gone once, or for at most
     * [PAUSE_MS]. Without that limit a beacon that never leaves range (the demo's stand-in) would pause it for good.
     */
    private var paused: String? = try { storage.get(PAUSED_KEY)?.substringBefore('|') } catch (_: Exception) { null }
    private var pausedAt: Long? = try { storage.get(PAUSED_KEY)?.substringAfter('|', "")?.toLongOrNull() } catch (_: Exception) { null }

    private var loop: Job? = null
    private var wake: Job? = null

    private fun load(): PassiveState {
        // On by default (the demo phones are flashed ready to go); only an explicit opt-out ("0") turns it off.
        val on = try { storage.get(OPT_IN_KEY) != "0" } catch (_: Exception) { true }
        return PassiveState(optedIn = on, status = if (on) PassiveStatus.WATCHING else PassiveStatus.OFF)
    }

    private fun setPaused(trainId: String?) {
        paused = trainId
        pausedAt = trainId?.let { store?.clock()?.toEpochMilliseconds() }
        try { if (trainId == null) storage.remove(PAUSED_KEY) else storage.put(PAUSED_KEY, "$trainId|${pausedAt ?: ""}") } catch (_: Exception) { }
    }

    private fun pauseExpired(): Boolean {
        val at = pausedAt ?: return false
        val now = store?.clock()?.toEpochMilliseconds() ?: return false
        return now - at >= PAUSE_MS
    }

    private fun status(s: PassiveStatus) = _state.update { it.copy(status = if (it.optedIn) s else PassiveStatus.OFF) }

    fun attach(store: PassengerStore) {
        this.store = store
        // A check-out the passenger made (the EasyRide stop, the notification's action) pauses this train, and the
        // status says so at once (the scan loop wakes on the same change and re-checks the beacon).
        scope.launch {
            var prev = store.state.value
            store.state.collect { s ->
                if (prev.phase == CheckInPhase.CHECKED_IN && s.phase == CheckInPhase.IDLE) {
                    setPaused(prev.trainId)
                    status(if (prev.trainId != null) PassiveStatus.PAUSED else PassiveStatus.WATCHING)
                }
                prev = s
            }
        }
        if (_state.value.optedIn) start()
    }

    /**
     * The one-time opt-in. Asks for Bluetooth and notifications (call it from a tap), remembers the choice and starts
     * watching. Returns false if the Bluetooth permission was refused; the opt-in is then not saved.
     */
    suspend fun optIn(askPermissions: Boolean = true): Boolean {
        val granted = try {
            if (askPermissions) platform.requestScanPermission() else platform.canScan()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        if (!granted) {
            _state.update { it.copy(status = if (it.optedIn) PassiveStatus.NO_PERMISSION else PassiveStatus.OFF) }
            return false
        }
        if (askPermissions) try { notifier.requestPermission() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        try { storage.put(OPT_IN_KEY, "1") } catch (_: Exception) { }
        setPaused(null)
        _state.update { it.copy(optedIn = true, status = PassiveStatus.WATCHING) }
        start()
        scanNow()
        return true
    }

    /** Stops watching and forgets the opt-in. A current check-in stays. */
    fun optOut() {
        try { storage.put(OPT_IN_KEY, "0") } catch (_: Exception) { } // remembered: the default is on
        loop?.cancel()
        loop = null
        _state.value = PassiveState()
    }

    /**
     * "Not on this train?": no passive check-in to the current (or last found) train until [resume], or until a scan
     * no longer hears its beacon. Doesn't check out by itself. A check-out by the passenger pauses the same way.
     */
    fun pause() {
        val train = store?.state?.value?.trainId ?: _state.value.lastBeacon?.replace(BEACON_CAR, "") ?: return
        setPaused(train)
        status(PassiveStatus.PAUSED)
    }

    /** Lifts [pause] and scans now. */
    fun resume() {
        setPaused(null)
        if (_state.value.status == PassiveStatus.PAUSED) status(PassiveStatus.WATCHING)
        scanNow()
    }

    /** Scan now instead of at the next interval (the app came to the front). No-op when not opted in. */
    fun scanNow() {
        if (!_state.value.optedIn) return
        if (loop?.isActive != true) start()
        wake?.cancel()
        wake = null
    }

    private fun start() {
        if (loop?.isActive == true || store == null) return
        loop = scope.launch {
            val store = store ?: return@launch
            while (true) {
                val phase = store.state.value.phase
                if (phase == CheckInPhase.CHECKED_IN) {
                    status(PassiveStatus.CHECKED_IN)
                    store.state.first { it.phase != CheckInPhase.CHECKED_IN }
                    continue
                }
                // IDLE: try. SEARCHING/FOUND: the passenger is checking in by hand right now, leave it alone.
                if (phase == CheckInPhase.IDLE) tryOnce(store)
                // Sleep until the next round, or until scanNow() cuts it short.
                val w = scope.launch { delay(intervalMs) }
                wake = w
                w.join()
            }
        }
    }

    private suspend fun tryOnce(store: PassengerStore) {
        if (!platform.canScan()) { status(PassiveStatus.NO_PERMISSION); return }
        // While a train is paused, the scan that checks whether its beacon is gone doesn't flash "scanning".
        status(if (paused != null) PassiveStatus.PAUSED else PassiveStatus.SCANNING)
        val beacon = try { platformScanner.find() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (beacon == null) {
            // Out of range of the train they checked out of: it may check them in again next time.
            setPaused(null)
            status(PassiveStatus.WATCHING)
            return
        }
        val trainId = beacon.replace(BEACON_CAR, "")
        if (trainId == paused && pauseExpired()) setPaused(null)
        if (trainId == paused) { status(PassiveStatus.PAUSED); return }
        if (store.state.value.phase != CheckInPhase.IDLE) { status(PassiveStatus.WATCHING); return }
        handoff = beacon
        store.findTrain()
        val found = withTimeoutOrNull(5_000) { store.state.first { it.phase != CheckInPhase.SEARCHING } }
        handoff = null
        if (found?.phase == CheckInPhase.FOUND) {
            store.checkInNow()
            if (store.state.value.phase == CheckInPhase.CHECKED_IN) {
                _state.update { it.copy(lastBeacon = beacon) }
                status(PassiveStatus.CHECKED_IN)
                return
            }
        }
        status(PassiveStatus.WATCHING)
    }

    private companion object {
        const val OPT_IN_KEY = "sbb-passive-checkin"
        const val PAUSED_KEY = "sbb-passive-paused"
        const val PAUSE_MS = 10 * 60_000L
        val BEACON_CAR = Regex("-Car([A-Za-z0-9_]+)$")
    }
}
