@file:OptIn(ExperimentalTime::class)

package railstatus.chat

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// JavaScript semantics the engine relies on, so the Kotlin port answers exactly like engine.ts on
// every platform. Pure common Kotlin: no java.*, so it runs on Android, the JVM and wasmJs.

// ---------- regex ----------
// Kotlin's Regex is java.util.regex on the JVM, ICU on Android (\b, \w, \d, \s are Unicode there) and
// a Harmony port on wasm. JavaScript without the `u` flag treats \b, \w and \d as ASCII and \s as its own
// whitespace set. jsRegex() spells them out, so a pattern matches the same everywhere.

private const val W = "[A-Za-z0-9_]"
private const val WB = "(?:(?<=$W)(?!$W)|(?<!$W)(?=$W))"

/** JavaScript's `\s` and `String.prototype.trim()` whitespace, as a character-class body. */
private const val JS_SPACE_CHARS =
    "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

/** A JavaScript regex source as a Kotlin Regex: ASCII \b \w \d, JavaScript's \s. */
internal fun jsRegex(source: String): Regex = Regex(
    source
        .replace("\\b", WB)
        .replace("\\w", W)
        .replace("\\d", "[0-9]")
        .replace("\\s", "[$JS_SPACE_CHARS]"),
)

private val JS_TRIM = Regex("^[$JS_SPACE_CHARS]+|[$JS_SPACE_CHARS]+$")

/** `String.prototype.trim()`: Kotlin's trim() uses a different whitespace set. */
fun jsTrim(s: String): String = JS_TRIM.replace(s, "")

// ---------- Unicode NFD without java.text ----------
// norm() lowercases, decomposes (NFD) and drops the combining marks U+0300–U+036F, then keeps only a–z and
// 0–9. The only characters whose decomposition leaves an a–z letter behind are these 246 precomposed
// lowercase letters (generated from java.text.Normalizer over every code point; none outside the BMP).
// Every other non-ASCII character becomes a separator either way, so this table is exact for norm().

private const val NFD_FROM = "àáâãäåçèéêëìíîïñòóôõöùúûüýÿāăąćĉċčďēĕėęěĝğġģĥĩīĭįĵķĺļľńņňōŏőŕŗřśŝşšţťũūŭůűųŵŷźżžơưǎǐǒǔǖǘǚǜǟǡǧǩǫǭǰǵǹǻȁȃȅȇȉȋȍȏȑȓȕȗșțȟȧȩȫȭȯȱȳḁḃḅḇḉḋḍḏḑḓḕḗḙḛḝḟḡḣḥḧḩḫḭḯḱḳḵḷḹḻḽḿṁṃṅṇṉṋṍṏṑṓṕṗṙṛṝṟṡṣṥṧṩṫṭṯṱṳṵṷṹṻṽṿẁẃẅẇẉẋẍẏẑẓẕẖẗẘẙạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ"
private const val NFD_TO = "aaaaaaceeeeiiiinooooouuuuyyaaaccccdeeeeegggghiiiijklllnnnooorrrssssttuuuuuuwyzzzouaiouuuuuaagkoojgnaaaeeiioorruusthaeooooyabbbcdddddeeeeefghhhhhiikkkllllmmmnnnnoooopprrrrsssssttttuuuuuvvwwwwwxxyzzzhtwyaaaaaaaaaaaaeeeeeeeeiioooooooooooouuuuuuuyyyy"
private val NFD_BASE: Map<Char, Char> = NFD_FROM.indices.associate { NFD_FROM[it] to NFD_TO[it] }

/** `s.normalize('NFD').replace(/[\u0300-\u036f]/g, '')` as far as norm() can tell. */
internal fun stripAccents(s: String): String {
    val out = StringBuilder(s.length)
    for (c in s) {
        if (c in '\u0300'..'\u036f') continue
        out.append(NFD_BASE[c] ?: c)
    }
    return out.toString()
}

// ---------- numbers ----------

private val JS_DECIMAL = Regex("^[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?$")
private val JS_HEX = Regex("^0[xX][0-9a-fA-F]+$")

