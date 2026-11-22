package railstatus.mobile.platform

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import railstatus.mobile.AppGraph
import railstatus.mobile.incident.PersonaId
import railstatus.mobile.incident.TRAIN

/**
 * Demo settings, changeable without a rebuild through intent extras (each one is remembered):
 *
 * ```
 * adb shell am start -n railstatus.mobile/.MainActivity \
 *   --es api https://<tunnel>.trycloudflare.com   # backend; "off" = offline demo (scripted incident)
 *   --es beacon auto|real|fake                   # how the beacon search works (default auto)
 *   --es train IR90-1734 --es car 7              # what the fake beacon "finds"
 *   --es persona supersaver|ticket|ga|guest
 *   --es passive on|off                          # opt in to passive check-in (as the UI's button would) / forget it
 *   --ez checkin true | --ez checkout true       # check in to the fake beacon's train now / check out
 * ```
 */
object DemoConfig {
    enum class BeaconMode {
        /** A real Bluetooth scan when Bluetooth and its permission are there; the fake beacon when it finds nothing. */
        AUTO,
        /** Only a real Bluetooth scan: no beacon, no check-in. */
        REAL,
        /** Always "finds" the fake beacon after 1.8 s, like the web app. */
        FAKE,
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("railstatus-demo", Context.MODE_PRIVATE)

    fun beaconMode(ctx: Context): BeaconMode =
        prefs(ctx).getString("beacon", null)?.let { m -> BeaconMode.entries.firstOrNull { it.name.equals(m, true) } } ?: BeaconMode.AUTO

    /** The fake beacon's name, "IR90-1734-Car7" unless `train`/`car` were set. */
    fun fakeBeacon(ctx: Context): String {
        val p = prefs(ctx)
        return "${p.getString("train", null) ?: TRAIN.id}-Car${p.getString("car", null) ?: "7"}"
    }

    /** Probed at start when no backend was set. If it doesn't answer, the app runs the offline demo until `--es api <url>` names one. */
    /** The live demo server, so a freshly installed phone works with no setup; `--es api …` still overrides it. */
    const val LIVE_API = "https://consequence-technology-intervention-take.trycloudflare.com"

    fun defaultApi(): String = LIVE_API

    /** Applies the extras of an intent that opened the app. */
    fun apply(ctx: Context, intent: Intent?, graph: AppGraph) {
        val x = intent?.extras ?: return
        val edit = prefs(ctx).edit()
        x.getString("beacon")?.let { edit.putString("beacon", it.lowercase()) }
        x.getString("train")?.let { edit.putString("train", it) }
        x.getString("car")?.let { edit.putString("car", it) }
        edit.apply()
        x.getString("api")?.let { graph.passenger.setApi(it) }
        x.getString("persona")?.let { p -> PersonaId.entries.firstOrNull { it.name.equals(p, true) }?.let(graph.passenger::setPersona) }
        when (x.getString("passive")) {
            "off" -> graph.passive.optOut()
            // The opt-in the UI offers, from adb: once the activity is on screen (it may ask for permissions).
            "on" -> MainScope().launch { delay(800); graph.passive.optIn() }
        }
        if (x.getBoolean("checkout", false)) graph.passenger.checkOut()
        if (x.getBoolean("checkin", false)) graph.passenger.quickCheckIn()
        // Waiting at the station (demo phone 3): --es follow IR90-1734 --es at 8501120 [--es dest <station id>],
        // what "Notify me about this train" does from a departure's journey.
        x.getString("follow")?.let { train ->
            val at = x.getString("at") ?: "8501120" // Lausanne
            MainScope().launch { delay(800); graph.passenger.follow(train, at, x.getString("dest")) }
        }
    }
}
