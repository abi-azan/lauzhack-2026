@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.data

import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** One time zone's expected values, computed by the web app's TypeScript. Null sections are Zurich-only. */
internal interface GoldenSet {
    val zone: String
    val pairHashes: String?
    val pairTimes: String?
    val cases: String?
    val boardHashes: String?
    val boards: String?
    val boardTimes: String?
    val clock: String?
    val ids: String?
    val derive: String?
    val search: String?
    val distances: String?
    val prices: String?
}

/**
 * Pins the Kotlin data layer to the web app's: the same inputs must give the same connections (ids, times, platforms,
 * train numbers, occupancy, formation, disruptions), boards, labels and derived values, in three time zones.
 */
class GoldenTest {
    private val sets = listOf(GoldenZurich, GoldenNewYork, GoldenKolkata)

    private fun parse(s: String?): JsonElement = Json.parseToJsonElement(s!!)
    private fun ms(e: JsonElement) = Instant.fromEpochMilliseconds(e.jsonPrimitive.long)
    private fun JsonElement.str(key: String) = jsonObject[key]!!.jsonPrimitive.content

    @Test
    fun everyPairOfStationsBuildsTheSameConnections() = sets.forEach { g ->
        val zone = TimeZone.of(g.zone)
        val times = parse(g.pairTimes).jsonArray.map(::ms)
        val expected = parse(g.pairHashes).jsonObject
        for (a in STATIONS) {
            val parts = mutableListOf<String>()
            for (b in STATIONS) {
                if (a.id == b.id) continue
                for ((i, t) in times.withIndex()) {
                    val label = listOf("2026-11-21T19:35", "2026-11-22T07:58")[i]
                    parts += "${a.id}>${b.id}@$label\n" + findConnections(a, b, t, zone = zone, webOnly = true).joinToString("\n") { dumpConnection(it) }
                }
            }
            assertEquals(expected[a.id]!!.jsonPrimitive.content, h64(parts.joinToString("\n")), "connections from ${a.id} in ${g.zone}")
        }
    }

    @Test
    fun selectedSearchesMatchLineForLine() = sets.forEach { g ->
        val zone = TimeZone.of(g.zone)
        for (c in parse(g.cases).jsonArray) {
            val got = findConnections(
                station(c.str("from")), station(c.str("to")), ms(c.jsonObject["at"]!!), c.jsonObject["count"]!!.jsonPrimitive.int, zone,
            )
            assertEquals(c.str("dump"), got.joinToString("\n") { dumpConnection(it) }, "${c.str("from")}>${c.str("to")} in ${g.zone}")
        }
    }

    @Test
    fun departureBoards() = sets.forEach { g ->
        val zone = TimeZone.of(g.zone)
        val times = parse(g.boardTimes).jsonArray.map(::ms)
        val hashes = parse(g.boardHashes).jsonObject
        for (s in STATIONS) {
            val dump = times.joinToString("\n#\n") { t -> departures(s, t, 8, zone).joinToString("\n") { dumpRow(it) } }
            assertEquals(hashes[s.id]!!.jsonPrimitive.content, h64(dump), "board ${s.id} in ${g.zone}")
        }
        for (b in parse(g.boards).jsonArray) {
            val got = departures(station(b.str("station")), ms(b.jsonObject["at"]!!), b.jsonObject["count"]!!.jsonPrimitive.int, zone)
            assertEquals(b.str("dump"), got.joinToString("\n") { dumpRow(it) }, "board ${b.str("station")} in ${g.zone}")
        }
    }

    @Test
    fun clockLabels() = sets.forEach { g ->
        val zone = TimeZone.of(g.zone)
        val c = parse(g.clock).jsonObject
        for (e in c["instants"]!!.jsonArray) {
            val t = ms(e.jsonObject["t"]!!)
            assertEquals(e.str("hhmm"), hhmm(t, zone), "hhmm $t ${g.zone}")
            assertEquals(e.str("longDate"), longDate(t, zone))
            assertEquals(e.str("shortDate"), shortDate(t, zone))
        }
        for (e in c["dayLabels"]!!.jsonArray) {
            assertEquals(e.str("label"), dayLabel(ms(e.jsonObject["d"]!!), ms(e.jsonObject["ref"]!!), zone), "dayLabel $e ${g.zone}")
        }
        for (e in c["durations"]!!.jsonArray) assertEquals(e.str("label"), durationLabel(e.jsonObject["m"]!!.jsonPrimitive.int))
        val base = Instant.fromEpochMilliseconds(1_000_000_000_000)
        for (e in c["between"]!!.jsonArray) {
            val d = e.jsonObject["d"]!!.jsonPrimitive.long
            assertEquals(e.jsonObject["min"]!!.jsonPrimitive.int, minutesBetween(base, Instant.fromEpochMilliseconds(base.toEpochMilliseconds() + d)), "minutesBetween $d")
        }
        for (e in c["addMin"]!!.jsonArray) {
            assertEquals(e.jsonObject["t"]!!.jsonPrimitive.long, addMin(base, e.jsonObject["m"]!!.jsonPrimitive.int).toEpochMilliseconds())
        }
        for (e in c["parse"]!!.jsonArray) {
            assertEquals(e.jsonObject["ms"]!!.jsonPrimitive.longOrNull, parseJsDate(e.str("s"), zone), "Date.parse(\"${e.str("s")}\") in ${g.zone}")
        }
    }

    @Test
    fun getConnectionRebuildsFromIds() {
        val zone = TimeZone.of(GoldenZurich.zone)
        for (e in parse(GoldenZurich.ids).jsonArray) {
            val id = e.str("id")
            // Deliberate deviation: for a non-numeric time the web builds a connection full of NaN; Kotlin returns null.
            val dump = e.jsonObject["dump"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }?.takeUnless { "@NaN" in it }
            assertEquals(dump, getConnection(id, zone)?.let { dumpConnection(it) }, "getConnection(\"$id\")")
        }
    }

