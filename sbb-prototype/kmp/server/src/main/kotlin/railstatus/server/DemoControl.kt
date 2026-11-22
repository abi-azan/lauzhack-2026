package railstatus.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import railstatus.model.DemoBeatInfo
import railstatus.model.DemoStatus
import railstatus.model.RailJson
import railstatus.model.TickerEvent
import railstatus.model.TickerInput
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * The dashboard's "Next beat": the 17 Sep evening in the same 4 beats as `replay.py --beats`, sent by the server itself
 * through the ticker stream, so the operator drives the whole demo from one screen. The messages come from
 * `demo-beats.json` (`replay.py --export-beats`), and their clock times move onto the demo clock like `shift_times`.
 *
 * It's hard to break on purpose: beats go strictly in order, a click while one is sending is ignored, and a reset
 * starts over.
 */
class DemoControl(private val bus: Bus, private val scope: CoroutineScope, private val gapMs: Long = 700) {
    private class Beat(val name: String, val events: List<Pair<Double, TickerInput>>)

    private val t0: LocalDateTime
    private val beats: List<Beat>

    init {
        // demo-beats.json holds SBB's internal ticker texts: it isn't in public copies of the repo. Without it there
        // are no beats, and the dashboard shows no demo bar.
        val root = DemoControl::class.java.getResource("/demo-beats.json")?.readText()?.let { RailJson.parseToJsonElement(it).jsonObject }
        t0 = root?.let { LocalDateTime.parse(it["t0"]!!.jsonPrimitive.content).truncatedTo(ChronoUnit.SECONDS) } ?: LocalDateTime.now()
        beats = root?.get("beats")?.jsonArray.orEmpty().map { b ->
            val o = b.jsonObject
            Beat(o["name"]!!.jsonPrimitive.content, o["events"]!!.jsonArray.map { e ->
                val ev = e.jsonObject
                ev["offsetMin"]!!.jsonPrimitive.content.toDouble() to
                    RailJson.decodeFromJsonElement(TickerInput.serializer(), JsonObject(ev - "offsetMin"))
            })
        }
    }

    private val mutex = Mutex()
    private var next = 1
    private var job: Job? = null
    private var wall0: OffsetDateTime? = null

    suspend fun status() = mutex.withLock { statusLocked() }

    private fun statusLocked() = DemoStatus(
        next = next, total = beats.size, sending = job?.isActive == true,
        beats = beats.mapIndexed { i, b -> DemoBeatInfo(i + 1, b.name, b.events.size, THEN.getOrElse(i) { "" }) },
    )

    /** Sends beat [n] if it's the next one and nothing is sending; otherwise changes nothing (a double click). */
    suspend fun send(n: Int): DemoStatus = mutex.withLock {
        if (n != next || next > beats.size || job?.isActive == true) return@withLock statusLocked()
        val beat = beats[n - 1]
        val start = wall0 ?: now().also { wall0 = it }
        next++
        job = scope.launch {
            log.info("demo: beat $n/${beats.size} \"${beat.name}\" (${beat.events.size} messages)")
            beat.events.forEachIndexed { i, (_, input) ->
                if (i > 0) delay(gapMs)
                val ev = input.copy(text = shiftTimes(input.text, t0, start)).toEvent()
                bus.publish(Streams.TICKER, RailJson.encodeToString(TickerEvent.serializer(), ev))
            }
        }
        statusLocked()
    }

    suspend fun reset() = mutex.withLock {
        job?.cancel()
        job = null
        next = 1
        wall0 = null
    }

    companion object {
        private val log = LoggerFactory.getLogger(DemoControl::class.java)

        /** What the operator does after each beat (shown under the beat in the dashboard). */
        val THEN = listOf(
            "The phones buzz in about 15 s: \"not known yet\" (the safety net).",
            "Draft from the ticker (AI), check it, Publish.",
            "Draft from the ticker (AI): buses and travel advice. Publish.",
            "Draft from the ticker (AI): the line is open again. Publish as Resolved.",
        )

        private val TIME = Regex("""\b([01]?\d|2[0-3])([h:])([0-5]\d)\b""")

        /** `replay.py`'s `shift_times` at speed 1: a clock time keeps its distance from the incident's start. */
        fun shiftTimes(text: String, t0: LocalDateTime, wall0: OffsetDateTime): String = TIME.replace(text) { m ->
            val t = t0.withHour(m.groupValues[1].toInt()).withMinute(m.groupValues[3].toInt()).withSecond(0).withNano(0)
            val delta = ChronoUnit.SECONDS.between(t0, t)
            if (delta !in -3600..36000) m.value
            else wall0.plusSeconds(delta).let { w -> "%02d%s%02d".format(w.hour, m.groupValues[2], w.minute) }
        }
    }
}
