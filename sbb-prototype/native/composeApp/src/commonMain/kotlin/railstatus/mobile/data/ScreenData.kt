@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.data

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Small derivations and labels the web screens compute inline (screens/*.tsx, components/transit.tsx), collected here
// so the Compose screens show exactly the same thing. Icons and layout stay in the UI.

// ---------------------------------------------------------------- products and lines

/** Products drawn as an SBB logotype with the line number in the box (`LOGO` in transit.tsx); others are plain text. */
val Product.hasLogo: Boolean
    get() = this in setOf(Product.IC, Product.IR, Product.RE, Product.S, Product.SN, Product.PE, Product.EC, Product.ICE)

enum class BadgeTone { RED, RE, S, SN }

/** Colour of the line badge: RE, S and SN have their own, every other logotype is red. */
val Product.badgeTone: BadgeTone
    get() = when (this) {
        Product.RE -> BadgeTone.RE
        Product.S -> BadgeTone.S
        Product.SN -> BadgeTone.SN
        else -> BadgeTone.RED
    }

/** "IC 1", "B 3": the text form of a line badge. */
fun lineText(product: Product, line: String): String = "${product.code} $line"

/** The leg's badge as text, or null when the leg has no badge (no product or no line, as `LegBadge`). */
val Leg.lineText: String? get() = if (product != null && !line.isNullOrEmpty()) lineText(product, line) else null

/** "18.00" (`toFixed(2)`); cards show "from CHF 18.00", the CTA "Tickets from CHF 18.00". */
fun formatPrice(chf: Double): String = jsToFixed2(chf)

/** "ca +3'" next to a time. */
fun approxDelayLabel(min: Int): String = "ca +$min'"

// ---------------------------------------------------------------- connection list (Connections.tsx)

/** The search time: the store's `searchAt`, or [now] when that is unset. */
fun searchTime(state: AppState, now: Instant): Instant = state.searchAt ?: now

/**
 * The connection list for the store's from/to (7 entries), searched from the start of [at]'s minute, so the list stays
 * stable while the minute lasts. Empty without both stations.
 */
fun connectionsFor(state: AppState, at: Instant): List<Connection> {
    val from = findStation(state.fromId) ?: return emptyList()
    val to = findStation(state.toId) ?: return emptyList()
    val minute = at.toEpochMilliseconds().floorDiv(60_000L)
    return findConnections(from, to, Instant.fromEpochMilliseconds(minute * 60_000), 7)
}

/** The departure board's station: the From station, else Lausanne. */
fun boardStation(state: AppState): Station = findStation(state.fromId) ?: station("lausanne")

// ---------------------------------------------------------------- connection detail (Connection.tsx)

/** Each disruption kind on the leg once, in order (the status icons on a leg card). */
fun Leg.disruptionKinds(): List<DisruptionKind> = disruptions.map { it.kind }.distinct()

/** A disruption with a body opens the Information sheet; the others are inline notes. */
val Disruption.hasInfo: Boolean get() = !body.isNullOrEmpty()

/** The attributes for the Legend, each once, in leg order. */
fun legendAttrs(legs: List<Leg>): List<Attr> = legs.flatMap { it.attrs }.distinct()

/** The leg a step card belongs to. */
fun cardLeg(card: StepCard, c: Connection): Int = when (card) {
    is StepCard.Departure -> 0
    is StepCard.Arrival -> c.legs.size - 1
    is StepCard.Change -> c.legs.indexOf(card.transfer.toLeg)
}

/** The arrival card's "Travel time": minutes left, or the whole trip once it is 0. */
fun StepCard.Arrival.travelTimeMin(c: Connection): Int = if (remainingMin != 0) remainingMin else c.durationMin

/** The red banner in the companion sheet: the leg's first disruption that has an Information text. */
fun Leg.banner(): Disruption? = disruptions.firstOrNull { it.hasInfo }

enum class LineKind { TRAIN, WALK }

/** One line on the route map (`MapLine` in RouteMap.tsx). */
data class MapLine(val coords: List<LonLat>, val kind: LineKind, val mode: Mode? = null)

/** The connection's legs as map lines, one per leg, through every stop. */
fun Connection.mapLines(): List<MapLine> = legs.map { l ->
    MapLine(
        kind = if (l.mode == Mode.WALK) LineKind.WALK else LineKind.TRAIN,
        mode = l.mode,
        coords = l.stops.map { it.station.lonLat },
    )
}

/** Every station of the connection once, in trip order (the map's stop dots). */
fun Connection.mapStations(): List<Station> = legs.flatMap { l -> l.stops.map { it.station } }.distinctBy { it.id }

/** The train marker: only while the trip is under way. */
fun Connection.trainMarker(now: Instant): LonLat? = if (progress(now) != null) trainPosition(now) else null

/** What the camera frames for a step card: the station for a change, otherwise the card's leg. */
fun Connection.cameraFocus(card: StepCard): List<LonLat> =
    if (card is StepCard.Change) listOf(card.transfer.at.lonLat) else mapLines()[cardLeg(card, this)].coords

/** A coach sector and how many coaches it spans, in order (the formation strip's header). */
data class SectorSpan(val name: String, val span: Int)

fun formationSectors(coaches: List<Coach>): List<SectorSpan> {
    val out = mutableListOf<SectorSpan>()
    for (c in coaches) {
        val last = out.lastOrNull()
        if (last != null && last.name == c.sector) out[out.size - 1] = last.copy(span = last.span + 1)
        else out += SectorSpan(c.sector, 1)
    }
    return out
}

// ---------------------------------------------------------------- journey (Journey.tsx)

/** The time a stop row shows in bold: departure, or arrival at the last stop. */
val Stop.shownTime: Instant get() = (dep ?: arr)!!

/** Greyed out in the stop list once its time has passed. */
fun Stop.isPassed(now: Instant): Boolean = shownTime < now

// ---------------------------------------------------------------- trips (Trips.tsx)

/**
 * The saved trips to list: rebuilt from their ids, dropping any that arrived more than 3 hours before [now] (the app
 * clock, so a pinned demo time counts), sorted by departure.
 */
fun savedTrips(saved: List<String>, now: Instant): List<Connection> =
    saved.mapNotNull { getConnection(it) }
        .filter { it.arr.toEpochMilliseconds() > now.toEpochMilliseconds() - 3 * 3_600_000L }
        .sortedBy { it.dep }

/** "Journey is active": from 15 minutes before departure until arrival. */
fun Connection.isActive(now: Instant): Boolean = now >= addMin(dep, -15) && now <= arr
