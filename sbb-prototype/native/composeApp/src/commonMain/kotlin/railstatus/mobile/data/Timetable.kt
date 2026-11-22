@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.data

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlin.math.abs

// Mock timetable, ported from `mobile/src/data/timetable.ts`. Swiss trains run on a clock-face timetable, so a few
// hourly patterns per corridor generate realistic connections for any time of day. Lausanne–Bern and Bern–Thun
// follow what the real SBB Mobile showed on 21.11.2026; every other pair gets a plausible generic pattern.
//
// Everything here is deterministic: the same pattern and departure always build the same connection, with the same
// id, train numbers, platforms and occupancy as the web app. That is what lets [getConnection] rebuild a saved trip
// or a deep link from its id alone.

private class StopSpec(val id: String, val arr: Int?, val dep: Int?, val platform: String)

private enum class FormationKind { IC, IR, RE, S }

private class LegSpec(
    val product: Product,
    val line: String?,
    val direction: (hour: Int) -> String,
    val numberBase: Long,
    val stops: List<StopSpec>,
    val attrs: List<Attr>,
    val formation: FormationKind,
    val disruptions: ((dep: Instant, zone: TimeZone) -> List<Disruption>)? = null,
)

private class Pattern(
    val key: String,
    val from: String,
    val to: String,
    val minutes: List<Int>,
    val price: Double,
    val legs: List<LegSpec>,
    /** Per-departure variations (platform changes, delays). */
    val tweak: ((Connection, hour: Int) -> Connection)? = null,
    /**
     * From the deleted web app. Later patterns (false) stay off the departure boards and out of [findConnections] with
     * `webOnly`, so the golden parity tests keep checking the ported behaviour.
     */
    val fromWeb: Boolean = true,
)

// ---------------------------------------------------------------- disruptions

private fun fmtDay(d: Instant, zone: TimeZone): String {
    val t = d.local(zone)
    return "${t.day.toString().padStart(2, '0')}.${t.month.number.toString().padStart(2, '0')}.${t.year}"
}

/** The construction notice on IR 15 Lausanne–Bern: from 06:00 on the departure's day until the next day, 23:59. */
fun renensAllaman(dep: Instant, zone: TimeZone = AppClock.zone): Disruption {
    val start = localInstant(dep.local(zone).date, 6, 0, zone)
    val end = addMin(start, 24 * 60)
    return Disruption(
        id = "renens-allaman",
        kind = DisruptionKind.CONSTRUCTION,
        summary = "Limited service between Renens VD and Allaman (train)",
        cause = "Due to construction work",
        title = "Limited service: Renens VD - Allaman",
        body = "Limited train service between Renens VD and Allaman.\n" +
            "This is due to construction work.\n" +
            "The restriction lasts from ${fmtDay(start, zone)}, 06:00 until ${fmtDay(end, zone)}, 23:59.\n" +
            "For your connection, please check the online timetable.",
    )
}

private fun platformChange() = Disruption(id = "platform-change", kind = DisruptionKind.PLATFORM_CHANGE, summary = "Platform change")

private fun delayNote(min: Int) = Disruption(id = "delay-$min", kind = DisruptionKind.DELAY, summary = "Delay of approx. $min minutes")

// ---------------------------------------------------------------- patterns

private fun s(id: String, arr: Int?, dep: Int?, platform: String) = StopSpec(id, arr, dep, platform)
private fun fixed(direction: String): (Int) -> String = { direction }

private val IR15_ATTRS = listOf(Attr.NF, Attr.BZ, Attr.VO)

