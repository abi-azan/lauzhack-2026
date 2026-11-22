package railstatus.mobile.data

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Mock station list with approximate coordinates, ported from `mobile/src/data/stations.ts`.

private fun st(id: String, name: String, lat: Double, lon: Double, kind: StationKind = StationKind.STATION) =
    Station(id, name, lat, lon, kind)

val STATIONS: List<Station> = listOf(
    st("lausanne", "Lausanne", 46.5167, 6.6291),
    st("renens", "Renens VD", 46.537, 6.5785),
    st("epfl", "EPFL", 46.5223, 6.5664, StationKind.METRO),
    st("lausanne-flon", "Lausanne-Flon", 46.5207, 6.6302, StationKind.METRO),
    st("morges", "Morges", 46.5108, 6.495),
    st("allaman", "Allaman", 46.4715, 6.399),
    st("nyon", "Nyon", 46.3832, 6.2397),
    st("geneve", "Genève", 46.2101, 6.1424),
    st("geneve-aeroport", "Genève-Aéroport", 46.2321, 6.1102),
    st("vevey", "Vevey", 46.463, 6.8433),
    st("montreux", "Montreux", 46.4352, 6.9104),
    st("palezieux", "Palézieux", 46.5426, 6.8373),
    st("romont", "Romont FR", 46.6966, 6.9186),
    st("fribourg", "Fribourg/Freiburg", 46.8032, 7.1513),
    st("yverdon", "Yverdon-les-Bains", 46.7814, 6.6408),
    st("neuchatel", "Neuchâtel", 46.9967, 6.9357),
    st("ins", "Ins", 47.0055, 7.1063),
    st("kerzers", "Kerzers", 46.9758, 7.1956),
    st("biel", "Biel/Bienne", 47.1325, 7.243),
    st("bern", "Bern", 46.949, 7.4391),
    st("bern-wankdorf", "Bern Wankdorf", 46.9676, 7.465),
    st("ostermundigen", "Ostermundigen", 46.9564, 7.4874),
    st("guemligen", "Gümligen", 46.9339, 7.5058),
    st("muensingen", "Münsingen", 46.874, 7.5615),
    st("belp", "Belp", 46.8916, 7.4994),
    st("thun", "Thun", 46.7548, 7.6297),
    st("spiez", "Spiez", 46.6863, 7.68),
    st("interlaken-ost", "Interlaken Ost", 46.6904, 7.8691),
    st("visp", "Visp", 46.294, 7.8812),
    st("brig", "Brig", 46.3194, 7.9884),
    st("sion", "Sion", 46.2275, 7.3592),
    st("martigny", "Martigny", 46.1027, 7.0726),
    st("olten", "Olten", 47.3518, 7.9075),
    st("aarau", "Aarau", 47.3913, 8.0512),
    st("basel", "Basel SBB", 47.5476, 7.5897),
    st("zuerich", "Zürich HB", 47.3779, 8.5402),
    st("zuerich-flughafen", "Zürich Flughafen", 47.4504, 8.5624),
    st("winterthur", "Winterthur", 47.5003, 8.7237),
    st("st-gallen", "St. Gallen", 47.4233, 9.3697),
    st("luzern", "Luzern", 47.0502, 8.3101),
    st("zug", "Zug", 47.1736, 8.515),
    st("arth-goldau", "Arth-Goldau", 47.0494, 8.5476),
    st("chur", "Chur", 46.8532, 9.529),
    st("bellinzona", "Bellinzona", 46.1955, 9.0294),
    st("lugano", "Lugano", 46.0055, 8.9468),
    st("locarno", "Locarno", 46.1723, 8.8013),
    st("schaffhausen", "Schaffhausen", 47.6979, 8.6327),
    st("genolier", "Genolier", 46.4348, 6.2189),
)

private val byId: Map<String, Station> = STATIONS.associateBy { it.id }

/** The station with this id; throws for an unknown one, like the web's `station()`. */
fun station(id: String): Station = byId[id] ?: throw IllegalArgumentException("unknown station $id")

fun findStation(id: String?): Station? = if (id.isNullOrEmpty()) null else byId[id]

