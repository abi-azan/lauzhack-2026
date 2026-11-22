package railstatus.mobile.platform

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import railstatus.mobile.RailApp
import railstatus.mobile.incident.Notifier
import railstatus.mobile.incident.SilentNotifier
import railstatus.mobile.incident.UpdateNotice

/**
 * System notifications, all on the one ride notification (the one the [RideService] keeps up to date), so an
 * incident is always a single notification, replaced in place. HeadsUp decides (NotificationPolicy):
 * [show] for a big change re-posts it with alerting on (sound, vibration, heads-up, also on the lock screen);
 * [update] re-posts it with `setOnlyAlertOnce`, which changes the text without a sound.
 */
class AndroidNotifier(private val ctx: Context) : Notifier, SilentNotifier {
    override suspend fun requestPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) Permissions.request(ctx, arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        return canNotify()
    }

    override fun canNotify(): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    override fun show(notice: UpdateNotice) = post(notice, alert = true)

    override fun update(notice: UpdateNotice) = post(notice, alert = false)

    private fun post(notice: UpdateNotice, alert: Boolean) {
        val graph = RailApp.instance.graph
        RideNotifications.post(ctx, RideNotifications.snapshot(graph, notice), alert)
        RideService.sync(ctx)
    }
}
