@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.incident

import kotlinx.datetime.TimeZone
import railstatus.model.IncidentFacts
import railstatus.model.Lang
import railstatus.model.Stage
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// UX-SPEC P2/P5: one notification per incident, updated in place. It alerts (sound, vibration, heads-up, the in-app
// banner) only on a big change; everything else replaces the text silently. The platforms only post what this says.

/** Why the incident's notification changes, and whether that alerts. */
enum class NoticeChange(val alerts: Boolean) {
    /** The first update of an incident this phone hears about. */
    FIRST(true),
    /** Investigating → identified → monitoring. */
    STAGE(true),
    /** New or changed alternatives, or new on-board instructions. */
    ADVICE(true),
    /** The incident is over. */
    RESOLVED(true),
    /** Anything else (a reworded text, a new "next update by"): replaced in place, silently. */
    MINOR(false),
}

/** What the policy compares between two states of an incident: its latest update and the advice it published. */
data class NoticeMark(
    val incidentId: String,
    /** The latest update's index (scripted) or `seq` (backend). */
    val index: Int,
    val stage: Stage,
    /** Each published alternative as "id: text", so a changed time also counts. */
    val alternatives: Set<String> = emptySet(),
    /** On-board instructions ("staff are handing out water"), if any. */
    val onboard: String? = null,
)

object NotificationPolicy {
    /**
     * What changed from [prev] (the last state we notified about, or null) to [next]; null when nothing is new
     * (the same update again, e.g. the server's `hello` after a reconnect or a restart).
     */
    fun change(prev: NoticeMark?, next: NoticeMark): NoticeChange? = when {
        prev == null || prev.incidentId != next.incidentId -> if (next.stage == Stage.RESOLVED) NoticeChange.RESOLVED else NoticeChange.FIRST
        next.index <= prev.index -> null
        next.stage == Stage.RESOLVED && prev.stage != Stage.RESOLVED -> NoticeChange.RESOLVED
        next.stage != prev.stage -> NoticeChange.STAGE
        next.alternatives != prev.alternatives -> NoticeChange.ADVICE
        next.onboard != null && next.onboard != prev.onboard -> NoticeChange.ADVICE
        else -> NoticeChange.MINOR
    }

    /**
     * The mark of [incident] now. Live mode passes the backend's [facts] (alternatives and on-board info are
     * per incident there); the scripted updates carry their own, and an update without them keeps the last ones.
     */
    fun markOf(incident: Incident, facts: IncidentFacts? = null): NoticeMark {
        val f = facts?.takeIf { it.id == incident.id }
        val alternatives = f?.alternatives ?: incident.updates.lastOrNull { it.alternatives != null }?.alternatives.orEmpty()
        val onboard = if (f != null) f.onboard else incident.updates.lastOrNull { it.onboard != null }?.onboard
        return NoticeMark(
            incidentId = incident.id,
            index = incident.latest.index,
            stage = incident.latest.stage,
            alternatives = alternatives.map { "${it.id}: ${tr(it.text, Lang.EN)}" }.toSet(),
            onboard = onboard?.let { tr(it, Lang.EN) }?.ifEmpty { null },
        )
    }

    /** "IR 90" */
    fun trainName(train: TrainView): String = "${train.product} ${train.line}".trim().replace("  ", " ")

    /**
     * The title: what happened to *my* train. "IR 90 stopped before Morges" (the next stop when the incident
     * started), "IR 90 moving again" (monitoring), "IR 90 continues" (resolved).
     */
    fun title(incident: Incident, train: TrainView): String {
        val name = trainName(train)
        return when (incident.latest.stage) {
            Stage.RESOLVED -> "$name continues"
            Stage.MONITORING -> "$name moving again"
            else -> stoppedBefore(train, incident.updates.first().at)?.let { "$name stopped before $it" } ?: "Disruption on $name"
        }
    }

    /** The train's next stop at [at], if it was under way then. */
    private fun stoppedBefore(train: TrainView, at: Instant): String? {
        val stops = train.stops
        if (stops.size < 2 || stops.first().planned > at) return null
        return stops.firstOrNull { it.planned > at }?.station
    }

    /**
     * The body. While open: the official text, shortened, always ending with "Next update by HH:MM.".
     * Resolved: "Arrival Lausanne about 18:20 (+29 min)." when we know where they get off and the delay,
     * else the official text.
     */
    fun body(update: PublishedUpdate, official: String, train: TrainView, dest: String?, zone: TimeZone): String {
        if (update.stage == Stage.RESOLVED) {
            val stop = dest?.let { d -> train.stops.firstOrNull { it.station == d } }
            val delay = update.delay
            if (stop != null && delay != null) {
                val late = if (delay.low == delay.high) "+${delay.high} min" else "+${delay.low}–${delay.high} min"
                return "Arrival ${stop.station} about ${hhmm(addMin(stop.planned, delay.high), zone)} ($late)."
            }
            return shorten(official)
        }
        val next = update.nextBy?.let { "Next update by ${hhmm(it, zone)}." }
        return listOfNotNull(shorten(official).ifEmpty { null }, next).joinToString(" ")
    }

    private val NEXT_UPDATE = Regex(
        "next (update|information)|further information|nächste information|weitere information|prochaine information|" +
            "nouvelles? informations?|autres informations|prossima informazione|ulteriori informazioni",
        RegexOption.IGNORE_CASE,
    )
    // A sentence ends at . ! ? but not after an abbreviation that comes before a time ("um ca. 07:35", "env. 07:35").
    private val SENTENCE_END = Regex("(?<!\\b(?:ca|env|approx|ev)\\.)(?<=[.!?])\\s+")

    /**
     * The official text in at most [max] characters: whole sentences while they fit (the text's own "next update"
     * sentence is dropped, we add ours), else the first sentence cut at a word with "…".
     */
    /** [text] without its own "next update" sentences, whole otherwise: the caller adds the one time it trusts. */
    fun withoutNextUpdate(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").split(SENTENCE_END).filter { it.isNotBlank() && !NEXT_UPDATE.containsMatchIn(it) }.joinToString(" ")

    fun shorten(text: String, max: Int = 140): String {
        val sentences = text.trim().replace(Regex("\\s+"), " ").split(SENTENCE_END)
            .filter { it.isNotBlank() && !NEXT_UPDATE.containsMatchIn(it) }
        val out = StringBuilder()
        for (s in sentences) {
            val add = if (out.isEmpty()) s.length else s.length + 1
            if (out.length + add > max) break
            if (out.isNotEmpty()) out.append(' ')
            out.append(s)
        }
        if (out.isNotEmpty() || sentences.isEmpty()) return out.toString()
        val first = sentences.first()
        val cut = first.lastIndexOf(' ', max - 1).takeIf { it > max / 2 } ?: (max - 1)
        return first.substring(0, cut).trimEnd(',', ';', ':', ' ') + "…"
    }
}
