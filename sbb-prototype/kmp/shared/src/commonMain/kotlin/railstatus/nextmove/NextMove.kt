package railstatus.nextmove

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import railstatus.model.*

// "Next move": what a passenger on an affected train should do now.
//
// No forecasting. Every number comes from one of three places, and each option says which:
//   - SBB:       what the admin published (the delay range, the alternatives),
//   - timetable: scheduled public transport around the closed section,
//   - stay:      the train's own timetable plus SBB's published delay.
// The decision is plain arithmetic on those numbers. Times are "HH:MM" on the trains' (demo) clock.

@Serializable
enum class MoveDecision {
    /** Staying on the train gets you there as early as anything else we know of. */
    @SerialName("stay") STAY,
    /** Change to another route: it's certainly earlier, or SBB recommends it. */
    @SerialName("go") GO,
    /** SBB hasn't said how long yet. Stay for now; a backup route and a deadline are shown. */
    @SerialName("wait_for_info") WAIT_FOR_INFO,
}

@Serializable
enum class MoveSource {
    /** Published by the admin: confirmed by SBB. */
    @SerialName("sbb") SBB,
    /** Scheduled public transport from the timetable. */
    @SerialName("timetable") TIMETABLE,
    /** This train, with SBB's published delay. */
    @SerialName("stay") STAY,
}

@Serializable
data class MoveLeg(
    /** "train", "bus", "metro", "walk" */
    val mode: String,
    val line: String? = null,
    val from: String,
    val to: String,
    val dep: String? = null,
    val arr: String? = null,
)

@Serializable
data class MoveOption(
    val id: String,
    val source: MoveSource,
    val title: Text,
    val legs: List<MoveLeg> = emptyList(),
    /** When you leave, "HH:MM", if this option has a departure. */
    val departAt: String? = null,
    /** Arrival at your destination as a range; both equal when exact, both null when not known. */
    val arriveEarliest: String? = null,
    val arriveLatest: String? = null,
    val note: Text? = null,
)

@Serializable
data class NextMove(
    val decision: MoveDecision,
    val headline: Text,
    val why: Text,
    val destination: Station,
    /** True when we don't know where the passenger gets off and assumed the first stop past the closure. */
    val destinationAssumed: Boolean = false,
    /** The recommended option first. "Stay" is always in the list. */
    val options: List<MoveOption>,
    /** "HH:MM": the last moment to leave the train for the backup or recommended route. */
    val decideBy: String? = null,
    /** From the incident: when SBB promised the next update (ISO). */
    val nextUpdateBy: String? = null,
    /** `IncidentFacts.version` this was computed from. */
    val basedOnVersion: Int,
)

/** A closed section and the scheduled ways around it. Demo data lives on the server. */
@Serializable
data class Corridor(
    /** Station ids along the line, west → east, including the closed interior. */
    val order: List<String>,
    /** Last station before the closure on the west side that trains can still reach, e.g. Allaman. */
    val westEdge: String,
    /** First station after the closure on the east side, e.g. Renens VD. */
    val eastEdge: String,
    /** Scheduled routes around the closure for passengers travelling west → east. */
    val eastbound: List<Detour> = emptyList(),
)

@Serializable
data class Detour(
    val id: String,
    val title: Text,
    /** Where you leave the train, e.g. Allaman. */
    val leaveAt: Station,
    /** Where the route rejoins the railway, e.g. Lausanne. */
    val rejoinAt: Station,
    /** Departures from [leaveAt], "HH:MM" on the trains' clock. */
    val departures: List<String>,
    /** From departure at [leaveAt] to arrival at [rejoinAt], in minutes. */
    val rideMin: Int,
    /** The route; the first leg gets the departure, the last the arrival. */
    val legs: List<MoveLeg>,
    val note: Text? = null,
)

/** Minutes to get off the train and walk to the bus stop. */
const val TRANSFER_MIN = 5

/** Wait for an onward train from where the detour rejoins the railway: up to one half-hourly train. */
const val ONWARD_WAIT_MAX_MIN = 30

/**
 * The next move for one passenger, or null when there's nothing to advise: no corridor data, or the
 * passenger gets off before the closed section.
 *
 * @param now "HH:MM" on the trains' clock (the wall clock when the trains are anchored to it).
 * @param routes Real journeys around the closure from the journey planner (OJP), already filtered for
 *   this train and destination: each `departAt` is when it leaves the train's last stop before the
 *   closure, walking included. Null means "no planner answer": the corridor's scheduled detours are used.
 */
