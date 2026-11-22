package railstatus.mobile.ui.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the phone is: longitude, latitude and, when moving, the heading (degrees clockwise from north). */
data class DeviceFix(val lon: Double, val lat: Double, val bearing: Float? = null)

/** The platform's location (Android: LocationManager after the permission; web: navigator.geolocation), or null. */
internal expect suspend fun platformLocation(): DeviceFix?

/** The phone's location for the maps (SBB's blue dot); asked once per map opening, kept between maps. */
internal object DeviceLocation {
    var fix: DeviceFix? by mutableStateOf(null)
        private set
    private val lock = Mutex()

    suspend fun refresh(): DeviceFix? = lock.withLock {
        val f = try { platformLocation() } catch (e: Throwable) { println("map: no location: $e"); null }
        if (f != null) fix = f
        fix
    }
}
