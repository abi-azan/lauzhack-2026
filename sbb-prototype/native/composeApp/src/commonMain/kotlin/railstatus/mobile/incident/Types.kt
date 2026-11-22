@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.incident

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import railstatus.model.Alternative
import railstatus.model.DelayRange
import railstatus.model.Lang
import railstatus.model.Stage
import railstatus.model.Text
import kotlin.math.abs
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Port of mobile/src/incident/types.ts: the shapes the passenger screens need. The languages, stages,
// texts and delay ranges are the backend's own (railstatus.model): Lang, Stage, Text, DelayRange.

/** The four languages, in the order the web app lists them. */
val LANGS: List<Lang> = listOf(Lang.EN, Lang.DE, Lang.FR, Lang.IT)

/** Status-page stages, in order. (The model's `detected` is internal and never reaches passengers.) */
val STAGES: List<Stage> = listOf(Stage.INVESTIGATING, Stage.IDENTIFIED, Stage.MONITORING, Stage.RESOLVED)

/** One text in the four languages, built in the web app's key order. */
fun text(en: String, de: String, fr: String, it: String): Text =
    linkedMapOf(Lang.EN to en, Lang.DE to de, Lang.FR to fr, Lang.IT to it)

/** `tr(t, lang)`: the text in [lang], else English. */
fun tr(t: Text, lang: Lang): String = t[lang] ?: t[Lang.EN] ?: ""

/** A stop of the scripted train: minutes after check-in (the fake train runs relative to the demo). */
data class TrainStop(val station: String, val offsetMin: Int)

/** A stop as the screens use it, in both modes: a planned time on today's clock. */
data class StopView(
    val station: String,
    /** DIDOK number, when the backend sent one. */
    val stationId: String? = null,
    val planned: Instant,
)

/** The passenger's train as the screens use it, built from the fake script or the backend. */
data class TrainView(
    val id: String,
    /** "IR", "IC", ... (the web's `Product`; the backend may send others). */
    val product: String,
    /** Line number for the badge: "90" for IR 90. */
    val line: String,
    val number: String,
    val direction: String,
    val stops: List<StopView>,
    /** From the backend's timetable: don't grey out "past" stops by the phone's clock. */
    val live: Boolean = false,
) {
    /** CheckIn.tsx `depStop`: where the train was at [at], its last stop at or before then. */
    fun depStop(at: Instant): StopView {
        var dep = stops[0]
        for (s in stops) if (s.planned <= at) dep = s
        return dep
    }

    /** CheckIn.tsx `nearestStop`: the stop nearest in time, where they got off when they stop EasyRide. */
    fun nearestStop(at: Instant): StopView = stops.reduce { a, s ->
        if (abs((s.planned - at).inWholeMilliseconds) < abs((a.planned - at).inWholeMilliseconds)) s else a
    }
}

/** The scripted demo train (types.ts `Train`). */
data class ScriptTrain(
    /** "IR90-1734": what the beacon name and the QR link carry. */
    val id: String,
    val product: String,
    val line: String,
    val number: String,
    val direction: String,
    val stops: List<TrainStop>,
)

/**
 * Who the passenger is, as far as SBB knows. Logged-in travellers bring their ticket,
 * and a ticket with a route tells us where they get off, so we never ask.
 */
@Serializable
enum class PersonaId {
    @SerialName("supersaver") SUPERSAVER,
    @SerialName("ticket") TICKET,
    @SerialName("ga") GA,
    @SerialName("guest") GUEST,
}

enum class TicketKind { SUPERSAVER, POINT_TO_POINT, GA }

data class Ticket(val kind: TicketKind, val label: String, val from: String? = null, val to: String? = null)

data class Persona(
    val id: PersonaId,
    /** Short label for the demo controls. */
    val label: String,
    val loggedIn: Boolean,
    val ticket: Ticket? = null,
)

/** An update as the admin writes it (types.ts `IncidentUpdate`; here the scripted ones). */
data class ScriptedUpdate(
    val stage: Stage,
    val title: Text,
    val body: Text,
    /** Expected delay of this train after this update. */
    val delay: DelayRange? = null,
    /** When the next update is promised, in minutes after this one. */
    val nextInMin: Int? = null,
    /** Facts this update publishes, like the backend's `IncidentFacts`. The chat answers from them. */
    val cause: Text? = null,
    val alternatives: List<Alternative>? = null,
    val onboard: Text? = null,
)

/** An update once it went out to the passengers on the train. */
data class PublishedUpdate(
    val stage: Stage,
    val title: Text,
    val body: Text,
    val delay: DelayRange? = null,
    val nextInMin: Int? = null,
    val cause: Text? = null,
    val alternatives: List<Alternative>? = null,
    val onboard: Text? = null,
    val index: Int,
    val at: Instant,
    val nextBy: Instant? = null,
)

data class Incident(
    val id: String,
    val trainId: String,
    /** Affected section, e.g. "Renens VD – Allaman". */
    val section: String,
    val stage: Stage,
    /** Oldest first. */
    val updates: List<PublishedUpdate>,
    val latest: PublishedUpdate,
)