fun nextMove(
    facts: IncidentFacts, train: Train, checkIn: CheckIn, now: String, corridor: Corridor?,
    routes: List<MoveOption>? = null,
): NextMove? {
    corridor ?: return null
    val nowMin = minutes(now) ?: return null
    val order = corridor.order
    val westEdgeIdx = order.indexOf(corridor.westEdge)
    val eastEdgeIdx = order.indexOf(corridor.eastEdge)
    if (westEdgeIdx < 0 || eastEdgeIdx < 0) return null

    // Direction along the corridor. Only west → east has detour data; the other way gets stay + SBB's alternatives.
    val inOrder = train.stops.map { order.indexOf(it.station.id) }
    val known = inOrder.filter { it >= 0 }
    if (known.size < 2) return null
    val eastbound = known.first() < known.last()

    // For each stop: is it past the closure (inside it or beyond)? Stops off the corridor count as past
    // once an earlier stop was past (e.g. Montreux after Lausanne).
    val past = BooleanArray(train.stops.size)
    for (i in train.stops.indices) {
        val idx = inOrder[i]
        past[i] = if (idx >= 0) (if (eastbound) idx > westEdgeIdx else idx < eastEdgeIdx) else (i > 0 && past[i - 1])
    }
    // The first stop where rail service resumes on the far side: the far edge or beyond.
    fun resumes(i: Int) = past[i] && inOrder[i].let { idx -> idx < 0 || (if (eastbound) idx >= eastEdgeIdx else idx <= westEdgeIdx) }
    val firstPastIdx = past.indexOfFirst { it }
    if (firstPastIdx < 0) return null

    // On the platform, following this departure (not on board yet): only stops from boarding onwards count.
    // -1 = on board, which leaves everything below as it was.
    val boardIdx = checkIn.boardingId?.let { id -> train.stops.indexOfFirst { it.station.id == id } } ?: -1
    val waiting = boardIdx >= 0
    /** On the platform at or past the closure: their train still has to come through it. */
    val waitingPast = waiting && past[boardIdx]

    // Where the passenger gets off. Unknown or not on this train → the first stop where rail resumes.
    val declared = checkIn.destinationId?.let { id -> train.stops.indexOfFirst { it.station.id == id } }?.takeIf { it > boardIdx }
    if (declared != null && !past[declared]) return null // gets off before the closure: unaffected
    val destIdx = declared
        ?: train.stops.indices.firstOrNull { it > boardIdx && resumes(it) }
        ?: train.stops.indices.firstOrNull { it > boardIdx && past[it] }
        ?: return null
    val destStop = train.stops[destIdx]
    val dest = destStop.station
    val assumed = declared == null

    // ---- the options -----------------------------------------------------------------------------
    val delay = facts.delayMin
    val scheduledArr = (destStop.arr ?: destStop.dep)?.let(::minutes)
    val stay = MoveOption(
        id = "stay", source = MoveSource.STAY,
        title = if (waiting) tx("Take this train", "Diesen Zug nehmen", "Prendre ce train", "Prendere questo treno")
        else tx("Stay on this train", "Im Zug bleiben", "Rester dans ce train", "Restare su questo treno"),
        arriveEarliest = if (delay != null && scheduledArr != null) hhmm(scheduledArr + delay.low) else null,
        arriveLatest = if (delay != null && scheduledArr != null) hhmm(scheduledArr + delay.high) else null,
    )

    val confirmed = facts.alternatives.map { alt ->
        MoveOption(id = "sbb-${alt.id}", source = MoveSource.SBB, title = fillLanguages(alt.text))
    }

    val detours = when {
        facts.stage == Stage.RESOLVED -> emptyList()
        // The planner's journeys already include the walk from the platform: catchable while not yet gone.
        routes != null -> routes.filter { o -> o.departAt?.let(::minutes)?.let { rel(it, nowMin) >= 0 } == true && o.arriveLatest != null }
        eastbound -> corridor.eastbound.mapNotNull { d -> priceDetour(d, train, destIdx, nowMin, boardIdx) }
        else -> emptyList()
    }
    // Time to get off and reach the first departure: in the planner's journeys already; assumed for the corridor's.
    val leaveMin = if (routes != null) 0 else TRANSFER_MIN
    fun deadline(o: MoveOption) = o.departAt?.let(::minutes)?.let { hhmm(it - leaveMin) }
    val best = detours.minByOrNull { rel(minutes(it.arriveLatest!!)!!, nowMin) }

    // ---- the decision ------------------------------------------------------------------------------
    val destName = dest.name
    val nextBy = facts.nextUpdateBy?.let(::isoToHhmm)
    val stayKnown = stay.arriveEarliest != null
    fun range(o: MoveOption) = if (o.arriveEarliest == o.arriveLatest) o.arriveEarliest!! else "${o.arriveEarliest}–${o.arriveLatest}"
    fun delayTxt() = "+${delay!!.low}–${delay.high} min".let { if (delay.low == delay.high) "+${delay.low} min" else it }

    val decision: MoveDecision
    val headline: Text
    val why: Text
    val options: List<MoveOption>
    val decideBy: String?

    when {
        facts.stage == Stage.RESOLVED -> {
            decision = MoveDecision.STAY
            headline = tx("The line is open again", "Die Strecke ist wieder offen", "La ligne est rouverte", "La linea è di nuovo aperta")
            why = if (waiting) tx("Take your train to $destName as planned.", "Nehmen Sie Ihren Zug nach $destName wie geplant.", "Prenez votre train pour $destName comme prévu.", "Prendete il vostro treno per $destName come previsto.")
            else tx("Stay on your train to $destName.", "Bleiben Sie im Zug bis $destName.", "Restez dans votre train jusqu'à $destName.", "Restate sul treno fino a $destName.")
            // The last published delay is stale once the line is open: don't turn it into an arrival time.
            options = listOf(stay.copy(arriveEarliest = null, arriveLatest = null))
            decideBy = null
        }
        // SBB's alternatives are for getting around the closure. Someone waiting on a platform past it isn't going
        // around it: they wait for their train (T18 #2).
        confirmed.isNotEmpty() && !waitingPast -> {
            decision = MoveDecision.GO
            headline = tx("Follow SBB's advice", "Folgen Sie der Empfehlung der SBB", "Suivez la recommandation des CFF", "Seguite il consiglio delle FFS")
            // SBB's text is already on the page (Information) and in the option rows: don't repeat it here (T18 #3).
            val alsoEn = best?.let { " Also possible, from the timetable: ${what(it)} at ${it.departAt} from ${it.legs.first().from}, in $destName by ${it.arriveLatest}." }.orEmpty()
            val alsoDe = best?.let { " Laut Fahrplan auch möglich: ${what(it)} um ${it.departAt} ab ${it.legs.first().from}, bis ${it.arriveLatest} in $destName." }.orEmpty()
            val alsoFr = best?.let { " Également possible selon l'horaire : ${what(it)} à ${it.departAt} depuis ${it.legs.first().from}, à $destName avant ${it.arriveLatest}." }.orEmpty()
            val alsoIt = best?.let { " Possibile anche secondo l'orario: ${what(it)} alle ${it.departAt} da ${it.legs.first().from}, a $destName entro le ${it.arriveLatest}." }.orEmpty()
            why = tx(
                "SBB has published other ways to travel (see Information).$alsoEn",
                "Die SBB hat andere Reisemöglichkeiten veröffentlicht (siehe Information).$alsoDe",
                "Les CFF ont publié d'autres possibilités de voyage (voir Information).$alsoFr",
                "Le FFS hanno pubblicato altre possibilità di viaggio (vedi Informazione).$alsoIt",
            )
            options = confirmed + listOfNotNull(best) + stay
            // Our route's deadline doesn't belong under SBB's advice; its row shows when it leaves.
            decideBy = null
        }
        stayKnown && best != null && rel(minutes(best.arriveLatest!!)!!, nowMin) < rel(minutes(stay.arriveEarliest!!)!!, nowMin) -> {
            decision = MoveDecision.GO
            val leg = best.legs.firstOrNull { it.mode != "walk" }
            val from = best.legs.first().from
            val what = leg?.let { label(it) }.orEmpty()
            headline = if (waiting && train.stops[boardIdx].station.name == from)
                tx("Take the $what from $from instead", "Nehmen Sie stattdessen $what ab $from", "Prenez plutôt le $what depuis $from", "Prendete invece il $what da $from")
            else tx("Change at $from", "In $from umsteigen", "Changez à $from", "Cambiate a $from")
            why = if (waiting) tx(
                "SBB expects ${delayTxt()}: your train gets you to $destName at ${range(stay)}. The $what at ${best.departAt} gets you there by ${best.arriveLatest}.",
                "Die SBB erwartet ${delayTxt()}: mit Ihrem Zug sind Sie um ${range(stay)} in $destName. Mit $what um ${best.departAt} sind Sie bis ${best.arriveLatest} dort.",
                "Les CFF prévoient ${delayTxt()} : votre train vous amène à $destName à ${range(stay)}. Le $what de ${best.departAt} vous y amène avant ${best.arriveLatest}.",
                "Le FFS prevedono ${delayTxt()}: con il vostro treno arrivate a $destName alle ${range(stay)}. Con il $what delle ${best.departAt} ci arrivate entro le ${best.arriveLatest}.",
            ) else tx(
                "SBB expects ${delayTxt()}: staying gets you to $destName at ${range(stay)}. The $what at ${best.departAt} gets you there by ${best.arriveLatest}.",
                "Die SBB erwartet ${delayTxt()}: im Zug sind Sie um ${range(stay)} in $destName. Mit $what um ${best.departAt} sind Sie bis ${best.arriveLatest} dort.",
                "Les CFF prévoient ${delayTxt()} : en restant, vous arrivez à $destName à ${range(stay)}. Le $what de ${best.departAt} vous y amène avant ${best.arriveLatest}.",
                "Le FFS prevedono ${delayTxt()}: restando arrivate a $destName alle ${range(stay)}. Con il $what delle ${best.departAt} ci arrivate entro le ${best.arriveLatest}.",
            )
            options = listOf(best) + stay
            decideBy = deadline(best)
        }
        stayKnown -> {
            decision = MoveDecision.STAY
            headline = if (waiting) tx("Take your train as planned", "Nehmen Sie Ihren Zug wie geplant", "Prenez votre train comme prévu", "Prendete il vostro treno come previsto")
            else tx("Stay on your train", "Bleiben Sie im Zug", "Restez dans le train", "Restate sul treno")
            val alt = best?.let { " The other route would arrive ${range(it)}." }.orEmpty()
            val altDe = best?.let { " Auf der anderen Route wären Sie um ${range(it)} dort." }.orEmpty()
            val altFr = best?.let { " Par l'autre itinéraire, vous arriveriez à ${range(it)}." }.orEmpty()
            val altIt = best?.let { " Con l'altro percorso arrivereste alle ${range(it)}." }.orEmpty()
            why = tx(
                "SBB expects ${delayTxt()}: you reach $destName at ${range(stay)}.$alt",
                "Die SBB erwartet ${delayTxt()}: Sie sind um ${range(stay)} in $destName.$altDe",
                "Les CFF prévoient ${delayTxt()} : vous arrivez à $destName à ${range(stay)}.$altFr",
                "Le FFS prevedono ${delayTxt()}: arrivate a $destName alle ${range(stay)}.$altIt",
            )
            options = listOf(stay) + listOfNotNull(best)
            decideBy = best?.let(::deadline)
        }
        else -> {
            decision = MoveDecision.WAIT_FOR_INFO
            headline = if (waiting) tx("Wait at the station for now", "Warten Sie vorerst am Bahnhof", "Attendez en gare pour l'instant", "Per ora aspettate in stazione")
            else tx("Stay on board for now", "Bleiben Sie vorerst im Zug", "Restez à bord pour l'instant", "Per ora restate a bordo")
            // The next-update time is on the official update and the page, once (T18 F5): not repeated here.
            val next = if (waitingPast && confirmed.isNotEmpty()) " SBB's other ways to travel are for the closed section, which your train comes through." else ""
            val nextDe = if (waitingPast && confirmed.isNotEmpty()) " Die Reisemöglichkeiten der SBB gelten für den gesperrten Abschnitt, durch den Ihr Zug kommt." else ""
            val nextFr = if (waitingPast && confirmed.isNotEmpty()) " Les autres possibilités des CFF concernent le tronçon fermé que votre train traverse." else ""
            val nextIt = if (waitingPast && confirmed.isNotEmpty()) " Le altre possibilità delle FFS riguardano la tratta chiusa che il vostro treno attraversa." else ""
            val bak = best?.let { " If you'd rather not wait: ${it.legs.firstOrNull { l -> l.mode != "walk" }?.let(::label) ?: "a connection"} at ${it.departAt} from ${it.legs.first().from}, in $destName by ${it.arriveLatest}." }.orEmpty()
            val bakDe = best?.let { " Wenn Sie nicht warten möchten: ${it.legs.firstOrNull { l -> l.mode != "walk" }?.let(::label) ?: "Verbindung"} um ${it.departAt} ab ${it.legs.first().from}, bis ${it.arriveLatest} in $destName." }.orEmpty()
            val bakFr = best?.let { " Si vous ne voulez pas attendre : ${it.legs.firstOrNull { l -> l.mode != "walk" }?.let(::label) ?: "correspondance"} à ${it.departAt} depuis ${it.legs.first().from}, à $destName avant ${it.arriveLatest}." }.orEmpty()
            val bakIt = best?.let { " Se non volete aspettare: ${it.legs.firstOrNull { l -> l.mode != "walk" }?.let(::label) ?: "coincidenza"} alle ${it.departAt} da ${it.legs.first().from}, a $destName entro le ${it.arriveLatest}." }.orEmpty()
            why = tx(
                "SBB doesn't know yet how long this will last.$next$bak",
                "Die SBB weiss noch nicht, wie lange es dauert.$nextDe$bakDe",
                "Les CFF ne savent pas encore combien de temps cela durera.$nextFr$bakFr",
                "Le FFS non sanno ancora quanto durerà.$nextIt$bakIt",
            )
            options = listOf(stay) + listOfNotNull(best)
            decideBy = best?.let(::deadline)
        }
    }

    return NextMove(
        decision = decision, headline = headline, why = why,
        destination = dest, destinationAssumed = assumed,
        options = options, decideBy = decideBy, nextUpdateBy = facts.nextUpdateBy,
        basedOnVersion = facts.version,
    )
}

