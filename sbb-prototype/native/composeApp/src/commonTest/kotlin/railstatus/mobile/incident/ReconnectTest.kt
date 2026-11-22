@file:OptIn(ExperimentalCoroutinesApi::class)

package railstatus.mobile.incident

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import railstatus.model.CheckInRequest
import railstatus.model.CheckInResponse
import railstatus.model.Hello
import railstatus.model.IncidentUpdated
import railstatus.model.Lang
import railstatus.model.Stage
import railstatus.model.Train
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [FakeBackend] whose calls take [latencyMs], like a real network: cancellation can hit them. */
private class SlowBackend(val fake: FakeBackend = FakeBackend(), val latencyMs: Long = 300) : PassengerBackend by fake {
    var attempts = 0
    override suspend fun getTrain(api: String, id: String): Train { delay(latencyMs); return fake.getTrain(api, id) }
    override suspend fun postCheckIn(api: String, body: CheckInRequest): CheckInResponse {
        attempts++
        delay(latencyMs)
        return fake.postCheckIn(api, body)
    }
    override fun openPassengerSocket(scope: CoroutineScope, api: String, token: String, on: PassengerSocketListener) =
        fake.openPassengerSocket(scope, api, token, on)
    override suspend fun postChat(api: String, token: String, text: String, lang: Lang) = fake.postChat(api, token, text, lang)
}

class ReconnectTest {
    private val live = PassengerConfig(api = "https://rail.example")

    @Test
    fun theRetryKeepsGoingWithBackoffUntilTheServerIsBack() = runTest {
        val backend = SlowBackend()
        val s = passengerStore(backend, config = live)
        s.quickCheckIn(); advanceTimeBy(1000); runCurrent()
        assertEquals("t1", s.state.value.live?.token)

        // The server restarts and forgot us (1008), and it's unreachable for a while.
        backend.fake.reachable = false
        backend.fake.openSockets.single().on.rejected()
        advanceTimeBy(1000); runCurrent()
        assertEquals(Conn.RETRYING, s.state.value.conn)
        assertEquals(CheckInPhase.CHECKED_IN, s.state.value.phase)
        val first = backend.attempts
        // Backoff 2 s, 4 s, 8 s, 15 s, 15 s (+ the calls' latency): the retries don't cancel themselves.
        advanceTimeBy(60_000); runCurrent()
        assertTrue(backend.attempts - first >= 5, "retries: ${backend.attempts - first}")
        assertEquals(Conn.RETRYING, s.state.value.conn)

        backend.fake.reachable = true
        advanceTimeBy(16_000); runCurrent()
        assertEquals("t2", s.state.value.live?.token)
        assertEquals(CheckInPhase.CHECKED_IN, s.state.value.phase)
        assertEquals(1, backend.fake.openSockets.size)
        assertTrue("t1" in backend.fake.deleted) // the forgotten token is cleaned up, best effort
    }

    @Test
    fun backoffSteps() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 15_000L, 15_000L), (0..4).map(::retryDelayMs))
    }

    @Test
    fun aDoubleTapChecksInOnceAndARecheckInDropsTheOldToken() = runTest {
        val backend = SlowBackend()
        val s = passengerStore(backend, config = live)
        s.quickCheckIn(); runCurrent()
        s.checkIn(); runCurrent() // the second tap, while the first is in flight
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, backend.fake.checkIns.size)
        assertEquals("t1", s.state.value.live?.token)

        // Checking in again (a new beacon hit) replaces the check-in: the old token goes.
        s.quickCheckIn(); advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("t1"), backend.fake.deleted)
        assertEquals("t2", s.state.value.live?.token)
        assertEquals(1, backend.fake.openSockets.size)
    }

    @Test
    fun aReloadOnTheSameOriginKeepsTheCheckIn() = runTest {
        val backend = FakeBackend()
        val storage = MemoryKeyValueStore()
        val web = PassengerConfig(defaultApi = "https://demo.example/")
        val first = passengerStore(backend, storage = storage, config = web)
        runCurrent() // the probe finds the origin
        first.quickCheckIn(); runCurrent()
        assertEquals("t1", first.state.value.live?.token)
        first.close()

        // The reload: no `?api=`, same origin. No new passenger, the same socket token.
        val again = passengerStore(backend, storage = storage, config = web)
        runCurrent()
        assertEquals("https://demo.example", again.state.value.api)
        assertEquals("t1", again.state.value.live?.token)
        assertEquals(1, backend.checkIns.size)
        assertEquals("t1", backend.openSockets.last().token)
        assertTrue(backend.deleted.isEmpty())

        // Served from another origin now: the old check-in is deleted on its server.
        val moved = passengerStore(backend, storage = storage, config = PassengerConfig(defaultApi = "https://other.example"))
        runCurrent()
        assertEquals(listOf("t1"), backend.deleted)
        assertEquals(2, backend.checkIns.size)
        assertEquals("https://other.example", moved.state.value.api)
    }

    @Test
    fun aStaleHelloDoesNotUndoANewerUpdate() = runTest {
        val backend = FakeBackend()
        val s = passengerStore(backend, config = live)
        s.quickCheckIn(); runCurrent()
        val sock = backend.openSockets.single()
        val newer = facts(Stage.INVESTIGATING, Stage.IDENTIFIED)
        sock.on.event(IncidentUpdated("now", newer, newer.updates.last())); runCurrent()
        val older = facts(Stage.INVESTIGATING)
        sock.on.event(Hello("now", railstatus.model.CheckIn("p1", "t1", "IR90-1734", at = "now"), backend.train, older)); runCurrent()
        assertEquals(2, s.state.value.live?.incident?.version)
        sock.on.event(IncidentUpdated("now", older, older.updates.last())); runCurrent()
        assertEquals(Stage.IDENTIFIED, s.incident.value?.stage)
    }
}