private val PATTERNS: List<Pattern> = listOf(
    Pattern(
        key = "lausanne>bern#ic1", from = "lausanne", to = "bern", minutes = listOf(17), price = 18.0,
        legs = listOf(
            LegSpec(
                product = Product.IC, line = "1", numberBase = 700, formation = FormationKind.IC,
                direction = { h -> if (h % 2 != 0) "Zürich HB" else "St. Gallen" },
                attrs = listOf(Attr.WR, Attr.NF, Attr.FZ, Attr.BZ, Attr.R, Attr.VO),
                stops = listOf(s("lausanne", null, 0, "1"), s("fribourg", 40, 42, "2"), s("bern", 69, null, "8")),
            ),
        ),
    ),
    Pattern(
        key = "lausanne>bern#ic5", from = "lausanne", to = "bern", minutes = listOf(34), price = 22.0,
        legs = listOf(
            LegSpec(
                product = Product.IC, line = "5", numberBase = 500, formation = FormationKind.IC, direction = fixed("Zürich HB"),
                attrs = listOf(Attr.WR, Attr.FZ, Attr.BZ, Attr.R, Attr.VO),
                stops = listOf(s("lausanne", null, 0, "4"), s("yverdon", 21, 22, "2"), s("neuchatel", 47, null, "5")),
            ),
            LegSpec(
                product = Product.IR, line = "66", numberBase = 3900, formation = FormationKind.RE, direction = fixed("Bern"),
                attrs = listOf(Attr.NF, Attr.KW),
                stops = listOf(s("neuchatel", null, 58, "2"), s("ins", 69, 70, "1"), s("kerzers", 76, 77, "3"), s("bern", 94, null, "50")),
            ),
        ),
        tweak = { c, h -> if (h % 3 == 0) delay(c, 0, 3) else c },
    ),
    Pattern(
        key = "lausanne>bern#ir15", from = "lausanne", to = "bern", minutes = listOf(40), price = 18.0,
        legs = listOf(
            LegSpec(
                product = Product.IR, line = "15", numberBase = 2501, formation = FormationKind.IR, direction = fixed("Luzern"),
                attrs = IR15_ATTRS,
                disruptions = { dep, zone -> listOf(renensAllaman(dep, zone)) },
                stops = listOf(
                    s("lausanne", null, 0, "1"), s("palezieux", 17, 17, "2"), s("romont", 33, 33, "2"),
                    s("fribourg", 52, 53, "3"), s("bern", 76, null, "10"),
                ),
            ),
        ),
        tweak = { c, h -> if (h % 2 == 0) changePlatform(c, 0, "5") else c },
    ),
    // Lausanne–Genève (the UX spec's Plan demo): IC 1 non-stop, IR 95 (as on the Lausanne board) via Morges and Nyon,
    // and RE 33 stopping through the Renens VD–Allaman section, so it carries that section's construction notice.
    Pattern(
        key = "lausanne>geneve#ic1", from = "lausanne", to = "geneve", minutes = listOf(45), price = 13.6, fromWeb = false,
        legs = listOf(
            LegSpec(
                product = Product.IC, line = "1", numberBase = 709, formation = FormationKind.IC, direction = fixed("Genève-Aéroport"),
                attrs = listOf(Attr.WR, Attr.NF, Attr.FZ, Attr.BZ, Attr.R, Attr.VO),
                stops = listOf(s("lausanne", null, 0, "3"), s("geneve", 36, null, "5")),
            ),
        ),
        tweak = { c, h -> if (h % 2 == 0) changePlatform(c, 0, "4") else c },
    ),
    Pattern(
        key = "lausanne>geneve#ir95", from = "lausanne", to = "geneve", minutes = listOf(19, 49), price = 15.0, fromWeb = false,
        legs = listOf(
            LegSpec(
                product = Product.IR, line = "95", numberBase = 1705, formation = FormationKind.IR, direction = fixed("Genève-Aéroport"),
                attrs = IR15_ATTRS,
                stops = listOf(
                    s("lausanne", null, 0, "8"), s("morges", 9, 10, "3"), s("nyon", 24, 25, "2"), s("geneve", 38, null, "4"),
                ),
            ),
        ),
    ),
    Pattern(
        key = "lausanne>geneve#re33", from = "lausanne", to = "geneve", minutes = listOf(7), price = 11.4, fromWeb = false,
        legs = listOf(
            LegSpec(
                product = Product.RE, line = "33", numberBase = 18207, formation = FormationKind.RE, direction = fixed("Annemasse"),
                attrs = listOf(Attr.NF, Attr.BI, Attr.VO),
                disruptions = { dep, zone -> listOf(renensAllaman(dep, zone)) },
                stops = listOf(
                    s("lausanne", null, 0, "6"), s("renens", 4, 5, "1"), s("morges", 11, 12, "4"), s("allaman", 19, 20, "2"),
                    s("nyon", 30, 31, "3"), s("geneve", 45, null, "2"),
                ),
            ),
        ),
    ),
    Pattern(
        key = "bern>thun#ic8", from = "bern", to = "thun", minutes = listOf(4), price = 13.6,
        legs = listOf(
            LegSpec(
                product = Product.IC, line = "8", numberBase = 900, formation = FormationKind.IC, direction = fixed("Brig"),
                attrs = listOf(Attr.WR, Attr.NF, Attr.BZ, Attr.FZ, Attr.VO),
                stops = listOf(s("bern", null, 0, "6"), s("thun", 17, 18, "1")),
            ),
        ),
    ),
    Pattern(
        key = "bern>thun#s1", from = "bern", to = "thun", minutes = listOf(14, 44), price = 13.6,
        legs = listOf(
            LegSpec(
                product = Product.S, line = "1", numberBase = 15100, formation = FormationKind.S, direction = fixed("Thun"),
                attrs = listOf(Attr.NF, Attr.VO),
                stops = listOf(
                    s("bern", null, 0, "12"), s("bern-wankdorf", 4, 4, "2"), s("ostermundigen", 6, 7, "1"),
                    s("guemligen", 10, 10, "1"), s("muensingen", 17, 18, "2"), s("thun", 30, null, "4"),
                ),
            ),
        ),
    ),
    Pattern(
        key = "bern>thun#ic61", from = "bern", to = "thun", minutes = listOf(34), price = 13.6,
        legs = listOf(
            LegSpec(
                product = Product.IC, line = "61", numberBase = 1000, formation = FormationKind.IC, direction = fixed("Interlaken Ost"),
                attrs = listOf(Attr.NF, Attr.BZ, Attr.VO, Attr.FZ),
                stops = listOf(s("bern", null, 0, "5"), s("thun", 19, 20, "2")),
            ),
        ),
    ),
)

