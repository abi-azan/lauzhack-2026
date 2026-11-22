package railstatus.server

import railstatus.model.Lang
import railstatus.model.Section
import railstatus.nextmove.Corridor
import railstatus.nextmove.Detour
import railstatus.nextmove.MoveLeg

/**
 * Demo data for "next move" (T3): the Lausanne – Genève line closed between Allaman and Renens VD,
 * as on 17 Sep 2026, and the scheduled public transport around it.
 *
 * Sources: the regular buses 724 (Allaman, gare → Morges, gare, hourly at :16 in the evening) and 701
 * (Morges → Lausanne-Bourdonnette, every ~7 min) + metro m1, from transport.opendata.ch for a weekday
 * evening; 17 Sep's own run was Allaman 19:16 → Lausanne 20:43 (87 min, search.ch, docs/next-move
 * research on the research/next-move branch). SBB's replacement buses are NOT here: they come only
 * from the admin's published alternatives.
 */
object NextMoveCorridor {
    private val allaman = Fixtures.allaman
    private val renens = Fixtures.renens

    private val base = Corridor(
        order = listOf(
            Fixtures.genevaAirport.id, Fixtures.geneva.id, Fixtures.nyon.id,
            allaman.id, Fixtures.morges.id, renens.id, Fixtures.lausanne.id,
        ),
        westEdge = allaman.id,
        eastEdge = renens.id,
        eastbound = listOf(
            Detour(
                id = "bus-724-701",
                title = mapOf(
                    Lang.EN to "Regional buses via Morges", Lang.DE to "Regionalbusse über Morges",
                    Lang.FR to "Bus régionaux via Morges", Lang.IT to "Bus regionali via Morges",
                ),
                leaveAt = allaman,
                rejoinAt = Fixtures.lausanne,
                departures = listOf("18:16", "19:16", "20:16", "21:16", "22:16"),
                rideMin = 87,
                legs = listOf(
                    MoveLeg("walk", null, "Allaman", "Allaman, gare"),
                    MoveLeg("bus", "724", "Allaman, gare", "Morges, gare"),
                    MoveLeg("bus", "701", "Morges, gare", "Lausanne, Bourdonnette"),
                    MoveLeg("metro", "m1", "Lausanne, Bourdonnette", "Lausanne"),
                ),
                note = mapOf(
                    Lang.EN to "Regular buses with few seats. Your ticket may not cover them.",
                    Lang.DE to "Reguläre Busse mit wenig Plätzen. Ihr Billett gilt dort eventuell nicht.",
                    Lang.FR to "Bus réguliers avec peu de places. Votre billet n'est peut-être pas valable.",
                    Lang.IT to "Bus regolari con pochi posti. Il vostro biglietto potrebbe non essere valido.",
                ),
            ),
        ),
    )

    /** The corridor with its departures moved by the same offset as the trains (see [TrainClock]). */
    fun forDemo(trainsAnchor: String?): Corridor {
        val d = TrainClock.delta(trainsAnchor) ?: return base
        return base.copy(eastbound = base.eastbound.map { it.copy(departures = it.departures.map { t -> shift(t, d) }) })
    }

    /** Does an incident at [location] close this corridor? */
    fun covers(location: Section?): Boolean =
        location != null && setOf(location.from.id, location.to.id) == setOf(allaman.id, renens.id)

    private fun shift(t: String, d: Int): String {
        val (h, m) = t.split(":").map(String::toInt)
        val x = Math.floorMod(h * 60 + m + d, 24 * 60)
        return "%02d:%02d".format(x / 60, x % 60)
    }
}
