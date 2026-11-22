@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.incident

import kotlinx.datetime.TimeZone
import railstatus.model.DelayRange
import railstatus.model.Lang
import railstatus.model.Stage
import railstatus.model.Text
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Port of mobile/src/incident/personal.ts.
// The personal part of an update. Everyone on the train gets the same status-page
// update; this adds what it means for *this* passenger, from plain rules on their
// ticket and destination. No AI, so every line is predictable and checkable.

/** MOCK demo travellers. The real app gets this from the SwissPass login. */
val PERSONAS: Map<PersonaId, Persona> = mapOf(
    PersonaId.SUPERSAVER to Persona(
        PersonaId.SUPERSAVER, "Supersaver", loggedIn = true,
        ticket = Ticket(TicketKind.SUPERSAVER, "Supersaver", from = "Genève", to = "Lausanne"),
    ),
    PersonaId.TICKET to Persona(
        PersonaId.TICKET, "Single ticket", loggedIn = true,
        ticket = Ticket(TicketKind.POINT_TO_POINT, "Single ticket", from = "Genève", to = "Sion"),
    ),
    PersonaId.GA to Persona(PersonaId.GA, "GA", loggedIn = true, ticket = Ticket(TicketKind.GA, "GA travelcard")),
    PersonaId.GUEST to Persona(PersonaId.GUEST, "Guest", loggedIn = false),
)

/** In the order the demo cycles through them. */
val PERSONA_IDS: List<PersonaId> = PERSONAS.keys.toList()

private val NAMES: Map<String, Map<Lang, String>> = mapOf(
    "Genève-Aéroport" to mapOf(Lang.EN to "Geneva Airport", Lang.DE to "Genf Flughafen", Lang.IT to "Ginevra Aeroporto"),
    "Genève" to mapOf(Lang.EN to "Geneva", Lang.DE to "Genf", Lang.IT to "Ginevra"),
    "Lausanne" to mapOf(Lang.IT to "Losanna"),
    "Sion" to mapOf(Lang.DE to "Sitten"),
    "Bern" to mapOf(Lang.FR to "Berne", Lang.IT to "Berna"),
    "Thun" to mapOf(Lang.FR to "Thoune"),
    "Visp" to mapOf(Lang.FR to "Viège"),
    "Brig" to mapOf(Lang.FR to "Brigue", Lang.IT to "Briga"),
)

fun stationName(station: String, lang: Lang): String = NAMES[station]?.get(lang) ?: station

private fun each(f: (Lang) -> String): Text = text(en = f(Lang.EN), de = f(Lang.DE), fr = f(Lang.FR), it = f(Lang.IT))

/** Where the passenger gets off: from their ticket, or what they told us. */
fun destinationOf(p: Persona, asked: String?): String? = p.ticket?.to ?: asked

/** One line of the "For you" box. */
sealed interface ForYou {
    data class Arrival(val text: Text) : ForYou
    data class TicketLine(val text: Text) : ForYou
    /** We need their stop: ask "Where are you getting off?". */
    data object Ask : ForYou
}

fun forYou(u: PublishedUpdate, p: Persona, dest: String?, train: TrainView, zone: TimeZone = SWISS_ZONE): List<ForYou> {
    // Without a published delay there's no arrival time to give and no reason to ask for the stop yet, but
    // the ticket line already holds: it's what tells the passenger they may take the backup route (T18 #4).
    val delay = u.delay ?: return listOfNotNull(ticketLine(p, dest)?.let { ForYou.TicketLine(it) })
    val out = mutableListOf<ForYou>()

    val stop = if (!dest.isNullOrEmpty()) train.stops.firstOrNull { it.station == dest } else null
    if (stop != null) {
        out.add(ForYou.Arrival(arrival(stop.station, stop.planned, delay, zone)))
    } else if (u.stage != Stage.RESOLVED) {
        // Ask only now: the delay is what makes the destination matter.
        out.add(ForYou.Ask)
    }

    ticketLine(p, dest)?.let { out.add(ForYou.TicketLine(it)) }
    return out
}

