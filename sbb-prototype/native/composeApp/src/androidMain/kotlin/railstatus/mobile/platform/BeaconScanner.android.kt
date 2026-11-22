package railstatus.mobile.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import railstatus.mobile.incident.BeaconScanner
import railstatus.mobile.incident.FakeBeaconScanner
import railstatus.mobile.incident.PassivePlatform
import java.util.concurrent.ConcurrentHashMap

/** What a Bluetooth LE scan needs: BLUETOOTH_SCAN (Android 12+, "never for location"), else fine location. */
class BeaconPermissions(private val ctx: Context) : PassivePlatform {
    private val perms: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun granted(): Boolean = Permissions.granted(ctx, perms)

    /** In `auto` and `fake` mode the opt-in works without Bluetooth (the fake beacon stands in). */
    override suspend fun requestScanPermission(): Boolean =
        Permissions.request(ctx, perms) || DemoConfig.beaconMode(ctx) != DemoConfig.BeaconMode.REAL

    override fun canScan(): Boolean = granted() || DemoConfig.beaconMode(ctx) != DemoConfig.BeaconMode.REAL
}

/**
 * Finds the nearest train beacon: a BLE device advertising a name like "IR90-1734-Car7" (`<train id>-Car<car>`).
 * Picks the strongest one heard within [settleMs] of the first hit. Null when Bluetooth is off, the permission is
 * missing, or nothing turns up within [timeoutMs].
 *
 * The scan has no filter (Android can't filter on a name prefix), so with the screen off Android delivers nothing;
 * passive check-in then happens the next time the screen is on.
 */
class BleBeaconScanner(
    private val ctx: Context,
    private val timeoutMs: Long = 8_000,
    private val settleMs: Long = 1_500,
) : BeaconScanner {
    private val permissions = BeaconPermissions(ctx)

    /** Bluetooth there, on, and allowed. */
    fun available(): Boolean =
        permissions.granted() && ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    override suspend fun find(): String? {
        if (!available()) return null
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return null
        val best = ConcurrentHashMap<String, Int>()
        val firstHit = CompletableDeferred<Boolean>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = seen(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::seen)
            override fun onScanFailed(errorCode: Int) { firstHit.complete(false) }
            private fun seen(r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: return
                if (!BEACON_NAME.matches(name)) return
                best.merge(name, r.rssi, ::maxOf)
                firstHit.complete(true)
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, callback)
            if (withTimeoutOrNull(timeoutMs) { firstHit.await() } != true) return null
            delay(settleMs)
            return best.maxByOrNull { it.value }?.key
        } catch (_: SecurityException) {
            return null
        } finally {
            try { scanner.stopScan(callback) } catch (_: Exception) { }
        }
    }

    companion object {
        /** "IR90-1734-Car7", "IC1-725-Car3". */
        val BEACON_NAME = Regex("^[A-Za-z]+[0-9]*-[0-9]+-Car[A-Za-z0-9_]+$")
    }
}

/** The scanner the app uses: real, fake or both, per [DemoConfig.beaconMode], read at every search. */
class DemoBeaconScanner(private val ctx: Context) : BeaconScanner {
    private val ble = BleBeaconScanner(ctx)
    private val autoBle = BleBeaconScanner(ctx, timeoutMs = 4_000)

    override suspend fun find(): String? = when (DemoConfig.beaconMode(ctx)) {
        DemoConfig.BeaconMode.FAKE -> FakeBeaconScanner(DemoConfig.fakeBeacon(ctx)).find()
        DemoConfig.BeaconMode.REAL -> ble.find()
        DemoConfig.BeaconMode.AUTO ->
            if (autoBle.available()) autoBle.find() ?: DemoConfig.fakeBeacon(ctx)
            else FakeBeaconScanner(DemoConfig.fakeBeacon(ctx)).find()
    }
}
