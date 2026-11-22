@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.data

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// UI-level shapes for the shell, ported from `mobile/src/data/types.ts`. They are mock shapes: the backend's
// `railstatus.model` types (Station with a DIDOK id, "HH:MM" stop times) don't line up with them, so the two are
// kept separate. Note the name clashes: `Station` and `Stop` exist in both packages.

/** `code` is the string the web app uses. */
enum class Mode(val code: String) {
    TRAIN("train"), BUS("bus"), TRAM("tram"), METRO("metro"), BOAT("boat"), WALK("walk");
}

enum class Product(val code: String) {
    IC("IC"), IR("IR"), RE("RE"), S("S"), SN("SN"), PE("PE"), EC("EC"), ICE("ICE"), B("B"), T("T"), M("M");

    companion object {
        /** "IC" → [IC]; anything else (the backend sends free strings) → null. */
        fun fromCode(code: String): Product? = entries.firstOrNull { it.code == code }
    }
}

enum class Occupancy(val code: String) {
    NONE("none"), LOW("low"), MEDIUM("medium"), HIGH("high");
}

/** Service attributes, named after the SBB `sa-*` icons. [label] is the legend text. */
enum class Attr(val label: String) {
    NF("Low-floor access"),
    BZ("Business zone in 1st class"),
    FZ("Family Coach with play area"),
    WR("Restaurant"),
    R("Seat reservation possible"),
    VO("Free Internet with the SBB FreeSurf app"),
    BI("Bicycle loading"),
    RS("Wheelchair space"),
    KW("Catering zone / Vending machine"),
    FA("Family zone"),
}

/** Small stops are shown with the metro/bus icon in search results. */
enum class StationKind(val code: String) {
    STATION("station"), METRO("metro"), BUS("bus");
}

data class Station(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val kind: StationKind = StationKind.STATION,
)

data class OccupancyPair(val first: Occupancy, val second: Occupancy)

data class Stop(
    val station: Station,
    val arr: Instant? = null,
    val dep: Instant? = null,
    /** Expected delay in minutes ("ca +3'"). */
    val delay: Int? = null,
    val platform: String? = null,
    val platformChanged: Boolean = false,
    val occupancy: OccupancyPair? = null,
)

enum class DisruptionKind(val code: String) {
    CONSTRUCTION("construction"),
    DELAY("delay"),
    CANCELLATION("cancellation"),
    PLATFORM_CHANGE("platform-change"),
    REPLACEMENT_BUS("replacementbus"),
}

data class Disruption(
    val id: String,
    val kind: DisruptionKind,
    /** Bold one-liner in cards, e.g. "Limited service between Renens VD and Allaman (train)". */
    val summary: String,
    /** Second bold line, e.g. "Due to construction work". */
    val cause: String? = null,
    /** Title and text of the Information sheet. Null for inline notes like "Platform change". */
    val title: String? = null,
    val body: String? = null,
)

enum class CoachFeature(val code: String) {
    NF("NF"), BIKE("bike"), STROLLER("stroller"), WHEELCHAIR("wheelchair"),
    RESTAURANT("restaurant"), FAMILY("family"), BUSINESS("business");
}

data class Coach(
    val number: Int,
    /** 1 or 2. */
    val cls: Int,
    val sector: String,
    val occupancy: Occupancy,
    val features: List<CoachFeature>,
)

data class Leg(
    val id: String,
    val mode: Mode,
    val product: Product? = null,
    /** Line number shown in the badge ("15" for IR 15). */
    val line: String? = null,
    /** Train number, e.g. "2537". */
    val number: String? = null,
    val direction: String? = null,
    /** First and last entries are where the traveller boards and alights. */
    val stops: List<Stop>,
    val attrs: List<Attr>,
    val occupancy: OccupancyPair,
    val disruptions: List<Disruption>,
    val formation: List<Coach>? = null,
    /** For walking legs between platforms. */
    val walkMinutes: Int? = null,
)

data class Connection(
    val id: String,
    val from: Station,
    val to: Station,
    val dep: Instant,
    val arr: Instant,
    val legs: List<Leg>,
    val priceFrom: Double? = null,
)

data class DepartureRow(
    val id: String,
    val mode: Mode,
    val product: Product,
    val line: String,
    val time: Instant,
    val delay: Int? = null,
    val direction: String,
    val platform: String,
)