private fun arrival(station: String, planned: Instant, delay: DelayRange, zone: TimeZone): Text {
    val was = hhmm(planned, zone)
    val a = hhmm(addMin(planned, delay.low), zone)
    val b = hhmm(addMin(planned, delay.high), zone)
    if (a == b) {
        return each { l ->
            val name = stationName(station, l)
            when (l) {
                Lang.EN -> "You arrive in $name at about $a instead of $was."
                Lang.DE -> "Sie kommen um ca. $a statt $was in $name an."
                Lang.FR -> "Vous arrivez à $name vers $a au lieu de $was."
                Lang.IT -> "Arrivate a $name verso le $a invece delle $was."
            }
        }
    }
    return each { l ->
        val name = stationName(station, l)
        when (l) {
            Lang.EN -> "You arrive in $name between about $a and $b instead of $was."
            Lang.DE -> "Sie kommen zwischen ca. $a und $b statt $was in $name an."
            Lang.FR -> "Vous arrivez à $name entre $a et $b environ au lieu de $was."
            Lang.IT -> "Arrivate a $name tra le $a e le $b circa invece delle $was."
        }
    }
}

/** "+30 min" or "+25–35 min". */
fun delayLabel(d: DelayRange): String = if (d.low == d.high) "+${d.high} min" else "+${d.low}–${d.high} min"

private fun ticketLine(p: Persona, dest: String?): Text? {
    val t = p.ticket ?: return null
    if (t.kind == TicketKind.SUPERSAVER) {
        // MOCK rule: SBB lifts the train binding when the train is delayed or cancelled.
        // Confirm the exact tariff wording with SBB before the pitch.
        val to = dest ?: t.to ?: ""
        return each { l ->
            val name = stationName(to, l)
            when (l) {
                Lang.EN -> "Supersaver: you may take another train."
                Lang.DE -> "Sparbillett: Sie dürfen einen anderen Zug nehmen."
                Lang.FR -> "Billet dégriffé : vous pouvez prendre un autre train."
                Lang.IT -> "Biglietto risparmio: potete prendere un altro treno."
            }
        }
    }
    if (t.kind == TicketKind.POINT_TO_POINT && !t.from.isNullOrEmpty() && !t.to.isNullOrEmpty()) {
        val from = t.from
        val to = t.to
        return each { l ->
            val a = stationName(from, l)
            val b = stationName(to, l)
            when (l) {
                Lang.EN -> "Your ticket $a → $b is valid on later trains and replacement buses."
                Lang.DE -> "Ihr Billett $a → $b gilt auch für spätere Züge und Ersatzbusse."
                Lang.FR -> "Votre billet $a → $b est valable dans les trains suivants et les bus de remplacement."
                Lang.IT -> "Il vostro biglietto $a → $b vale anche sui treni successivi e sui bus sostitutivi."
            }
        }
    }
    if (t.kind == TicketKind.GA) {
        return text(
            en = "Your GA travelcard covers replacement buses and any other route.",
            de = "Ihr GA gilt für Ersatzbusse und jede andere Route.",
            fr = "Votre AG est valable dans les bus de remplacement et sur tout autre itinéraire.",
            it = "Il vostro AG vale sui bus sostitutivi e su qualsiasi altro percorso.",
        )
    }
    return null
}

/** personal.ts `FOR_YOU_UI`. */
object ForYouUi {
    val heading = text(en = "For you", de = "Für Sie", fr = "Pour vous", it = "Per voi")
    val ask = text(
        en = "Where are you getting off? We’ll work out your arrival.",
        de = "Wo steigen Sie aus? Wir berechnen Ihre Ankunft.",
        fr = "Où descendez-vous ? Nous calculons votre arrivée.",
        it = "Dove scendete? Calcoliamo il vostro arrivo.",
    )
}

/**
 * ui.tsx `TicketSummary`: "Genève → Lausanne · Supersaver", "GA travelcard", "→ Sion", or null for a guest
 * who hasn't said where they get off. [route] is empty when there's no route to show.
 */
data class TicketSummary(val route: String, val label: String?)

fun ticketSummary(persona: Persona, dest: String?, lang: Lang): TicketSummary? {
    val t = persona.ticket
    if (t == null && dest.isNullOrEmpty()) return null
    val route = when {
        !t?.from.isNullOrEmpty() && !t?.to.isNullOrEmpty() -> "${stationName(t!!.from!!, lang)} → ${stationName(t.to!!, lang)}"
        !dest.isNullOrEmpty() -> "→ ${stationName(dest, lang)}"
        else -> ""
    }
    return TicketSummary(route, t?.label)
}