/** Price one detour: the next departure we can still make, and the arrival at the destination. */
private fun priceDetour(d: Detour, train: Train, destIdx: Int, nowMin: Int, boardIdx: Int = -1): MoveOption? {
    // Only from a station this train actually stops at, before the passenger's destination. Never tell
    // someone to change at a station their train runs through (e.g. the IR 90 doesn't stop at Allaman).
    // Someone still on the platform can only start from where they board or a later stop.
    val leaveIdx = train.stops.indexOfFirst { it.station.id == d.leaveAt.id }
    if (leaveIdx < 0 || leaveIdx >= destIdx || leaveIdx < boardIdx) return null
    val dep = d.departures.mapNotNull(::minutes)
        .map { rel(it, nowMin) }
        .filter { it >= TRANSFER_MIN }
        .minOrNull() ?: return null
    val depAbs = nowMin + dep
    val atRejoin = depAbs + d.rideMin

    // Onward from where the detour rejoins the railway, using this train's own running time.
    val rejoinIdx = train.stops.indexOfFirst { it.station.id == d.rejoinAt.id }
    if (rejoinIdx < 0 || rejoinIdx > destIdx) return null
    val (early, late) = if (rejoinIdx == destIdx) atRejoin to atRejoin else {
        val from = (train.stops[rejoinIdx].dep ?: train.stops[rejoinIdx].arr)?.let(::minutes) ?: return null
        val to = (train.stops[destIdx].arr ?: train.stops[destIdx].dep)?.let(::minutes) ?: return null
        val ride = mod1440(to - from)
        (atRejoin + ride) to (atRejoin + ride + ONWARD_WAIT_MAX_MIN)
    }
    val legs = d.legs.mapIndexed { i, l ->
        l.copy(
            dep = if (i == d.legs.indexOfFirst { it.mode != "walk" }) hhmm(depAbs) else l.dep,
            arr = if (i == d.legs.lastIndex) hhmm(atRejoin) else l.arr,
        )
    }
    return MoveOption(
        id = d.id, source = MoveSource.TIMETABLE, title = fillLanguages(d.title), legs = legs,
        departAt = hhmm(depAbs), arriveEarliest = hhmm(early), arriveLatest = hhmm(late), note = d.note?.let(::fillLanguages),
    )
}

