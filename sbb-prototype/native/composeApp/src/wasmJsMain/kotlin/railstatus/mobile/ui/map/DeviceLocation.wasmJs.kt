package railstatus.mobile.ui.map

import kotlinx.coroutines.await
import kotlin.js.Promise

// "lon,lat[,heading]" or "" (no geolocation, denied, or no fix within 6 s).
private fun geolocate(): Promise<JsString> = js(
    """new Promise((ok) => {
    if (!navigator.geolocation) { ok(''); return; }
    navigator.geolocation.getCurrentPosition(
        (p) => ok(p.coords.longitude + ',' + p.coords.latitude + (p.coords.heading != null && !isNaN(p.coords.heading) ? ',' + p.coords.heading : '')),
        () => ok(''), { timeout: 6000, maximumAge: 300000 });
})""",
)

internal actual suspend fun platformLocation(): DeviceFix? {
    val s = geolocate().await<JsString>().toString()
    val p = s.split(',').mapNotNull { it.toDoubleOrNull() }
    return if (p.size >= 2) DeviceFix(p[0], p[1], p.getOrNull(2)?.toFloat()) else null
}
