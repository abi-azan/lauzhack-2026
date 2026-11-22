package railstatus.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Every timestamp in this model is an ISO-8601 string with an offset, e.g. "2026-09-01T04:06:07+02:00".
// Plain strings keep the JSON identical for the Kotlin apps and the TypeScript apps.

@Serializable
enum class Lang {
    @SerialName("de") DE,
    @SerialName("fr") FR,
    @SerialName("it") IT,
    @SerialName("en") EN,
}

/** One text in the four languages, serialized as `{"de": "...", "fr": "...", "it": "...", "en": "..."}`. */
typealias Text = Map<Lang, String>

/**
 * Status-page stages, in order. `detected` is internal: the incident service opened it from the ticker,
 * but nothing has been published yet, so passengers and the public status page never see it.
 */
@Serializable
enum class Stage {
    @SerialName("detected") DETECTED,
    @SerialName("investigating") INVESTIGATING,
    @SerialName("identified") IDENTIFIED,
    @SerialName("monitoring") MONITORING,
    @SerialName("resolved") RESOLVED,
}

/** A station. `id` is the DIDOK number as a string, e.g. "8507000" for Bern. */
@Serializable
data class Station(val id: String, val name: String)

/** Two stations bounding a stretch of line, e.g. Bern – Thun. */
@Serializable
data class Section(val from: Station, val to: Station)

@Serializable
data class Stop(
    val station: Station,
    /** "HH:MM", local time. Null at the first stop. */
    val arr: String? = null,
    /** "HH:MM", local time. Null at the last stop. */
    val dep: String? = null,
    val platform: String? = null,
    /** Actual times ("HH:MM") when known, e.g. from the open-data actuals archive in a replay. */
    val arrActual: String? = null,
    val depActual: String? = null,
    val cancelled: Boolean = false,
)

@Serializable
data class Train(
    /** "IC8-1234". The beacon name and the QR check-in link carry this id. */
    val id: String,
    /** "IC", "IR", "RE", "S", ... */
    val product: String,
    /** "IC 8" */
    val line: String,
    /** "1234" */
    val number: String,
    /** Final destination name, e.g. "Brig". */
    val direction: String,
    /** Only the stops where it halts, in order. */
    val stops: List<Stop>,
    val cars: Int = 8,
    val cancelled: Boolean = false,
)

@Serializable
enum class TickerKind {
    @SerialName("created") CREATED,
    @SerialName("updated") UPDATED,
    @SerialName("ended") ENDED,
}

@Serializable
enum class TickerSource {
    /** The upstream event system (an event created, updated or ended). */
    @SerialName("via") VIA,
    /** The control centre's internal ticker: free-text messages and Infoboard modules. */
    @SerialName("kult") KULT,
    /** Typed into the demo control console. */
    @SerialName("manual") MANUAL,
}

/** One entry on the control centre's internal ticker. It reaches the backend through the `ticker.events` stream. */
@Serializable
data class TickerEvent(
    val id: String,
    /** The upstream event id (e.g. the VIA id "2195005174318"). Every entry about one disruption shares it. */
    val sourceEventId: String,
    val kind: TickerKind,
    val source: TickerSource,
    val ts: String,
    /** e.g. "EG_047" */
    val reasonCode: String? = null,
    val reasonText: Text? = null,
    /** The line that is affected, e.g. Bern – Thun. Used to find the trains. */
    val line: Section? = null,
    /** Where it happened exactly, e.g. Ostermundigen – Gümligen. */
    val location: Section? = null,
    /** Affected line labels as the source gives them, e.g. ["IC6", "IC8", "S1"]. Empty means all. */
    val lines: List<String> = emptyList(),
    /** Internal free text. Never shown to passengers as-is. */
    val text: String = "",
)

@Serializable
enum class CheckInMethod {
    @SerialName("ble") BLE,
    @SerialName("qr") QR,
    @SerialName("link") LINK,
}

