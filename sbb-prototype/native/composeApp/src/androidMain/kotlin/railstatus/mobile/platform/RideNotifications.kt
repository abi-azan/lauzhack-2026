package railstatus.mobile.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import railstatus.mobile.AppGraph
import railstatus.mobile.MainActivity
import railstatus.mobile.R
import railstatus.mobile.incident.CheckInPhase
import railstatus.mobile.incident.Conn
import railstatus.mobile.incident.PassiveStatus
import railstatus.mobile.incident.STAGE_LABEL
import railstatus.mobile.incident.UpdateNotice
import railstatus.mobile.incident.hhmm
import railstatus.mobile.incident.tr
import railstatus.model.Stage

/**
 * The one ride notification (same id every time, so it updates in place), ported from android/'s LiveNotifier:
 *
 * - watching for the beacon (opted in, not checked in): a quiet "ready to check you in";
 * - checked in, no incident: the train, the car, the journey's progress, quiet;
 * - an incident: what happened to this passenger's train (title) and the official text ending with "Next update by
 *   HH:MM" (body), on the high-importance channel. HeadsUp/NotificationPolicy decide whether a new update alerts:
 *   a big change is posted with [alert] = true (sound, vibration, heads-up, also on the lock screen); everything
 *   else, and every re-render here, is posted with `setOnlyAlertOnce`, which replaces the text silently.
 *
 * Android 16+ (API 36): a Live Update (ProgressStyle along the stops, status-bar chip). Older: a standard ongoing
 * notification with a progress bar.
 */
object RideNotifications {
    const val ID = 1001
    /** v2: the first channel asked to bypass Do Not Disturb. A channel keeps its settings, so it has a new id. */
    const val CH_ALERTS = "sbb_disruptions_v2"
    private const val CH_ALERTS_OLD = "sbb_disruptions"
    // v2: silent and minimal (IMPORTANCE_MIN: no status-bar icon, no lock screen, a collapsed row in the shade). Only a
    // disruption should reach the passenger; being checked in or watching for a beacon is background state that
    // Android still requires a notification for. A channel keeps its first settings, hence the new ids.
    const val CH_JOURNEY = "sbb_journey_v2"
    const val CH_WATCH = "sbb_train_detection_v2"