/** Every stop of the leg gets the delay, and the leg a "Delay of approx. N minutes" note at the end. */
private fun delay(c: Connection, legIdx: Int, min: Int): Connection = c.mapLeg(legIdx) { leg ->
    leg.copy(stops = leg.stops.map { it.copy(delay = min) }, disruptions = leg.disruptions + delayNote(min))
}

/** The boarding platform changes, and the leg gets a "Platform change" note first. */
private fun changePlatform(c: Connection, legIdx: Int, platform: String): Connection = c.mapLeg(legIdx) { leg ->
    val stops = leg.stops.toMutableList()
    stops[0] = stops[0].copy(platform = platform, platformChanged = true)
    leg.copy(stops = stops, disruptions = listOf(platformChange()) + leg.disruptions)
}

private fun Connection.mapLeg(i: Int, f: (Leg) -> Leg) = copy(legs = legs.mapIndexed { j, l -> if (j == i) f(l) else l })

// ---------------------------------------------------------------- generic pairs

private val HUBS = listOf("bern", "zuerich", "olten", "lausanne", "luzern", "biel", "basel")
private val IC_LINES = listOf("1", "3", "5", "6", "8", "21", "51", "61")
private val IR_LINES = listOf("13", "16", "17", "35", "36", "37", "70", "90", "95")
private val S_LINES = listOf("1", "2", "3", "4", "5", "6", "9")

private fun travelMin(a: Station, b: Station): Int = jsRound(distanceKm(a, b) * 1.15 + 6).toInt()

