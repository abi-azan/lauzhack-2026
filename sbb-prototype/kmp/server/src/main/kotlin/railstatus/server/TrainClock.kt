package railstatus.server

import railstatus.model.Train
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Re-times a fixed timetable for the live demo.
 *
 * The trains (e.g. the real 1 Sep 2026 Bern – Thun trains) carry the times of their own day, but the
 * incident updates are stamped with the wall clock. Shifting every `arr`/`dep` so that [anchor] (an
 * "HH:MM" of the trains' day) falls on the current minute makes the phones show consistent times,
 * while keeping the trains' real order and spacing. Train times are display data only: which trains
 * an incident hits depends on their stops and line, never on their times.
 */
object TrainClock {
    private val SWISS: ZoneId = ZoneId.of("Europe/Zurich")
    private val hhmm = Regex("""^(\d{1,2}):(\d{2})$""")

    /** Minutes to add to every time so that [anchor] lands on [at]; null if [anchor] is unset or invalid. */
    fun delta(anchor: String?, at: OffsetDateTime = now()): Int? {
        val a = anchor?.trim()?.let(::minutes) ?: return null
        val n = at.atZoneSameInstant(SWISS).let { it.hour * 60 + it.minute }
        return n - a
    }

    fun shift(trains: List<Train>, anchor: String?, at: OffsetDateTime = now()): List<Train> {
        val d = delta(anchor, at) ?: return trains
        if (d == 0) return trains
        return trains.map { t ->
            t.copy(stops = t.stops.map { s -> s.copy(arr = s.arr?.let { plus(it, d) }, dep = s.dep?.let { plus(it, d) }) })
        }
    }

    private fun minutes(s: String): Int? =
        hhmm.find(s)?.let { m -> m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt() }?.takeIf { it < 24 * 60 }

    private fun plus(s: String, d: Int): String {
        val m = minutes(s) ?: return s
        val x = Math.floorMod(m + d, 24 * 60)
        return "%02d:%02d".format(x / 60, x % 60)
    }
}
