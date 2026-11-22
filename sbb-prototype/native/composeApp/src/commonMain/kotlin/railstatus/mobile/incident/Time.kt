@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.incident

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import railstatus.chat.jsNumber
import railstatus.chat.orZero
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// The bits of mobile/src/data/clock.ts the incident code uses. The stores take the clock and the zone as
// parameters; the app passes the shared app clock (railstatus.mobile.data.AppClock), tests a fixed one.

/** The default zone for local times ("HH:MM"), like AppClock's. */
val SWISS_ZONE: TimeZone = TimeZone.of("Europe/Zurich")

/** The default clock: the system's. The app passes `{ railstatus.mobile.data.now() }` to honour a pinned demo time. */
val systemClock: () -> Instant = { Clock.System.now() }

/** "07:05" in [zone]. */
fun hhmm(d: Instant, zone: TimeZone): String {
    val t = d.toLocalDateTime(zone)
    return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

fun addMin(d: Instant, m: Int): Instant = d + m.minutes

/** live.ts `todayAt`: `now` with its local clock set to "HH:MM" (`Date.setHours(h || 0, m || 0, 0, 0)`). */
internal fun todayAt(hhmm: String, now: Instant, zone: TimeZone): Instant {
    val parts = hhmm.split(":").map(::jsNumber)
    val h = orZero(parts.getOrElse(0) { Double.NaN }).toInt()
    val m = orZero(parts.getOrElse(1) { Double.NaN }).toInt()
    val date = now.toLocalDateTime(zone).date
    if (h in 0..23 && m in 0..59) return LocalDateTime(date, LocalTime(h, m)).toInstant(zone)
    // setHours rolls hours ≥ 24 and minutes ≥ 60 over into the next day/hour.
    return date.atStartOfDayIn(zone) + (h * 60 + m).minutes
}
