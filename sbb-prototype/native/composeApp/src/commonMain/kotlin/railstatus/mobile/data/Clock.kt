@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The app clock, ported from `mobile/src/data/clock.ts`. The web app's `?now=2026-11-21T19:35` pins the start time
 * for demo rehearsals and the clock keeps running from there; here that is [pin], e.g. from an intent extra.
 *
 * All local-time logic (hours, days, "HH:MM") uses [zone]. The web uses the browser's zone; this defaults to
 * Europe/Zurich, which is the same on any phone in Switzerland and keeps the timetable Swiss elsewhere.
 * Set the fields from one thread (startup, tests).
 */
object AppClock {
    var zone: TimeZone = TimeZone.of("Europe/Zurich")

    /** The real clock in epoch ms. Replace it in tests. */
    var wallClock: () -> Long = { Clock.System.now().toEpochMilliseconds() }

    /** How far the app clock runs ahead of the real one; 0 unless [pin]ned. */
    var offsetMs: Long = 0
        private set

    /**
     * Pins the app clock to [override] (the web app's `?now=` value) from this moment on; it keeps running from there.
     * Null, or a string `Date.parse` would reject, unpins it (offset 0), as the web app ignores an invalid `?now=`.
     * Returns whether the clock is pinned.
     */
    fun pin(override: String?): Boolean {
        val at = override?.let { parseJsDate(it, zone) }
        offsetMs = if (at == null) 0 else at - wallClock()
        return at != null
    }

    /** `now()`: the app's current time. */
    fun now(): Instant = Instant.fromEpochMilliseconds(wallClock() + offsetMs)

    /** The real time, ignoring [pin]. */
    fun wallNow(): Instant = Instant.fromEpochMilliseconds(wallClock())
}

/** The app's current time (see [AppClock]). */
fun now(): Instant = AppClock.now()

/** `useNow(intervalMs)`: emits [now] at once and then every [intervalMs]. Collect it in a composable with `collectAsState(now())`. */
fun nowFlow(intervalMs: Long = 20_000): Flow<Instant> = flow {
    while (true) {
        emit(now())
        delay(intervalMs)
    }
}

/** "07:05" in local time. */
fun hhmm(d: Instant, zone: TimeZone = AppClock.zone): String {
    val t = d.local(zone)
    return "${pad(t.hour)}:${pad(t.minute)}"
}

fun addMin(d: Instant, m: Int): Instant = Instant.fromEpochMilliseconds(d.toEpochMilliseconds() + m * 60_000L)

/** Whole minutes from [a] to [b], rounded like `Math.round`. */
fun minutesBetween(a: Instant, b: Instant): Int =
    jsRound((b.toEpochMilliseconds() - a.toEpochMilliseconds()) / 60_000.0).toInt()

/** "45 min", "1 h", "1 h 16 min". */
fun durationLabel(min: Int): String {
    val h = min.floorDiv(60)
    val m = min % 60
    if (h == 0) return "$m min"
    return if (m != 0) "$h h $m min" else "$h h"
}

private val DAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
private fun pad(n: Int) = n.toString().padStart(2, '0')
private fun dayName(t: LocalDateTime) = DAYS[t.dayOfWeek.isoDayNumber % 7]

/** "Saturday 21.11.2026" */
fun longDate(d: Instant, zone: TimeZone = AppClock.zone): String {
    val t = d.local(zone)
    return "${dayName(t)} ${pad(t.day)}.${pad(t.month.number)}.${t.year}"
}

/** "Sat 21.11." */
fun shortDate(d: Instant, zone: TimeZone = AppClock.zone): String {
    val t = d.local(zone)
    return "${dayName(t).take(3)} ${pad(t.day)}.${pad(t.month.number)}."
}

/** "Today", "Tomorrow", otherwise [shortDate]. */
fun dayLabel(d: Instant, ref: Instant, zone: TimeZone = AppClock.zone): String {
    val a = localInstant(d.local(zone).date, 0, 0, zone)
    val b = localInstant(ref.local(zone).date, 0, 0, zone)
    val diff = jsRound((a.toEpochMilliseconds() - b.toEpochMilliseconds()) / 86_400_000.0)
    if (diff == 0L) return "Today"
    if (diff == 1L) return "Tomorrow"
    return shortDate(d, zone)
}

private val JS_DATE = Regex(
    """^([+-]\d{6}|\d{4})(?:-(\d{2})(?:-(\d{2}))?)?(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?)?(Z|[+-]\d{2}:\d{2})?$""",
)

/**
 * The ISO forms of `Date.parse`, in epoch ms, or null where it gives NaN. As in JavaScript, a date-time without an
 * offset is local time, a date alone is UTC midnight, and day 29–31 rolls over into the next month as in V8.
 * (`Date.parse` also takes legacy formats like
 * "Nov 21 2026"; those aren't supported.)
 */
fun parseJsDate(s: String, zone: TimeZone = AppClock.zone): Long? {
    val m = JS_DATE.matchEntire(s) ?: return null
    val (y, mo, d, h, mi, sec, frac, off) = m.destructured
    return runCatching {
        // V8 takes any day 1..31 and rolls over: "2026-02-30" is 2 March.
        val day = d.ifEmpty { "1" }.toInt()
        require(day in 1..31)
        val date = LocalDate(y.toInt(), mo.ifEmpty { "1" }.toInt(), 1).plus(day - 1, DateTimeUnit.DAY)
        val nanos = frac.take(3).padEnd(3, '0').toInt() * 1_000_000
        val local = if (h.isEmpty()) LocalDateTime(date.year, date.month, date.day, 0, 0)
        else LocalDateTime(date.year, date.month, date.day, h.toInt(), mi.toInt(), sec.ifEmpty { "0" }.toInt(), nanos)
        when {
            off.isNotEmpty() -> local.toInstant(if (off == "Z") UtcOffset.ZERO else UtcOffset.parse(off))
            h.isEmpty() -> local.toInstant(TimeZone.UTC)
            else -> local.toInstant(zone)
        }.toEpochMilliseconds()
    }.getOrNull()
}
