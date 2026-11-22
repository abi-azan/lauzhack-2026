package railstatus.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.*

class DemoControlTest {
    private class RecordingBus : Bus {
        val sent = mutableListOf<String>()
        override suspend fun publish(stream: String, json: String) { synchronized(sent) { sent += json } }
        override fun subscribe(scope: CoroutineScope, stream: String, group: String, handler: suspend (String) -> Unit) = Job()
        override suspend fun reset() {}
    }

    @Test
    fun shiftsClockTimesOntoTheDemoClock() {
        val t0 = LocalDateTime.of(2026, 9, 17, 17, 22)
        val wall0 = OffsetDateTime.of(2026, 11, 22, 11, 0, 0, 0, ZoneOffset.ofHours(1))
        assertEquals("COLGEN dès 11h08, jusqu'à 12:08", DemoControl.shiftTimes("COLGEN dès 17h30, jusqu'à 18:30", t0, wall0))
        assertEquals("train 06:15", DemoControl.shiftTimes("train 06:15", t0, wall0), "far from the incident: unchanged")
    }

    @Test
    fun beatsGoStrictlyInOrderAndResetStartsOver() = runBlocking {
        val bus = RecordingBus()
        val demo = DemoControl(bus, this, gapMs = 0)
        if (demo.status().total == 0) return@runBlocking // a public copy, without SBB's ticker data
        assertEquals(4, demo.status().total)
        assertEquals(1, demo.send(2).next, "skipping ahead does nothing")
        assertEquals(2, demo.send(1).next)
        assertEquals(2, demo.send(1).next, "a double click does nothing")
        while (demo.status().sending) kotlinx.coroutines.delay(10)
        assertEquals(2, bus.sent.size, "beat 1 has two messages")
        assertTrue("\"kind\":\"created\"" in bus.sent[0])
        demo.reset()
        assertEquals(1, demo.status().next)
    }
}