/** [seed] is a uint32 hash, or that hash `>>> 3`; never negative. */
private fun genericLeg(a: Station, b: Station, offset: Int, seed: Long): LegSpec {
    val km = distanceKm(a, b)
    val product = if (km > 45) Product.IC else if (km > 18) Product.IR else Product.S
    val lines = when (product) {
        Product.IC -> IC_LINES
        Product.IR -> IR_LINES
        else -> S_LINES
    }
    val t = travelMin(a, b)
    return LegSpec(
        product = product,
        line = lines[(seed % lines.size).toInt()],
        numberBase = 100 + (seed % 2000),
        formation = when (product) {
            Product.IC -> FormationKind.IC
            Product.IR -> FormationKind.IR
            else -> FormationKind.S
        },
        direction = fixed(b.name),
        attrs = if (product == Product.S) listOf(Attr.NF, Attr.VO) else listOf(Attr.NF, Attr.BZ, Attr.VO),
        stops = listOf(
            StopSpec(a.id, null, offset, (1 + (seed % 9)).toString()),
            StopSpec(b.id, offset + t, null, (1 + (jsUshr(seed, 4) % 12)).toString()),
        ),
    )
}

private fun genericPatterns(from: Station, to: Station): List<Pattern> {
    val km = distanceKm(from, to)
    if (km < 1.5) return emptyList()
    val seed = fnv1a("${from.id}>${to.id}")
    val base = (seed % 30).toInt()
    val price = jsRound((km * 0.31 + 3) * 5) / 5.0
    val direct = Pattern(
        key = "${from.id}>${to.id}#g0", from = from.id, to = to.id, minutes = listOf(base, base + 30), price = price,
        legs = listOf(genericLeg(from, to, 0, seed)),
    )
    if (km < 70) return listOf(direct)
    val hub = HUBS.filter { it != from.id && it != to.id }
        .map { station(it) }
        .map { it to distanceKm(from, it) + distanceKm(it, to) }
        .sortedBy { it.second }
        .firstOrNull()
    if (hub == null || hub.second > km * 1.3) return listOf(direct)
    val first = genericLeg(from, hub.first, 0, seed)
    val arrHub = first.stops[1].arr!!
    val second = genericLeg(hub.first, to, arrHub + 6, jsUshr(seed, 3))
    val via = Pattern(
        key = "${from.id}>${to.id}#g1", from = from.id, to = to.id, minutes = listOf((base + 15) % 60), price = price,
        legs = listOf(first, second),
    )
    return listOf(direct, via)
}

// ---------------------------------------------------------------- builder

private fun seededOcc(seed: Long, hour: Int): Occupancy {
    val rush = (hour in 7..8) || (hour in 17..18)
    val r = seed % 10
    if (rush) return if (r < 4) Occupancy.MEDIUM else if (r < 8) Occupancy.HIGH else Occupancy.LOW
    return if (r < 7) Occupancy.LOW else Occupancy.MEDIUM
}

private class CoachSpec(val cls: Int, val sector: String, val features: List<CoachFeature>)

private val FORMATIONS: Map<FormationKind, List<CoachSpec>> = run {
    val nf = CoachFeature.NF
    val bike = CoachFeature.BIKE
    val stroller = CoachFeature.STROLLER
    val wheelchair = CoachFeature.WHEELCHAIR
    fun c(cls: Int, sector: String, vararg f: CoachFeature) = CoachSpec(cls, sector, f.toList())
    mapOf(
        FormationKind.IR to listOf(
            c(1, "B", nf, bike), c(1, "C", stroller, nf, bike), c(1, "C", nf, bike),
            c(2, "C", wheelchair, nf), c(2, "D", stroller, nf), c(2, "D", nf, bike),
        ),
        FormationKind.IC to listOf(
            c(2, "F", bike), c(2, "E"), c(1, "E", CoachFeature.BUSINESS), c(1, "D", wheelchair),
            c(2, "D", CoachFeature.RESTAURANT), c(2, "C", CoachFeature.FAMILY, stroller), c(2, "B", nf, bike), c(2, "A", nf),
        ),
        FormationKind.RE to listOf(
            c(2, "C", stroller, nf, bike), c(2, "C", wheelchair, nf), c(1, "D", stroller, nf), c(1, "D", stroller, nf, bike),
        ),
        FormationKind.S to listOf(
            c(2, "A", nf, bike), c(2, "B", nf, stroller), c(1, "B", nf), c(2, "C", nf, wheelchair),
        ),
    )
}

