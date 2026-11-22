@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.incident

import kotlinx.coroutines.delay
import railstatus.model.Stage
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// What the platform supplies. Android: a Bluetooth LE scan, system notifications, SharedPreferences.
// Web (wasmJs): the fake scanner, browser notifications, localStorage. Tests: the fakes below.

/** Finds the train's Bluetooth beacon. */
fun interface BeaconScanner {
    /**
     * Scans for the nearest train beacon and returns its advertised name, e.g. "IR90-1734-Car7"
     * (`<train id>-Car<car>`), or null when none was found. Called from a coroutine; cancel it to stop.
     */
    suspend fun find(): String?
}

/** store.ts's fake search: "finds" [beacon] after [searchMs], like the web's 1.8 s spinner. */
class FakeBeaconScanner(
    private val beacon: String = FAKE_BEACON,
    private val searchMs: Long = 1800,
) : BeaconScanner {
    override suspend fun find(): String? {
        delay(searchMs)
        return beacon
    }
}

/** A new incident update, as a heads-up notification shows it (Layer.tsx `HeadsUp`). */
data class UpdateNotice(
    val incidentId: String,
    /** The same for every update of an incident, so each one replaces the previous notification. */
    val tag: String,
    val stage: Stage,
    /** "SBB Mobile · 17:42 · Identified" */
    val appLine: String,
    val title: String,
    /** What changes for this passenger (their arrival), else the update's body. */
    val body: String,
    val at: Instant,
    val update: PublishedUpdate,
)

/** Shows notifications. The web app's push and its simulated heads-up both become this. */
interface Notifier {
    /** Asks for permission to notify (Android 13+, browsers). Called inside the check-in gesture. True when allowed. */
    suspend fun requestPermission(): Boolean

    /** Whether notifications can be shown now. */
    fun canNotify(): Boolean

    /** Shows (or replaces, by [UpdateNotice.tag]) a heads-up for a new update, with a short vibration. */
    fun show(notice: UpdateNotice)
}

/** Shows nothing. Records what it was asked to show, for tests. */
class FakeNotifier(var permitted: Boolean = true) : Notifier {
    val shown = mutableListOf<UpdateNotice>()
    override suspend fun requestPermission(): Boolean = permitted
    override fun canNotify(): Boolean = permitted
    override fun show(notice: UpdateNotice) { shown.add(notice) }
}

/** Small persistent strings (the web's localStorage). Implementations may throw; the stores carry on without. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class MemoryKeyValueStore : KeyValueStore {
    val map = LinkedHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}
