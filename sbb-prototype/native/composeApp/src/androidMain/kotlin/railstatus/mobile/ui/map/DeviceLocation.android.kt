package railstatus.mobile.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import railstatus.mobile.RailApp
import railstatus.mobile.platform.Permissions
import kotlin.coroutines.resume

private val PERMS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

@SuppressLint("MissingPermission")
internal actual suspend fun platformLocation(): DeviceFix? {
    val ctx = RailApp.instance
    if (!Permissions.granted(ctx, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))) Permissions.request(ctx, PERMS)
    if (!Permissions.granted(ctx, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))) return null
    val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
    val providers = lm.getProviders(true)
    // A fresh fix first (the emulator's `geo fix` and a phone that hasn't asked lately have no last location).
    val last = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    var loc: Location? = last?.takeIf { System.currentTimeMillis() - it.time < 120_000 }
    if (loc == null && Build.VERSION.SDK_INT >= 30) {
        for (p in listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (p !in providers) continue
            loc = withTimeoutOrNull(2500) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val cancel = android.os.CancellationSignal()
                    cont.invokeOnCancellation { cancel.cancel() }
                    lm.getCurrentLocation(p, cancel, ContextCompat.getMainExecutor(ctx)) { if (cont.isActive) cont.resume(it) }
                }
            }
            if (loc != null) break
        }
    }
    if (loc == null) loc = last
    return loc?.let { DeviceFix(it.longitude, it.latitude, if (it.hasBearing() && it.speed > 0.5f) it.bearing else null) }
}