@Serializable
data class CheckIn(
    val passengerId: String,
    /** Secret for the passenger's WebSocket and push registration. */
    val token: String,
    val trainId: String,
    val car: Int? = null,
    val lang: Lang = Lang.EN,
    val method: CheckInMethod = CheckInMethod.LINK,
    /** Optional destination station id. The chat needs it to answer connection questions. */
    val destinationId: String? = null,
    val at: String,
    val hasPush: Boolean = false,
    /**
     * Station id where the passenger will board: they follow this departure from the platform and aren't
     * on the train yet. Null means on board (the beacon/QR check-in).
     */
    val boardingId: String? = null,
)

@Serializable
data class DelayRange(val low: Int, val high: Int)

@Serializable
data class Alternative(val id: String, val text: Text, val via: String? = null)

@Serializable
enum class Author {
    @SerialName("admin") ADMIN,
    /** Published automatically by the 60-second safety net. */
    @SerialName("auto") AUTO,
}

/** One published status-page update. */
@Serializable
data class IncidentUpdate(
    val id: String,
    val incidentId: String,
    /** 1, 2, 3, ... per incident. Equals `IncidentFacts.version` right after this update. */
    val seq: Int,
    val stage: Stage,
    val title: Text,
    val body: Text,
    val publishedAt: String,
    val nextUpdateBy: String? = null,
    val author: Author = Author.ADMIN,
    val templateId: String? = null,
)

/**
 * What passengers, the public status page and the chat see. It's rebuilt on every published update and
 * holds only published information: nothing from the internal ticker leaks in here.
 * A null field means "not known yet".
 */
@Serializable
data class IncidentFacts(
    val id: String,
    /** +1 per published update. The chat uses it as a cache key. */
    val version: Int,
    val stage: Stage,
    val trainIds: List<String>,
    /** The affected line, e.g. Bern – Thun. */
    val section: Section? = null,
    /** Where the line is closed, e.g. Renens VD – Allaman: set with the first published update, null once resolved. */
    val location: Section? = null,
    val cause: Text? = null,
    val delayMin: DelayRange? = null,
    val alternatives: List<Alternative> = emptyList(),
    /** On-board info (water, heating, toilets), if the admin said anything. */
    val onboard: Text? = null,
    val nextUpdateBy: String? = null,
    /** Time of the first published update. */
    val openedAt: String? = null,
    val resolvedAt: String? = null,
    /** Oldest first. */
    val updates: List<IncidentUpdate> = emptyList(),
)

/** The admin's view: the published facts plus everything internal. */
@Serializable
data class AdminIncident(
    val facts: IncidentFacts,
    val sourceEventId: String,
    val reasonCode: String? = null,
    val location: Section? = null,
    val detectedAt: String,
    /** When the upstream source reported the event ended. The admin still has to publish "resolved". */
    val sourceEndedAt: String? = null,
    /** Internal ticker entries for this incident, oldest first. */
    val ticker: List<TickerEvent> = emptyList(),
    val passengersByTrain: Map<String, Int> = emptyMap(),
    val metrics: IncidentMetrics = IncidentMetrics(),
    /** When the safety net will publish on its own, while the incident is unpublished; null otherwise. */
    val autoAckAt: String? = null,
)

@Serializable
data class IncidentMetrics(
    /** When the first update reached a passenger's WebSocket or push. */
    val firstNotifiedAt: String? = null,
    /** Seconds from the upstream event's own timestamp to the first passenger notification. */
    val secondsToFirstNotification: Double? = null,
    /** Deliveries for the latest update: WebSocket + push. */
    val lastDeliveries: Int = 0,
)

/** A ready-made update text with placeholders: {section}, {location}, {cause}, {next}, {delayLow}, {delayHigh}. */
@Serializable
data class Template(
    val id: String,
    val stage: Stage,
    /** A short label for the admin button, in English. */
    val label: String,
    val title: Text,
    val body: Text,
    val nextUpdateInMin: Int? = null,
)