private fun formation(kind: FormationKind, seed: Long, hour: Int): List<Coach> =
    FORMATIONS.getValue(kind).mapIndexed { i, c ->
        Coach(number = i + 1, cls = c.cls, sector = c.sector, features = c.features, occupancy = seededOcc(seed + i * 7, hour))
    }

/** A train's formation from its product alone (trains from outside the timetable: board rows, the checked-in train). */
internal fun formationFor(product: Product, seed: Long, hour: Int): List<Coach>? {
    val kind = when (product) {
        Product.IC, Product.EC, Product.ICE -> FormationKind.IC
        Product.IR, Product.PE -> FormationKind.IR
        Product.RE -> FormationKind.RE
        Product.S, Product.SN -> FormationKind.S
        else -> null
    }
    return kind?.let { formation(it, seed, hour) }
}

/** Seeded occupancy (1st, 2nd class) for a train from outside the timetable. */
internal fun rideOccupancy(seed: Long, hour: Int): OccupancyPair = OccupancyPair(seededOcc(seed, hour), seededOcc(jsUshr(seed, 5), hour))

private fun build(p: Pattern, dep: Instant, zone: TimeZone): Connection {
    val id = "${p.key}@${jsRound(dep.toEpochMilliseconds() / 60_000.0)}"
    val hour = dep.local(zone).hour
    val legs = p.legs.mapIndexed { i, ls ->
        val seed = fnv1a("$id:$i")
        val occ = OccupancyPair(seededOcc(seed, hour), seededOcc(jsUshr(seed, 5), hour))
        val stops = ls.stops.mapIndexed { j, spec ->
            Stop(
                station = station(spec.id),
                arr = spec.arr?.let { addMin(dep, it) },
                dep = spec.dep?.let { addMin(dep, it) },
                platform = spec.platform,
                occupancy = OccupancyPair(seededOcc(seed + j, hour), seededOcc(jsUshr(seed, 3) + j, hour)),
            )
        }
        Leg(
            id = "$id:L$i",
            mode = modeOf(ls.product),
            product = ls.product,
            line = ls.line,
            number = (ls.numberBase + hour * 2).toString(),
            direction = ls.direction(hour),
            stops = stops,
            attrs = ls.attrs,
            occupancy = occ,
            disruptions = ls.disruptions?.invoke(dep, zone) ?: emptyList(),
            formation = formation(ls.formation, seed, hour),
        )
    }
    val c = Connection(
        id = id,
        from = station(p.from),
        to = station(p.to),
        dep = legs.first().stops.first().dep!!,
        arr = legs.last().stops.last().arr!!,
        legs = legs,
        priceFrom = p.price,
    )
    return p.tweak?.invoke(c, hour) ?: c
}

internal fun modeOf(p: Product): Mode = when (p) {
    Product.B -> Mode.BUS
    Product.T -> Mode.TRAM
    Product.M -> Mode.METRO
    else -> Mode.TRAIN
}

private fun patternsFor(from: Station, to: Station, webOnly: Boolean = false): List<Pattern> {
    val own = PATTERNS.filter { it.from == from.id && it.to == to.id && (it.fromWeb || !webOnly) }
    return own.ifEmpty { genericPatterns(from, to) }
}

// ---------------------------------------------------------------- public API

/** Connections departing at or after [at] (30 s grace), sorted, like the SBB connection list. */
fun findConnections(
    from: Station,
    to: Station,
    at: Instant,
    count: Int = 6,
    zone: TimeZone = AppClock.zone,
    /** Only the web app's patterns (golden tests). */
    webOnly: Boolean = false,
): List<Connection> {
    val out = mutableListOf<Connection>()
    val start = startOfLocalHour(at, zone)
    val patterns = patternsFor(from, to, webOnly)
    for (h in -1 until 6) {
        val hourStart = addMin(start, h * 60)
        for (p in patterns) {
            for (m in p.minutes) {
                val dep = addMin(hourStart, m)
                if (dep.toEpochMilliseconds() >= at.toEpochMilliseconds() - 30_000) out += build(p, dep, zone)
            }
        }
    }
    return out.sortedBy { it.dep }.take(count)
}

