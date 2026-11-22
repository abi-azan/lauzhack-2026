@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.incident

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * A [Notifier] that can also replace the incident's notification without alerting (UX-SPEC P2: "everything else
 * updates silently"). Android and the browser implement it; a notifier without it only hears about alerts.
 */
interface SilentNotifier {
    /** Replaces the notification of [UpdateNotice.tag] with [notice], with no sound, vibration or heads-up. */
    fun update(notice: UpdateNotice)
}

/**
 * One notification per incident, updated in place (UX-SPEC P2/P5, rules in [NotificationPolicy]). For each new
 * update of the passenger's incident:
 * - a big change (the first notice, a new stage, new alternatives or instructions, resolved) alerts: [Notifier.show]
 *   (sound, vibration, heads-up). Only when the platform can't notify (permission refused, no notification support)
 *   does [shown], the in-app banner that hides itself after 8 s, stand in for it, so the passenger never gets the
 *   same news twice;
 * - anything else replaces the notification silently ([SilentNotifier.update]); no banner.
 *
 * Only fresh news alerts: an update older than 2 minutes (e.g. checking in to a train whose incident is old)
 * replaces the text silently. The same update seen again (the server's `hello` after a reconnect, a restart, a
 * re-check-in) does nothing.
 */
class HeadsUp(
    private val store: PassengerStore,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
) {
    private val _shown = MutableStateFlow<PublishedUpdate?>(null)

    /** The update the in-app banner shows, or null. */
    val shown: StateFlow<PublishedUpdate?> = _shown.asStateFlow()

    private val _lastChange = MutableStateFlow<NoticeChange?>(null)

    /** Why the notification last changed (for the demo controls and tests). */
    val lastChange: StateFlow<NoticeChange?> = _lastChange.asStateFlow()

    /** What we last notified about. It outlives a moment without an incident (a reconnect drops it briefly). */
    private var last: NoticeMark? = store.currentIncident()?.let(::markOf)
    private var hideJob: Job? = null

    init {
        scope.launch { store.incident.collect(::onIncident) }
    }

    private fun markOf(incident: Incident) = NotificationPolicy.markOf(incident, store.state.value.live?.incident)

    private fun onIncident(incident: Incident?) {
        if (incident == null) {
            _shown.value = null
            return
        }
        val mark = markOf(incident)
        val change = NotificationPolicy.change(last, mark) ?: return
        last = mark
        if (store.state.value.phase != CheckInPhase.CHECKED_IN) return
        _lastChange.value = change
        val latest = incident.latest
        val notice = noticeFor(incident, latest)
        val fresh = store.clock() - latest.at <= 2.minutes
        if (!change.alerts || !fresh) {
            (notifier as? SilentNotifier)?.update(notice)
            return
        }
        notifier.show(notice)
        if (notifier.canNotify()) return
        _shown.value = latest
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(8000)
            _shown.value = null
        }
    }

    /** The banner was tapped (open the incident) or swiped away. */
    fun dismiss() {
        hideJob?.cancel()
        _shown.value = null
    }

    /**
     * What the notification says ([NotificationPolicy.title], [NotificationPolicy.body]): what happened to this
     * passenger's train, then the official text, shortened, ending with "Next update by HH:MM."
     */
    fun noticeFor(incident: Incident, update: PublishedUpdate): UpdateNotice {
        val lang = store.state.value.lang
        val train = store.currentTrain()
        val shownIncident = if (update == incident.latest) incident else incident.copy(latest = update, stage = update.stage)
        return UpdateNotice(
            incidentId = incident.id,
            tag = "incident-${incident.id}",
            stage = update.stage,
            appLine = "SBB Mobile · ${hhmm(update.at, store.zone)} · ${tr(STAGE_LABEL.getValue(update.stage), lang)}",
            title = NotificationPolicy.title(shownIncident, train),
            body = NotificationPolicy.body(update, tr(update.body, lang), train, store.currentPerson().dest, store.zone),
            at = update.at,
            update = update,
        )
    }
}
