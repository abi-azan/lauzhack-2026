package railstatus.mobile.data

import kotlin.math.floor

// The data side of `mobile/src/state/router.ts` and `route()` in App.tsx: the web app's paths (`#/plan/c/<id>/map`),
// parsed into [Route]s, so deep links and push notifications that carry them open the same screen natively.
//
//   /plan                       Plan (timetable)          /plan/touch        Plan (touch timetable)
//   /plan/search/from|to        station search            /plan/connections  connection list for the current from/to
//   /plan/map                   network map
//   /plan/c/<id>                connection detail         /plan/c/<id>/map   travel companion (map + step cards)
//   /plan/c/<id>/leg/<n>        journey information (?tab=formation for the formation)
//   /trips, /trips/commuting, /trips/c/<id>/...           saved trips, same sub-routes
//   /easyride, /easyride/incident, /easyride/chat, /incident, /checkin/<trainId>?car=<n>
//   /tickets, /shop, /profile, /profile/notifications

enum class Tab(val segment: String) {
    PLAN("plan"), TRIPS("trips"), EASYRIDE("easyride"), TICKETS("tickets"), SHOP("shop"), PROFILE("profile");
}

/** "/plan/c/x?tab=1" → ["plan", "c", "x"] */
fun segments(path: String): List<String> = path.substringBefore('?').split('/').filter { it.isNotEmpty() }

/** Where in-app back goes when there is no history: up one level, or two after `c/<id>`, `leg/<n>`, `search/<field>`. */
fun parentPath(path: String): String {
    val segs = segments(path)
    val drop = if (segs.size >= 2 && segs[segs.size - 2] in setOf("c", "leg", "search")) 2 else 1
    return "/" + segs.take(maxOf(1, segs.size - drop)).joinToString("/")
}

enum class ConnectionView { LIST, MAP }

sealed interface Route {
    data class Plan(val touch: Boolean) : Route
    data class StationSearch(val field: StationField) : Route
    data object Connections : Route
    data object NetworkMap : Route
    /** [base] is [Tab.PLAN] or [Tab.TRIPS]. */
    data class ConnectionDetail(val id: String, val base: Tab, val view: ConnectionView) : Route
    /** [legIdx] is null when the path's index isn't a number (the screen then shows "No journey information."). */
    data class Journey(val id: String, val base: Tab, val legIdx: Int?, val formation: Boolean) : Route
    data class Trips(val commuting: Boolean) : Route
    data object CheckIn : Route
    data object EasyRideIncident : Route
    data object Chat : Route
    data object Incident : Route
    data class CheckInLink(val trainId: String?, val car: String?) : Route
    data object Tickets : Route
    data object Shop : Route
    data object Profile : Route
    data object Notifications : Route
}

/** The screen for a path, exactly as App.tsx's `route()` picks it. Unknown paths go to Plan. */
fun parseRoute(path: String): Route {
    val segs = segments(path)
    val tab = segs.getOrNull(0)
    val rest = segs.drop(1)
    val query = parseQuery(path.split('?').getOrNull(1) ?: "")
    if ((tab == "plan" || tab == "trips") && rest.getOrNull(0) == "c" && !rest.getOrNull(1).isNullOrEmpty()) {
        val id = decodeURIComponent(rest[1])
        val base = if (tab == "plan") Tab.PLAN else Tab.TRIPS
        return when (rest.getOrNull(2)) {
            "map" -> Route.ConnectionDetail(id, base, ConnectionView.MAP)
            "leg" -> Route.Journey(id, base, legIndex(rest.getOrNull(3)), query["tab"] == "formation")
            else -> Route.ConnectionDetail(id, base, ConnectionView.LIST)
        }
    }
    return when (tab) {
        "plan" -> when (rest.getOrNull(0)) {
            "touch" -> Route.Plan(touch = true)
            "search" -> Route.StationSearch(if (rest.getOrNull(1) == "to") StationField.TO else StationField.FROM)
            "connections" -> Route.Connections
            "map" -> Route.NetworkMap
            else -> Route.Plan(touch = false)
        }
        "trips" -> Route.Trips(commuting = rest.getOrNull(0) == "commuting")
        "easyride" -> when (rest.getOrNull(0)) {
            "chat" -> Route.Chat
            "incident" -> Route.EasyRideIncident
            else -> Route.CheckIn
        }
        "incident" -> Route.Incident
        "checkin" -> Route.CheckInLink(rest.getOrNull(0), query["car"])
        "tickets" -> Route.Tickets
        "shop" -> Route.Shop
        "profile" -> if (rest.getOrNull(0) == "notifications") Route.Notifications else Route.Profile
        else -> Route.Plan(touch = false)
    }
}