private val JS_DECIMAL = Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$""")

/** Rebuilds a connection from its id (`<pattern key>@<epoch minutes>`), so deep links and saved trips survive restarts. */
fun getConnection(id: String, zone: TimeZone = AppClock.zone): Connection? {
    if (id == DemoTrip.ID) return DemoTrip.connection(zone = zone)
    if (id.startsWith(DEP_PREFIX)) return departureConnection(id, zone)
    val parts = id.split('@')
    val key = parts[0]
    val minutes = parts.getOrNull(1)
    val pair = key.split('#')[0].split('>')
    val from = findStation(pair[0])
    val to = findStation(pair.getOrNull(1))
    if (from == null || to == null || minutes.isNullOrEmpty()) return null
    val p = patternsFor(from, to).find { it.key == key } ?: return null
    // `new Date(Number(minutes) * 60_000)`; ids that don't make a valid date give null instead of a broken connection.
    val n = minutes.trim().takeIf { it.isEmpty() || JS_DECIMAL.matches(it) }?.let { if (it.isEmpty()) 0.0 else it.toDouble() } ?: return null
    val ms = n * 60_000
    if (!ms.isFinite() || abs(ms) > 8.64e15) return null
    return build(p, Instant.fromEpochMilliseconds(ms.toLong()), zone)
}

// ---------------------------------------------------------------- departure boards

private class LocalLine(
    val product: Product, val line: String, val mode: Mode, val direction: String,
    val every: Int, val offset: Int, val platform: String,
)

private val LOCAL_LINES: Map<String, List<LocalLine>> = mapOf(
    "lausanne" to listOf(
        LocalLine(Product.IR, "95", Mode.TRAIN, "Genève-Aéroport", 30, 19, "8"),
        LocalLine(Product.B, "1", Mode.BUS, "Maladière", 10, 0, "B"),
        LocalLine(Product.B, "3", Mode.BUS, "Maillefer", 10, 0, "D"),
        LocalLine(Product.M, "m2", Mode.METRO, "Lausanne, Ouchy-Olympique", 6, 2, ""),
        LocalLine(Product.M, "m2", Mode.METRO, "Epalinges, Croisettes", 6, 4, ""),
        LocalLine(Product.S, "3", Mode.TRAIN, "Villeneuve", 30, 13, "3"),
        LocalLine(Product.RE, "33", Mode.TRAIN, "Annemasse", 60, 7, "6"),
    ),
)

/** The next [count] departures from [at] at or after [from] (1 min grace), for the Plan tab's departure board. */
fun departures(at: Station, from: Instant, count: Int = 5, zone: TimeZone = AppClock.zone): List<DepartureRow> {
    val rows = mutableListOf<DepartureRow>()
    val start = startOfLocalHour(from, zone)
    val local = LOCAL_LINES[at.id] ?: emptyList()
    val own = PATTERNS.filter { it.from == at.id && it.fromWeb }
    for (h in 0 until 3) {
        val hs = addMin(start, h * 60)
        for (l in local) {
            var m = l.offset
            while (m < 60) {
                val t = addMin(hs, m)
                val seed = fnv1a("${at.id}${l.line}${t.toEpochMilliseconds()}")
                rows += DepartureRow(
                    id = "${l.line}-${t.toEpochMilliseconds()}", mode = l.mode, product = l.product, line = l.line, time = t,
                    direction = l.direction, platform = l.platform, delay = if (seed % 9 == 0L) 3 else null,
                )
                m += l.every
            }
        }
        for (p in own) {
            for (m in p.minutes) {
                val c = build(p, addMin(hs, m), zone)
                val leg = c.legs[0]
                rows += DepartureRow(
                    id = c.id, mode = leg.mode, product = leg.product!!, line = leg.line!!, time = c.dep,
                    direction = leg.direction!!, platform = leg.stops[0].platform!!, delay = leg.stops[0].delay,
                )
            }
        }
    }
    if (local.isEmpty() && own.isEmpty()) {
        // Generic board: trains towards the nearest big stations.
        val near = STATIONS.filter { it.id != at.id && it.kind == StationKind.STATION }
            .sortedBy { distanceKm(at, it) }
            .take(4)
        for (h in 0 until 2) {
            near.forEachIndexed { i, s ->
                val seed = fnv1a("${at.id}${s.id}")
                val t = addMin(start, (h * 60 + ((seed % 30) + i * 7) % 60).toInt())
                rows += DepartureRow(
                    id = "${s.id}-${t.toEpochMilliseconds()}", mode = Mode.TRAIN, product = if (i % 2 != 0) Product.IR else Product.S,
                    line = (1 + (seed % 20)).toString(), time = t, direction = s.name, platform = (1 + (seed % 6)).toString(),
                )
            }
        }
    }
    return rows.filter { it.time.toEpochMilliseconds() >= from.toEpochMilliseconds() - 60_000 }.sortedBy { it.time }.take(count)
}

/** Destinations for the Touch timetable tiles (station ids). */
val TOUCH_TILES: List<String> = listOf(
    "bern", "lausanne", "olten", "geneve", "renens", "fribourg", "basel", "zuerich", "luzern", "winterthur", "st-gallen", "lugano",
)

// ---------------------------------------------------------------- a departure board row as a journey

private const val DEP_PREFIX = "dep:"

/**
 * The journey id for a departure board row, so tapping a row opens its train's Journey screen (as SBB does). Rows from
 * a timetable pattern already carry a connection id; the board's local lines get a `dep:` id that [getConnection]
 * rebuilds: the board station, the line, its direction, platform and time.
 */
fun departureJourneyId(at: Station, r: DepartureRow): String =
    if (getConnection(r.id) != null) r.id
    else DEP_PREFIX + listOf(at.id, r.product.code, r.line, r.direction, r.platform, r.time.toEpochMilliseconds() / 60_000, r.delay ?: "").joinToString("|")

private fun departureConnection(id: String, zone: TimeZone): Connection? {
    val f = id.removePrefix(DEP_PREFIX).split('|')
    if (f.size != 6 && f.size != 7) return null
    // The board row's delay (its red time and clock), so the journey shows "ca +2'" at its stops as SBB does.
    val delay = f.getOrNull(6)?.toIntOrNull()?.takeIf { it != 0 }
    val from = findStation(f[0]) ?: return null
    val product = Product.entries.firstOrNull { it.code == f[1] } ?: return null
    val line = f[2]; val direction = f[3]; val platform = f[4]
    val dep = Instant.fromEpochMilliseconds((f[5].toLongOrNull() ?: return null) * 60_000)
    val mode = modeOf(product)
    // The terminus: a known station by name, else a stop of its own name near the board station.
    val to = STATIONS.firstOrNull { it.name == direction }
        ?: Station(id = "dep-" + fnv1a(direction).toString(16), name = direction, lat = from.lat + 0.012, lon = from.lon + 0.012, kind = from.kind)
    val minutes = when {
        to.id.startsWith("dep-") -> when (mode) { Mode.METRO -> 10; Mode.BUS -> 14; Mode.TRAM -> 12; else -> 30 }
        else -> travelMin(from, to)
    }
    val seed = fnv1a(id)
    val hour = dep.local(zone).hour
    val occ = OccupancyPair(seededOcc(seed, hour), seededOcc(jsUshr(seed, 5), hour))
    val leg = Leg(
        id = "$id:L0",
        mode = mode,
        product = product,
        line = line,
        number = (100 + seed % 900).toString(),
        direction = direction,
        stops = listOf(
            Stop(station = from, dep = dep, delay = delay, platform = platform.ifEmpty { null }, occupancy = occ),
            Stop(station = to, arr = addMin(dep, minutes), delay = delay, occupancy = occ),
        ),
        attrs = if (mode == Mode.TRAIN) listOf(Attr.NF, Attr.VO) else listOf(Attr.NF),
        occupancy = occ,
        disruptions = emptyList(),
        formation = formationFor(product, seed, hour),
    )
    return Connection(id = id, from = from, to = to, dep = dep, arr = addMin(dep, minutes), legs = listOf(leg))
}
