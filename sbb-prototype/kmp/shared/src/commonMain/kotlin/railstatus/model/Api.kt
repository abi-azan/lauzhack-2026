package railstatus.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The one Json config for both sides: `type` is the class discriminator, nulls are left out, unknown keys are ignored. */
val RailJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

// ---- REST bodies --------------------------------------------------------------------------------

/** `POST /api/checkins` */
@Serializable
data class CheckInRequest(
    val trainId: String,
    val car: Int? = null,
    val lang: Lang = Lang.EN,
    val method: CheckInMethod = CheckInMethod.LINK,
    val destinationId: String? = null,
    /** Set when following a departure from the platform (see [CheckIn.boardingId]); null = on board. */
    val boardingId: String? = null,
)

/** Response of `POST /api/checkins`. `incident` is set if the train already has a published incident. */
@Serializable
data class CheckInResponse(
    val checkIn: CheckIn,
    val train: Train,
    val incident: IncidentFacts? = null,
)

/** `POST /api/checkins/{token}/push`: a browser PushSubscription as `subscription.toJSON()` returns it. */
@Serializable
data class PushSubscriptionJson(val endpoint: String, val keys: PushKeys)

@Serializable
data class PushKeys(val p256dh: String, val auth: String)

/**
 * `POST /api/admin/incidents/{id}/updates`. Missing languages are filled from the first one given.
 * Facts fields left null keep their previous value. To clear one, send it empty.
 */
@Serializable
data class PublishUpdateRequest(
    val stage: Stage,
    val title: Text,
    val body: Text,
    val nextUpdateInMin: Int? = null,
    val cause: Text? = null,
    val delayMin: DelayRange? = null,
    val alternatives: List<Alternative>? = null,
    val onboard: Text? = null,
    val templateId: String? = null,
    /** Set on AI drafts only: who drafted it and what it looked at. Ignored when publishing. */
    val draftedBy: DraftInfo? = null,
)

/** How an AI draft came about, for the dashboard: `koog` (the T17 agent, with the tools it called in order) or `single-call`. */
@Serializable
data class DraftInfo(val engine: String, val tools: List<String> = emptyList(), val model: String? = null)

/** `POST /api/admin/incidents/{id}/draft`: renders a template into a `PublishUpdateRequest` without publishing it. */
@Serializable
data class DraftRequest(val templateId: String, val cause: Text? = null, val delayMin: DelayRange? = null)

/** `POST /api/ingest/ticker`: like `TickerEvent`, but `id` and `ts` are optional (the server fills them in). */
@Serializable
data class TickerInput(
    val sourceEventId: String,
    val kind: TickerKind,
    val source: TickerSource = TickerSource.MANUAL,
    val ts: String? = null,
    val reasonCode: String? = null,
    val reasonText: Text? = null,
    val line: Section? = null,
    val location: Section? = null,
    val lines: List<String> = emptyList(),
    val text: String = "",
)

/** `POST /api/admin/incidents`: an incident opened by hand from the dashboard. */
@Serializable
data class CreateIncidentRequest(
    /** The affected line; it decides which trains are hit. */
    val line: Section,
    /** Where exactly, if known. */
    val location: Section? = null,
    val reasonCode: String? = null,
    /** Cause in the four languages, if known. Internal until an update publishes it. */
    val cause: Text? = null,
    /** Limit to these line labels, e.g. ["IR90", "IC1"]. Empty = every train on the line. */
    val lines: List<String> = emptyList(),
    val note: String = "",
)

/** One group of similar passenger questions, from the chat service. */
@Serializable
data class QuestionCluster(
    val id: String,
    val label: String,
    val count: Int,
    val sample: String? = null,
    val lastAt: String? = null,
    /** Emergencies; shown on top. */
    val urgent: Boolean = false,
)

@Serializable
data class ApiError(val error: String)

// ---- WebSocket events (server → client) ----------------------------------------------------------
//
// Every message is one JSON object with a `type` field. Endpoints:
//   /ws/passenger?token=...  hello, incident.update
//   /ws/admin                ticker.entry, incident.changed, checkins.changed, question.new
//   /ws/status               incident.update

@Serializable
sealed interface WsEvent {
    val ts: String
}

/** Sent first on `/ws/passenger`. */
@Serializable
@SerialName("hello")
data class Hello(
    override val ts: String,
    val checkIn: CheckIn,
    val train: Train,
    val incident: IncidentFacts? = null,
    /** What this passenger should do now (T3), if their trip crosses the closure. */
    val nextMove: railstatus.nextmove.NextMove? = null,
) : WsEvent

/** A new published update. "Resolved" is just an update with `stage = resolved`. */
@Serializable
@SerialName("incident.update")
data class IncidentUpdated(
    override val ts: String,
    val incident: IncidentFacts,
    val update: IncidentUpdate,
    /** What this passenger should do now (T3), computed per passenger. Absent on `/ws/status`. */
    val nextMove: railstatus.nextmove.NextMove? = null,
) : WsEvent

@Serializable
@SerialName("ticker.entry")
data class TickerEntry(
    override val ts: String,
    val event: TickerEvent,
    val incidentId: String? = null,
) : WsEvent

/** Any change to an incident the admin should redraw: detected, ticker added, published, metrics. */
@Serializable
@SerialName("incident.changed")
data class IncidentChanged(
    override val ts: String,
    val incident: AdminIncident,
) : WsEvent

@Serializable
@SerialName("checkins.changed")
data class CheckinsChanged(
    override val ts: String,
    val trainId: String,
    val count: Int,
) : WsEvent

/** The demo was reset (`POST /api/control/reset`). Sent on every socket; clients drop their state and refetch. */
@Serializable
@SerialName("reset")
data class Reset(override val ts: String) : WsEvent

/** The chat service's current question clusters for one incident (replaces the previous list). */
@Serializable
@SerialName("questions.changed")
data class QuestionsChanged(
    override val ts: String,
    val incidentId: String,
    val clusters: List<QuestionCluster>,
) : WsEvent

/** One passenger question, from the chat service. */
@Serializable
@SerialName("question.new")
data class QuestionNew(
    override val ts: String,
    val incidentId: String,
    val passengerId: String,
    val lang: Lang,
    val text: String,
    val clusterId: String? = null,
) : WsEvent

/** `GET /api/control/demo`: where the live demo is. */
@Serializable
data class DemoStatus(
    /** The beat the next click sends, 1-based; `total + 1` once all are sent. */
    val next: Int,
    val total: Int,
    val sending: Boolean,
    val beats: List<DemoBeatInfo>,
)

@Serializable
data class DemoBeatInfo(val n: Int, val name: String, val messages: Int, val then: String)