// `s.normalize('NFD').replace(/[\u0300-\u036f]/g, '')` without java.text: every precomposed Latin letter
// (U+00C0–U+024F, U+1E00–U+1EFF) whose decomposition carries combining marks, mapped to what is left without them.
// Generated from the Unicode database. Outside Latin, only loose combining marks are dropped.
private const val FOLD_FROM = "ÀÁÂÃÄÅÇÈÉÊËÌÍÎÏÑÒÓÔÕÖÙÚÛÜÝàáâãäåçèéêëìíîïñòóôõöùúûüýÿĀāĂăĄąĆćĈĉĊċČčĎďĒēĔĕĖėĘęĚěĜĝĞğĠġĢģĤĥĨĩĪīĬĭĮįİĴĵĶķĹĺĻļĽľŃńŅņŇňŌōŎŏŐőŔŕŖŗŘřŚśŜŝŞşŠšŢţŤťŨũŪūŬŭŮůŰűŲųŴŵŶŷŸŹźŻżŽžƠơƯưǍǎǏǐǑǒǓǔǕǖǗǘǙǚǛǜǞǟǠǡǢǣǦǧǨǩǪǫǬǭǮǯǰǴǵǸǹǺǻǼǽǾǿȀȁȂȃȄȅȆȇȈȉȊȋȌȍȎȏȐȑȒȓȔȕȖȗȘșȚțȞȟȦȧȨȩȪȫȬȭȮȯȰȱȲȳḀḁḂḃḄḅḆḇḈḉḊḋḌḍḎḏḐḑḒḓḔḕḖḗḘḙḚḛḜḝḞḟḠḡḢḣḤḥḦḧḨḩḪḫḬḭḮḯḰḱḲḳḴḵḶḷḸḹḺḻḼḽḾḿṀṁṂṃṄṅṆṇṈṉṊṋṌṍṎṏṐṑṒṓṔṕṖṗṘṙṚṛṜṝṞṟṠṡṢṣṤṥṦṧṨṩṪṫṬṭṮṯṰṱṲṳṴṵṶṷṸṹṺṻṼṽṾṿẀẁẂẃẄẅẆẇẈẉẊẋẌẍẎẏẐẑẒẓẔẕẖẗẘẙẛẠạẢảẤấẦầẨẩẪẫẬậẮắẰằẲẳẴẵẶặẸẹẺẻẼẽẾếỀềỂểỄễỆệỈỉỊịỌọỎỏỐốỒồỔổỖỗỘộỚớỜờỞởỠỡỢợỤụỦủỨứỪừỬửỮữỰựỲỳỴỵỶỷỸỹ"
private const val FOLD_TO = "AAAAAACEEEEIIIINOOOOOUUUUYaaaaaaceeeeiiiinooooouuuuyyAaAaAaCcCcCcCcDdEeEeEeEeEeGgGgGgGgHhIiIiIiIiIJjKkLlLlLlNnNnNnOoOoOoRrRrRrSsSsSsSsTtTtUuUuUuUuUuUuWwYyYZzZzZzOoUuAaIiOoUuUuUuUuUuAaAaÆæGgKkOoOoƷʒjGgNnAaÆæØøAaAaEeEeIiIiOoOoRrRrUuUuSsTtHhAaEeOoOoOoOoYyAaBbBbBbCcDdDdDdDdDdEeEeEeEeEeFfGgHhHhHhHhHhIiIiKkKkKkLlLlLlLlMmMmMmNnNnNnNnOoOoOoOoPpPpRrRrRrRrSsSsSsSsSsTtTtTtTtUuUuUuUuUuVvVvWwWwWwWwWwXxXxYyZzZzZzhtwyſAaAaAaAaAaAaAaAaAaAaAaAaEeEeEeEeEeEeEeEeIiIiOoOoOoOoOoOoOoOoOoOoOoOoUuUuUuUuUuUuUuYyYyYyYy"
private val FOLD: Map<Char, Char> = FOLD_FROM.indices.associate { FOLD_FROM[it] to FOLD_TO[it] }

private fun fold(s: String): String = buildString {
    for (c in s) if (c !in '\u0300'..'\u036f') append(FOLD[c] ?: c)
}.lowercase()

/** Up to 8 stations: names starting with [q] first, then names containing it. Accents and case are ignored. */
fun searchStations(q: String): List<Station> {
    val f = fold(q.trim())
    if (f.isEmpty()) return emptyList()
    val starts = STATIONS.filter { fold(it.name).startsWith(f) }
    val contains = STATIONS.filter { !fold(it.name).startsWith(f) && fold(it.name).contains(f) }
    return (starts + contains).take(8)
}

/** Great-circle distance (haversine), evaluated in the same order as the JS. */
fun distanceKm(a: Station, b: Station): Double {
    val r = 6371.0
    val dLat = ((b.lat - a.lat) * PI) / 180
    val dLon = ((b.lon - a.lon) * PI) / 180
    val sLat = sin(dLat / 2)
    val sLon = sin(dLon / 2)
    // Same evaluation order as the JS (`x ** 2` is x * x in fdlibm's pow).
    val h = sLat * sLat + cos((a.lat * PI) / 180) * cos((b.lat * PI) / 180) * (sLon * sLon)
    return 2 * r * asin(sqrt(h))
}

/** The icon mode for a station in search results: metro, bus, else train. */
val Station.searchMode: Mode
    get() = when (kind) {
        StationKind.METRO -> Mode.METRO
        StationKind.BUS -> Mode.BUS
        StationKind.STATION -> Mode.TRAIN
    }
