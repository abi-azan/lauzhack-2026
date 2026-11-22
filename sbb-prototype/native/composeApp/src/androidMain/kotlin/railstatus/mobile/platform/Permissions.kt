package railstatus.mobile.platform

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * Runtime permissions from shared code, which has no Activity: asks through the activity on screen, if there is
 * one. In the background (a passive check-in) it only reports what's already granted.
 */
object Permissions {
    private var resumed: WeakReference<ComponentActivity>? = null
    private var started = 0
    private var seq = 0

    /** Whether an activity of ours is visible: starting a foreground service is allowed then. */
    val inForeground: Boolean get() = started > 0

    val tracker = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity is ComponentActivity) resumed = WeakReference(activity)
        }
        override fun onActivityPaused(activity: Activity) {
            if (resumed?.get() === activity) resumed = null
        }
        override fun onActivityStarted(activity: Activity) { started++ }
        override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0) }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    fun granted(ctx: Context, perms: Array<String>): Boolean =
        perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    /** Asks for [perms] (main thread); true when all are granted. Without a visible activity: whether they already are. */
    suspend fun request(ctx: Context, perms: Array<String>): Boolean {
        if (perms.isEmpty() || granted(ctx, perms)) return true
        val activity = resumed?.get() ?: return false
        return suspendCancellableCoroutine { cont ->
            var launcher: ActivityResultLauncher<Array<String>>? = null
            launcher = activity.activityResultRegistry.register(
                "railstatus-permission-${seq++}", ActivityResultContracts.RequestMultiplePermissions(),
            ) { _ ->
                launcher?.unregister()
                if (cont.isActive) cont.resume(granted(ctx, perms))
            }
            cont.invokeOnCancellation { launcher.unregister() }
            launcher.launch(perms)
        }
    }
}