/** `Number(s)`; NaN as Double.NaN. */
internal fun jsNumber(s: String): Double {
    val t = jsTrim(s)
    if (t.isEmpty()) return 0.0
    return when {
        t == "Infinity" || t == "+Infinity" -> Double.POSITIVE_INFINITY
        t == "-Infinity" -> Double.NEGATIVE_INFINITY
        JS_DECIMAL.matches(t) -> t.toDouble()
        JS_HEX.matches(t) -> t.substring(2).toLong(16).toDouble()
        else -> Double.NaN
    }
}

/** `String(n)` for the numbers the engine prints: integers without ".0". */
fun jsNumberString(n: Double): String = when {
    n.isNaN() -> "NaN"
    n == Double.POSITIVE_INFINITY -> "Infinity"
    n == Double.NEGATIVE_INFINITY -> "-Infinity"
    n == floor(n) && abs(n) < 1e21 -> n.toLong().toString()
    else -> n.toString()
}

/** `x || 0` for a number. */
internal fun orZero(n: Double): Double = if (n.isNaN()) 0.0 else n

// ---------- dates ----------

/** The "local time" of JavaScript's Date (the browser's zone). Tests pin it; the app leaves the device's. */
object JsLocal {
    var zone: TimeZone = TimeZone.currentSystemDefault()
}

private val JS_DATE = Regex(
    "^([+-][0-9]{6}|[0-9]{4})(?:-([0-9]{2})(?:-([0-9]{2}))?)?" +
        "(?:[T ]([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\\.([0-9]+))?)?)?(Z|[+-][0-9]{2}:[0-9]{2})?$",
)

/**
 * `Date.parse(s)` for the ISO forms: with an offset or `Z`; without one it is local time ([localZone]), as in
 * JavaScript; a date alone is UTC midnight. Null where JavaScript gives NaN. (Legacy forms like
 * "Sep 17 2026" aren't supported.)
 */
internal fun jsDateParse(s: String, localZone: TimeZone = JsLocal.zone): Long? {
    val m = JS_DATE.matchEntire(s) ?: return null
    val g = m.groupValues
    val y = g[1]; val mo = g[2]; val d = g[3]; val h = g[4]; val mi = g[5]; val sec = g[6]; val frac = g[7]; val off = g[8]
    if (h.isEmpty() && off.isNotEmpty()) return null
    return try {
        val date = LocalDate(y.toInt(), mo.ifEmpty { "1" }.toInt(), d.ifEmpty { "1" }.toInt())
        val nanos = frac.take(3).padEnd(3, '0').toInt() * 1_000_000
        val hour = if (h.isEmpty()) 0 else h.toInt()
        // JavaScript accepts 24:00 (the end of the day).
        val endOfDay = hour == 24 && mi == "00" && sec.ifEmpty { "0" }.toInt() == 0 && nanos == 0
        val time = if (h.isEmpty() || endOfDay) LocalTime(0, 0) else LocalTime(hour, mi.toInt(), sec.ifEmpty { "0" }.toInt(), nanos)
        val local = LocalDateTime(date, time)
        val instant = when {
            off.isNotEmpty() -> local.toInstant(if (off == "Z") UtcOffset.ZERO else UtcOffset.parse(off))
            h.isEmpty() -> local.toInstant(UtcOffset.ZERO)
            else -> local.toInstant(localZone)
        }
        instant.toEpochMilliseconds() + if (endOfDay) 86_400_000L else 0L
    } catch (_: Exception) {
        null
    }
}

private fun pad(n: Int, len: Int = 2) = n.toString().padStart(len, '0')

/** `new Date(ms).toISOString()`: "2026-09-17T15:45:00.000Z", always with milliseconds. */
internal fun jsIsoString(epochMs: Long): String {
    val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.UTC)
    val ms = ((epochMs % 1000) + 1000) % 1000
    return "${pad(t.year, 4)}-${pad(t.month.number)}-${pad(t.day)}T${pad(t.hour)}:${pad(t.minute)}:${pad(t.second)}.${pad(ms.toInt(), 3)}Z"
}

/** An IANA zone name as `Intl.DateTimeFormat` takes it (case-insensitive), or null if unknown. */
internal fun jsZone(name: String): TimeZone? {
    try { return TimeZone.of(name) } catch (_: Exception) { }
    val match = try { TimeZone.availableZoneIds.firstOrNull { it.equals(name, ignoreCase = true) } } catch (_: Exception) { null }
    return match?.let { try { TimeZone.of(it) } catch (_: Exception) { null } }
}