// ---- small helpers --------------------------------------------------------------------------------

/** The first leg that isn't a walk, named: "train RE33". */
private fun what(o: MoveOption): String = o.legs.firstOrNull { it.mode != "walk" }?.let(::label) ?: "a connection"

/** "bus 724", "train R3", "boat": how a leg is named in the advice. */
private fun label(l: MoveLeg): String = listOfNotNull(l.mode, l.line).joinToString(" ")

private fun tx(en: String, de: String, fr: String, it: String): Text = mapOf(Lang.EN to en, Lang.DE to de, Lang.FR to fr, Lang.IT to it)

internal fun minutes(s: String): Int? {
    val m = Regex("""^(\d{1,2}):(\d{2})$""").find(s.trim()) ?: return null
    return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
}

internal fun hhmm(min: Int): String {
    val m = mod1440(min)
    return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
}

/** [t] relative to [now] in minutes, in −12 h … +12 h, so times just past midnight count as later. */
internal fun rel(t: Int, now: Int): Int = mod1440(t - now + 720) - 720

/** "2026-11-22T01:13:00+01:00" → "01:13" (the server writes local times). */
private fun isoToHhmm(iso: String): String? = iso.substringAfter('T', "").take(5).takeIf { minutes(it) != null }

private fun mod1440(x: Int): Int = ((x % 1440) + 1440) % 1440