/** `/plan/c/<encoded id>`, `/trips/c/<encoded id>/map` */
fun connectionPath(base: Tab, id: String, view: ConnectionView = ConnectionView.LIST): String =
    "/${base.segment}/c/${encodeURIComponent(id)}" + if (view == ConnectionView.MAP) "/map" else ""

/** `/plan/c/<encoded id>/leg/<n>`, with `?tab=formation` for the formation tab. */
fun journeyPath(base: Tab, id: String, legIdx: Int, formation: Boolean = false): String =
    "/${base.segment}/c/${encodeURIComponent(id)}/leg/$legIdx" + if (formation) "?tab=formation" else ""

/** `Number(s ?? 0)` as a leg index; null for NaN or a non-integer. */
private fun legIndex(s: String?): Int? {
    val t = (s ?: "0").trim()
    if (t.isEmpty()) return 0
    val d = if (JS_DECIMAL_NUMBER.matches(t)) t.toDouble() else return null
    return if (d == floor(d) && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
}

private val JS_DECIMAL_NUMBER = Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$""")

private const val URI_UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
private const val HEX = "0123456789ABCDEF"

/** JavaScript's `encodeURIComponent`. */
fun encodeURIComponent(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val v = b.toInt() and 0xff
        val c = v.toChar()
        if (v < 0x80 && c in URI_UNRESERVED) append(c) else append('%').append(HEX[v shr 4]).append(HEX[v and 15])
    }
}

/** JavaScript's `decodeURIComponent` ('+' stays '+'). Malformed input is returned as is (JS would throw). */
fun decodeURIComponent(s: String): String = percentDecode(s, strict = true) ?: s

/**
 * %XX sequences to UTF-8 bytes to text. Strict: a stray '%' or invalid UTF-8 gives null. Lenient (`URLSearchParams`):
 * a stray '%' stays, invalid UTF-8 becomes U+FFFD.
 */
private fun percentDecode(s: String, strict: Boolean): String? {
    val bytes = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val hi = if (c == '%') s.getOrNull(i + 1)?.digitToIntOrNull(16) else null
        val lo = if (hi != null) s.getOrNull(i + 2)?.digitToIntOrNull(16) else null
        if (c == '%' && hi != null && lo != null) {
            bytes += (hi * 16 + lo).toByte()
            i += 3
            continue
        }
        if (c == '%' && strict) return null
        // Copy one character (a surrogate pair together) as UTF-8.
        val end = if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) i + 2 else i + 1
        s.substring(i, end).encodeToByteArray().forEach { bytes += it }
        i = end
    }
    val arr = bytes.toByteArray()
    return if (strict) runCatching { arr.decodeToString(throwOnInvalidSequence = true) }.getOrNull() else arr.decodeToString()
}

/** `URLSearchParams.get` semantics: first value wins, '+' is a space. */
private fun parseQuery(q: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (pair in q.split('&')) {
        if (pair.isEmpty()) continue
        val k = pair.substringBefore('=')
        val v = if ('=' in pair) pair.substringAfter('=') else ""
        val dk = percentDecode(k.replace('+', ' '), strict = false)!!
        val dv = percentDecode(v.replace('+', ' '), strict = false)!!
        if (dk !in out) out[dk] = dv
    }
    return out
}