    /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING (Android 16 Live Updates). */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    private val RED = Color.rgb(0xEB, 0x00, 0x00)
    private val INK = Color.rgb(0x2D, 0x32, 0x7D)
    private val GREEN = Color.rgb(0x00, 0x79, 0x51)
    private val VIBRATION = longArrayOf(0, 300, 150, 300)

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Disruptions on your journey", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Live updates when your train is disrupted: what happened, what it means for you, when we know more."
                enableVibration(true)
                vibrationPattern = VIBRATION
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build(),
                )
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                // Do Not Disturb wins: a disruption is important, but not an emergency. The user can still let the
                // channel through in the system settings.
            },
        )
        nm.deleteNotificationChannel(CH_ALERTS_OLD)
        nm.deleteNotificationChannel("sbb_journey")
        nm.deleteNotificationChannel("sbb_train_detection")
        nm.createNotificationChannel(
            NotificationChannel(CH_JOURNEY, "Checked in (background)", NotificationManager.IMPORTANCE_MIN).apply {
                description = "While you're checked in: your train and how far along it is."
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCH, "Automatic check-in (background)", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while SBB Mobile looks for your train's beacon to check you in."
            },
        )
    }

    /** The journey bar: one segment per leg between stops, a point per intermediate stop, minutes since departure. */
    data class Progress(val segments: List<Int>, val points: List<Int>, val position: Int) {
        val total: Int get() = segments.sum()
    }

    /** Everything the notification shows; compared to skip identical re-posts. */
    data class Ride(
        val needed: Boolean,
        val checkedIn: Boolean,
        val title: String,
        val text: String,
        val sub: String?,
        val chip: String?,
        val trainId: String?,
        val incident: Boolean,
        val disrupted: Boolean,
        val resolved: Boolean,
        val progress: Progress?,
        /** When the latest update was published (epoch ms), for the notification's time. */
        val at: Long? = null,
    )

    /** The ride as the graph has it now. [notice]: the update being announced, if any (else the latest's). */
    fun snapshot(graph: AppGraph, notice: UpdateNotice? = null): Ride {
        val store = graph.passenger
        val s = store.state.value
        val passive = graph.passive.state.value
        val checkedIn = s.phase == CheckInPhase.CHECKED_IN ||
            (s.phase == CheckInPhase.FOUND && s.checkedInAt != null) // re-checking in after a backend reset
        if (!checkedIn) {
            val (title, text) = when (passive.status) {
                PassiveStatus.PAUSED -> "Checked out" to "We'll check you in automatically on your next train."
                PassiveStatus.NO_PERMISSION -> "Automatic check-in is paused" to "Allow Bluetooth for SBB Mobile to find your train."
                PassiveStatus.SCANNING -> "Looking for your train…" to "SBB Mobile checks you in when it finds the train's beacon."
                else -> "Ready to check you in" to "When you board, SBB Mobile finds the train's beacon and checks you in."
            }
            return Ride(passive.optedIn, false, title, text, null, null, null, false, false, false, null)
        }

        val train = store.currentTrain()
        val zone = store.zone
        val now = store.clock()
        val label = "${train.product} ${train.line} → ${train.direction}".replace("  ", " ")
        // Only a reconnect is worth a word in the header; "Live"/"Demo" was developer talk.
        val live = when (s.conn) {
            Conn.CONNECTING, Conn.RETRYING -> "Reconnecting…"
            else -> null
        }

        val progress = train.stops.takeIf { it.size >= 2 }?.let { stops ->
            val start = stops.first().planned
            val segments = stops.zipWithNext { a, b -> (b.planned - a.planned).inWholeMinutes.toInt().coerceAtLeast(1) }
            val points = segments.dropLast(1).runningReduce(Int::plus)
            Progress(segments, points, (now - start).inWholeMinutes.toInt().coerceIn(0, segments.sum()))
        }

        val incident = store.currentIncident()
        if (incident == null) {
            return Ride(
                needed = true, checkedIn = true, title = label,
                // Plain, like SBB's journey notification: where you are, nothing promised.
                text = s.car?.let { "Car $it" } ?: "Checked in",
                sub = live, chip = null, trainId = s.trainId ?: train.id,
                incident = false, disrupted = false, resolved = false, progress = progress,
            )
        }
        val latest = incident.latest
        val n = notice ?: graph.headsUp.noticeFor(incident, latest)
        val resolved = latest.stage == Stage.RESOLVED
        val stage = tr(STAGE_LABEL.getValue(latest.stage), s.lang)
        val delay = latest.delay?.let { if (it.low == it.high) "+${it.low}'" else "+${it.low}–${it.high}'" }
        return Ride(
            needed = true, checkedIn = true,
            title = n.title,
            text = n.body,
            // No sub text: the title and body say it, and a status-page stage ("Identified") isn't SBB's language.
            sub = null,
            chip = delay ?: latest.nextBy?.takeIf { !resolved }?.let { hhmm(it, zone) } ?: stage,
            trainId = incident.trainId.ifEmpty { s.trainId ?: train.id },
            incident = true, disrupted = !resolved, resolved = resolved, progress = progress,
            at = latest.at.toEpochMilliseconds(),
        )
    }

    /** Posts (or replaces) the ride notification. */
    fun post(ctx: Context, ride: Ride, alert: Boolean) {
        if (!ride.needed) return
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, ride, alert))
    }

    fun cancel(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(ID)

    fun build(ctx: Context, ride: Ride, alert: Boolean): Notification {
        val channel = when {
            ride.incident -> CH_ALERTS
            ride.checkedIn -> CH_JOURNEY
            else -> CH_WATCH
        }
        val open = openIntent(ctx, ride.trainId?.takeIf { ride.incident })
        val action = if (ride.checkedIn) "Check out" to RideService.ACTION_CHECK_OUT else "Turn off" to RideService.ACTION_STOP_WATCHING
        val actionIntent = PendingIntent.getForegroundService(
            ctx, 1, Intent(ctx, RideService::class.java).setAction(action.second), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // A Live Update (status-bar chip) only while there's a disruption; checked in with nothing wrong stays silent.
        if (Build.VERSION.SDK_INT >= 36 && ride.checkedIn && ride.incident) return liveUpdate(ctx, channel, ride, alert, open, action.first, actionIntent)

        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_train)
            .setColor(if (ride.resolved) GREEN else RED)
            .setContentTitle(ride.title)
            .setContentText(ride.text)
            .setSubText(ride.sub)
            .setStyle(NotificationCompat.BigTextStyle().bigText(ride.text))
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setPriority(if (ride.incident) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_MIN)
            .setCategory(if (ride.incident) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(ride.incident)
            .setWhen(ride.at ?: System.currentTimeMillis())
            .setForegroundServiceBehavior(if (ride.incident) NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE else NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stat_train, action.first, actionIntent)
        // Pre-O only: from Android 8 the channel's sound and vibration pattern apply (CH_ALERTS has both, so a
        // phone in vibrate mode still buzzes; mute mode silences everything, and we don't fight the ringer mode).
        if (alert) b.setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
        ride.progress?.let { b.setProgress(it.total, it.position, false) }
        return b.build()
    }

    @RequiresApi(36)
    private fun liveUpdate(
        ctx: Context, channel: String, ride: Ride, alert: Boolean, open: PendingIntent, actionLabel: String, action: PendingIntent,
    ): Notification {
        val color = when {
            ride.resolved -> GREEN
            ride.disrupted -> RED
            else -> INK
        }
        val icon = Icon.createWithResource(ctx, R.drawable.ic_stat_train)
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_train)
            .setColor(RED)
            .setContentTitle(ride.title)
            .setContentText(ride.text)
            .setSubText(ride.sub)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setCategory(if (ride.incident) Notification.CATEGORY_STATUS else Notification.CATEGORY_NAVIGATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(ride.incident)
            .setWhen(ride.at ?: System.currentTimeMillis())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(icon, actionLabel, action).build())
            .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        ride.chip?.let { b.setShortCriticalText(it) }
        val p = ride.progress
        if (p != null) {
            b.setStyle(
                Notification.ProgressStyle()
                    .setStyledByProgress(true)
                    .setProgressSegments(p.segments.map { Notification.ProgressStyle.Segment(it).setColor(color) })
                    .setProgressPoints(p.points.filter { it in 1 until p.total }.map { Notification.ProgressStyle.Point(it).setColor(color) })
                    .setProgress(p.position)
                    .setProgressTrackerIcon(icon),
            )
        } else {
            b.setStyle(Notification.BigTextStyle().bigText(ride.text))
        }
        return b.build()
    }

    /** Tapping opens the incident (`railstatus://incident/<trainId>`, routed by nav/DeepLinks), else the app. */
    private fun openIntent(ctx: Context, incidentTrain: String?): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (incidentTrain != null) intent.setAction(Intent.ACTION_VIEW).setData(Uri.parse("railstatus://incident/$incidentTrain"))
        else intent.setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
