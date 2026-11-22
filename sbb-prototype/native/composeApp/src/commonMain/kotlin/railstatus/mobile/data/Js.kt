@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.data

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.floor
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// The few JavaScript number and Date semantics the ported code depends on, so the Kotlin produces exactly what the
// web app produced (same connection ids, seeds, platforms, labels). Common Kotlin only (KMP-ready).

/** `Math.round`: halves round towards +infinity. */
internal fun jsRound(x: Double): Long = floor(x + 0.5).toLong()

/** JS `x >>> n` on a number below 2^32 (a seed): unsigned, so the result stays non-negative. */
internal fun jsUshr(x: Long, n: Int): Long = (x and 0xffffffffL) ushr n

/**
 * `Number.prototype.toFixed(2)` for |x| < 2^53 / 100: rounds the exact binary value, ties away from zero, so 1.005
 * gives "1.00" as in JavaScript (the double is just below 1.005).
 */
internal fun jsToFixed2(x: Double): String {
    if (x.isNaN()) return "NaN"
    val neg = x < 0
    val v = if (neg) -x else x
    val bits = v.toRawBits()
    val exp = ((bits ushr 52) and 0x7ff).toInt()
    val frac = bits and 0xfffffffffffffL
    // v = m * 2^e exactly.
    val m = if (exp == 0) frac else frac or (1L shl 52)
    val e = (if (exp == 0) 1 else exp) - 1075
    val cents: Long = when {
        m == 0L -> 0
        e >= 0 -> (m shl e) * 100 // integer value; fine for the prices this is used for
        -e > 60 -> 0 // m * 100 < 2^60, so below half a cent
        else -> {
            val k = -e
            val num = m * 100
            val q = num shr k
            val rem = num - (q shl k)
            if (rem * 2 >= (1L shl k)) q + 1 else q
        }
    }
    val s = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
    return if (neg && cents != 0L) "-$s" else s
}

internal fun Instant.local(zone: TimeZone): LocalDateTime = toLocalDateTime(zone)

/**
 * `new Date(...)` plus a local setter (`setHours(6, 0, 0, 0)`, `setMinutes(0, 0, 0)`): the instant of a wall-clock time
 * in [zone], resolved as ECMAScript does (an ambiguous time takes the earlier instant, a time in a gap moves forward
 * by the gap), which is also kotlinx-datetime's rule.
 */
internal fun localInstant(date: LocalDate, hour: Int, minute: Int, zone: TimeZone): Instant =
    LocalDateTime(date, LocalTime(hour, minute)).toInstant(zone)

/** `d.setMinutes(0, 0, 0)` */
internal fun startOfLocalHour(t: Instant, zone: TimeZone): Instant {
    val l = t.local(zone)
    return localInstant(l.date, l.hour, 0, zone)
}

/** FNV-1a over UTF-16 code units, as `hash()` in timetable.ts (`Math.imul`, `>>> 0`). */
internal fun fnv1a(s: String): Long {
    var h = 2166136261L.toInt()
    for (ch in s) h = (h xor ch.code) * 16777619
    return h.toLong() and 0xffffffffL
}