    @Test
    fun derivedValues() {
        val zone = TimeZone.of(GoldenZurich.zone)
        for (e in parse(GoldenZurich.derive).jsonArray) {
            val o = e.jsonObject
            val c = getConnection(o["id"]!!.jsonPrimitive.content, zone)!!
            assertEquals(o["durationMin"]!!.jsonPrimitive.int, c.durationMin)
            assertEquals(o["changePositions"]!!.jsonArray.map { it.jsonPrimitive.double }, c.changePositions(), c.id)
            assertEquals(o["allDisruptions"]!!.jsonArray.map { it.jsonPrimitive.content }, c.allDisruptions().map { it.id })
            assertEquals(
                o["transfers"]!!.jsonArray.map { it.jsonPrimitive.content },
                c.transfers().map { "${it.at.id}|${it.minutes}|${it.fromLeg.id}|${it.toLeg.id}" },
            )
            for (a in o["at"]!!.jsonArray) {
                val now = ms(a.jsonObject["t"]!!)
                val what = "${c.id} at $now"
                assertEquals(a.jsonObject["progress"]!!.jsonPrimitive.doubleOrNull, c.progress(now), "progress $what")
                assertEquals(
                    a.jsonObject["steps"]!!.jsonArray.map { it.jsonPrimitive.content },
                    c.stepCards(now).map {
                        when (it) {
                            is StepCard.Arrival -> "arrival:${it.remainingMin}"
                            is StepCard.Change -> "change:${it.transfer.at.id}"
                            is StepCard.Departure -> "departure:${it.stop.station.id}"
                        }
                    },
                    "steps $what",
                )
                assertEquals(a.jsonObject["currentStep"]!!.jsonPrimitive.int, c.currentStep(now), "currentStep $what")
                assertEquals(a.jsonObject["currentLeg"]!!.jsonPrimitive.int, c.currentLeg(now), "currentLeg $what")
                val (lon, lat) = a.jsonObject["train"]!!.jsonArray.map { it.jsonPrimitive.double }
                assertEquals(LonLat(lon, lat), c.trainPosition(now), "trainPosition $what")
            }
        }
    }

    @Test
    fun stationSearchDistancesAndPrices() {
        for (e in parse(GoldenZurich.search).jsonArray) {
            assertEquals(e.jsonObject["ids"]!!.jsonArray.map { it.jsonPrimitive.content }, searchStations(e.str("q")).map { it.id }, "search \"${e.str("q")}\"")
        }
        for (e in parse(GoldenZurich.distances).jsonArray) {
            val km = distanceKm(station(e.str("a")), station(e.str("b")))
            val want = e.jsonObject["km"]!!.jsonPrimitive.double
            // Platform math libraries may differ in the last bit; the rounding-sensitive uses are pinned by the hashes.
            assertTrue(kotlin.math.abs(km - want) <= 1e-9 * want, "distance ${e.str("a")}-${e.str("b")}: $km vs $want")
        }
        for (e in parse(GoldenZurich.prices).jsonArray) {
            assertEquals(e.str("s"), formatPrice(e.jsonObject["x"]!!.jsonPrimitive.double), "toFixed(2) of ${e.jsonObject["x"]}")
        }
    }
}

// ------------------------------------------------------------------ dumps, identical to the TS generator's

private fun u(v: Any?) = v?.toString() ?: "-"
private fun ms(d: Instant?) = d?.toEpochMilliseconds()?.toString() ?: "-"
private fun esc(s: String?) = s?.replace("\\", "\\\\")?.replace("\n", "\\n")?.replace("|", "\\p") ?: "-"
private fun occ(o: OccupancyPair) = "${o.first.code}/${o.second.code}"

internal fun dumpConnection(c: Connection): String {
    val out = mutableListOf(
        "C|${c.id}|${c.from.id}|${c.to.id}|${ms(c.dep)}|${ms(c.arr)}|${c.priceFrom?.let { jsRound(it * 100) } ?: "-"}|${c.priceFrom?.let(::formatPrice) ?: "-"}",
    )
    for (l in c.legs) {
        out += "L|${l.id}|${l.mode.code}|${u(l.product?.code)}|${u(l.line)}|${u(l.number)}|${esc(l.direction)}|" +
            "${l.attrs.joinToString(",")}|${occ(l.occupancy)}|${u(l.walkMinutes)}"
        for (s in l.stops) {
            out += "S|${s.station.id}|${ms(s.arr)}|${ms(s.dep)}|${u(s.delay)}|${u(s.platform)}|${if (s.platformChanged) 1 else 0}|${s.occupancy?.let(::occ) ?: "-"}"
        }
        for (d in l.disruptions) out += "D|${d.id}|${d.kind.code}|${esc(d.summary)}|${esc(d.cause)}|${esc(d.title)}|${esc(d.body)}"
        for (f in l.formation.orEmpty()) out += "F|${f.number}|${f.cls}|${f.sector}|${f.occupancy.code}|${f.features.joinToString(",") { it.code }}"
    }
    return out.joinToString("\n")
}

internal fun dumpRow(r: DepartureRow) =
    "R|${r.id}|${r.mode.code}|${r.product.code}|${r.line}|${r.time.toEpochMilliseconds()}|${u(r.delay)}|${esc(r.direction)}|${r.platform}"

/** FNV-1a 64 over UTF-16 code units, as hex (same as the generator). */
internal fun h64(s: String): String {
    var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    for (ch in s) h = (h xor ch.code.toLong()) * 0x100000001b3L
    return h.toULong().toString(16).padStart(16, '0')
}
