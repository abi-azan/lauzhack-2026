@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package railstatus.mobile.incident

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import railstatus.model.CheckInMethod
import railstatus.model.DelayRange
import railstatus.model.Hello
import railstatus.model.IncidentFacts
import railstatus.model.IncidentUpdate
import railstatus.model.IncidentUpdated
import railstatus.model.Lang
import railstatus.model.Reset
import railstatus.model.Stage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal const val T0 = 1_789_658_160_000L // 2026-09-17T15:16:00Z, 17:16 in Zurich

internal val zurich = TimeZone.of("Europe/Zurich")

internal fun TestScope.testClock(): () -> Instant = { Instant.fromEpochMilliseconds(T0 + testScheduler.currentTime) }

internal fun TestScope.passengerStore(
    backend: PassengerBackend = FakeBackend(),
    notifier: Notifier = FakeNotifier(),
    storage: KeyValueStore = MemoryKeyValueStore(),
    config: PassengerConfig = PassengerConfig(),
    scanner: BeaconScanner = FakeBeaconScanner(),
) = PassengerStore(backgroundScope, backend, notifier, storage, scanner, config, testClock(), zurich)

internal fun facts(vararg stages: Stage, delay: DelayRange? = null) = IncidentFacts(
    id = "inc-1", version = stages.size, stage = stages.last(), trainIds = listOf("IR90-1734"), delayMin = delay,
    updates = stages.mapIndexed { i, s ->
        IncidentUpdate("u${i + 1}", "inc-1", i + 1, s, text("T${i + 1}", "T", "T", "T"), text("B${i + 1}", "B", "B", "B"), "2026-09-17T15:1${i}:00Z")
    },
)

class PassengerStoreTest {
    @Test
    fun fakeModeBeaconSearchCheckInAndScriptedUpdates() = runTest {
        val s = passengerStore()
        assertEquals(CheckInPhase.IDLE, s.state.value.phase)
        assertFalse(s.isLive)

        s.findTrain()
        runCurrent()
        assertEquals(CheckInPhase.SEARCHING, s.state.value.phase)
        advanceTimeBy(1799); runCurrent()
        assertEquals(CheckInPhase.SEARCHING, s.state.value.phase)
        advanceTimeBy(1); runCurrent()
        with(s.state.value) {
            assertEquals(CheckInPhase.FOUND, phase)
            assertEquals(CheckInVia.BEACON, via)
            assertEquals("IR90-1734", trainId)
            assertEquals("7", car)
        }

        s.checkIn(); runCurrent()
        assertEquals(CheckInPhase.CHECKED_IN, s.state.value.phase)
        assertEquals(T0 + 1800, s.state.value.checkedInAt)
        assertEquals(fakeTrain(Instant.fromEpochMilliseconds(T0 + 1800)), s.train.value)
        assertNull(s.incident.value)

        // The demo controls send the five scripted updates, then nothing.
        repeat(6) { s.publishNext(); advanceTimeBy(60_000) }
        runCurrent()
        val inc = assertNotNull(s.incident.value)
        assertEquals(5, inc.updates.size)
        assertEquals(Stage.RESOLVED, inc.stage)
        assertEquals("renens-allaman-${T0 + 1800}", inc.id)
        assertEquals(addMin(inc.updates[0].at, 10), inc.updates[0].nextBy)
        assertNull(inc.latest.nextBy)
        assertNull(s.nextScripted())

        s.restartDemo(); runCurrent()
        assertEquals(PassengerState(trainId = "IR90-1734"), s.state.value) // the train id stays, as on the web
        assertNull(s.incident.value)
    }

    @Test
    fun aScannerThatFindsNothingStopsTheSearch() = runTest {
        val s = passengerStore(scanner = { null })
        s.findTrain(); runCurrent()
        assertEquals(CheckInPhase.IDLE, s.state.value.phase)
        assertEquals(NO_TRAIN_FOUND, s.state.value.error)
    }

    @Test
    fun qrLinkAndCheckOut() = runTest {
        assertEquals(QrCheckIn("IC1-725", "3"), parseCheckInLink("https://x.trycloudflare.com/#/checkin/IC1-725?car=3"))
        assertEquals(QrCheckIn("IR90-1734", null), parseCheckInLink("#/checkin/IR90-1734"))
        assertEquals(QrCheckIn(null, null), parseCheckInLink("#/checkin"))
        assertNull(parseCheckInLink("https://x/#/plan"))

        val s = passengerStore()
        s.foundByQr(null, null)
        with(s.state.value) {
            assertEquals(CheckInVia.QR, via); assertEquals("IR90-1734", trainId); assertEquals("7", car)
        }
        s.foundByQr("IC1-725", "3")
        assertEquals("IC1-725", s.state.value.trainId)
        s.checkIn(); runCurrent()
        assertEquals(CheckInPhase.CHECKED_IN, s.state.value.phase)
        s.setDest("Sion")
        s.checkOut(); runCurrent()
        with(s.state.value) {
            assertEquals(CheckInPhase.IDLE, phase); assertNull(via); assertNull(car); assertNull(checkedInAt)
            assertEquals("Sion", dest) // check-out keeps the destination, like the web
        }
    }

    @Test
    fun aCheckOutWhileACheckInIsInFlightWins() = runTest {
        val backend = FakeBackend().apply { checkInDelayMs = 1_000 }
        val s = passengerStore(backend, config = PassengerConfig(api = "https://rail.example"))
        s.quickCheckIn(); runCurrent()
        s.checkOut()
        advanceTimeBy(2_000); runCurrent() // the store runs in backgroundScope, which advanceUntilIdle doesn't wait for
        assertEquals(1, backend.checkIns.size)
        assertEquals(CheckInPhase.IDLE, s.state.value.phase)
        assertNull(s.state.value.live)
        assertEquals(listOf("t1"), backend.deleted) // the server forgets the check-in that came back too late
    }

    @Test
    fun personasAndDestination() = runTest {
        val s = passengerStore()
        assertEquals(Person(PERSONAS.getValue(PersonaId.SUPERSAVER), "Lausanne"), s.currentPerson())
        s.setPersona(PersonaId.GUEST); s.setDest("Sion"); runCurrent()
        assertEquals("Sion", s.person.value.dest)
        s.nextPersona(); runCurrent() // guest → supersaver, and the asked destination is forgotten
        assertEquals(PersonaId.SUPERSAVER, s.persona)
        assertNull(s.state.value.dest)
        assertEquals(listOf(PersonaId.TICKET, PersonaId.GA, PersonaId.GUEST, PersonaId.SUPERSAVER),
            List(4) { s.nextPersona(); s.persona })
    }

    @Test
    fun stateSurvivesARestart() = runTest {
        val storage = MemoryKeyValueStore()
        val a = passengerStore(storage = storage)
        a.quickCheckIn(); runCurrent()
        a.publishNext(); a.setDest("Sion"); a.setLang(Lang.DE)
        val b = passengerStore(storage = storage)
        with(b.state.value) {
            assertEquals(CheckInPhase.CHECKED_IN, phase)
            assertEquals(1, sent.size)
            assertEquals("Sion", dest)
            assertEquals(Lang.EN, lang) // the app is English-only for now
        }
        // A preset persona replaces the stored one and forgets the asked destination.
        val c = passengerStore(storage = storage, config = PassengerConfig(persona = PersonaId.GA))
        assertEquals(PersonaId.GA, c.persona)
        assertNull(c.state.value.dest)
        // A search in progress doesn't survive; garbage doesn't break the start.
        storage.put("sbb-passenger-demo-v1", """{"phase":"searching","persona":"nobody","conn":"open"}""")
        with(passengerStore(storage = storage).state.value) {
            assertEquals(CheckInPhase.IDLE, phase); assertEquals(PersonaId.SUPERSAVER, persona); assertEquals(Conn.OFF, conn)
        }
        storage.put("sbb-passenger-demo-v1", "{not json")
        assertEquals(PassengerState(), passengerStore(storage = storage).state.value)
    }

    @Test
    fun liveCheckInFollowsTheSocket() = runTest {
        val backend = FakeBackend()
        val storage = MemoryKeyValueStore()
        val notifier = FakeNotifier(permitted = true)
        val s = passengerStore(backend, notifier, storage, PassengerConfig(api = "https://rail.example//"))
        assertTrue(s.isLive)
        assertEquals("https://rail.example", s.state.value.api)
        assertEquals("https://rail.example", storage.get("sbb-passenger-api"))

        s.quickCheckIn(); runCurrent()
        val (api, req) = backend.checkIns.single()
        assertEquals("https://rail.example", api)
        assertEquals("IR90-1734", req.trainId)
        assertEquals(7, req.car)
        assertEquals(CheckInMethod.BLE, req.method)
        assertEquals("8501120", req.destinationId) // the supersaver's Lausanne, looked up on the train
        with(s.state.value) {
            assertEquals(CheckInPhase.CHECKED_IN, phase)
            assertEquals("t1", live?.token)
            assertEquals(Conn.CONNECTING, conn)
            assertEquals(PushState.ON, push)
        }
        assertEquals("90", s.train.value.line)
        assertTrue(s.train.value.live)
        assertNull(s.incident.value)

        val sock = backend.openSockets.single()
        sock.on.state(Conn.OPEN)
        assertEquals(Conn.OPEN, s.state.value.conn)

        // hello, then a published update.
        sock.on.event(Hello("now", backend.postCheckInFor(), backend.train, facts(Stage.DETECTED)))
        runCurrent()
        assertNull(s.incident.value) // detected isn't public
        sock.on.event(IncidentUpdated("now", facts(Stage.INVESTIGATING, Stage.IDENTIFIED, delay = DelayRange(5, 10)), facts(Stage.INVESTIGATING).updates[0]))
        runCurrent()
        with(assertNotNull(s.incident.value)) {
            assertEquals(Stage.IDENTIFIED, stage)
            assertEquals(DelayRange(5, 10), latest.delay)
            assertNull(updates[0].delay)
        }
        // Fake-mode controls do nothing live.
        s.publishNext()
        assertTrue(s.state.value.sent.isEmpty())

        // A demo reset: check in again, same train and car, no tap.
        sock.on.event(Reset("now")); runCurrent()
        assertTrue(sock.closed)
        assertEquals(2, backend.checkIns.size)
        assertEquals("t2", s.state.value.live?.token)
        assertEquals("t2", backend.openSockets.single().token)

        // The backend forgot the token (close 1008): the same.
        backend.openSockets.single().on.rejected(); runCurrent()
        assertEquals("t3", s.state.value.live?.token)

        s.checkOut(); runCurrent()
        assertEquals(listOf("t1", "t2", "t3"), backend.deleted) // each re-check-in drops the token it replaces
        assertTrue(backend.openSockets.isEmpty())
        with(s.state.value) { assertNull(live); assertEquals(Conn.OFF, conn); assertNull(push) }
    }

    @Test
    fun liveCheckInFailsThenRetriesAfterAReset() = runTest {
        val backend = FakeBackend()
        val s = passengerStore(backend, config = PassengerConfig(api = "https://rail.example", persona = PersonaId.GA))
        backend.reachable = false
        s.quickCheckIn(); runCurrent()
        with(s.state.value) {
            assertEquals(CheckInPhase.FOUND, phase)
            assertEquals(CHECK_IN_ERROR, error)
            assertEquals(Conn.OFF, conn)
        }
        backend.reachable = true
        s.checkIn(); runCurrent()
        assertEquals(CheckInPhase.CHECKED_IN, s.state.value.phase)
        assertNull(backend.checkIns.single().second.destinationId) // a GA has no destination

        // Reset while the backend is down: keep showing checked in, retry every 5 s.
        backend.reachable = false
        backend.openSockets.single().on.event(Reset("now")); runCurrent()
        with(s.state.value) { assertEquals(CheckInPhase.CHECKED_IN, phase); assertEquals(Conn.RETRYING, conn); assertNull(live) }
        advanceTimeBy(5001); runCurrent()
        assertEquals(Conn.RETRYING, s.state.value.conn)
        backend.reachable = true
        advanceTimeBy(5000); runCurrent()
        assertEquals("t2", s.state.value.live?.token)
        assertEquals(1, backend.openSockets.size)
    }

    @Test
    fun startUpReconnectsOrChecksInAgain() = runTest {
        val backend = FakeBackend()
        val storage = MemoryKeyValueStore()
        val a = passengerStore(backend, storage = storage, config = PassengerConfig(api = "https://rail.example"))
        a.quickCheckIn(); runCurrent()
        a.close()
        // Restarted with the check-in: reconnect with the same token.
        passengerStore(backend, storage = storage)
        runCurrent()
        assertEquals("t1", backend.openSockets.single().token)
        assertEquals(1, backend.checkIns.size)
    }

    @Test
    fun apiOffForgetsTheBackendAndADefaultIsProbed() = runTest {
        val backend = FakeBackend()
        val storage = MemoryKeyValueStore()
        storage.put("sbb-passenger-api", "https://old.example")
        assertEquals("https://old.example", passengerStore(backend, storage = storage).state.value.api)

        val off = passengerStore(backend, storage = storage, config = PassengerConfig(api = "off", defaultApi = "https://default.example"))
        runCurrent()
        assertNull(off.state.value.api)
        assertNull(storage.get("sbb-passenger-api"))
        assertTrue(backend.probes.isEmpty()) // "off" means fake, no probing

        val probed = passengerStore(backend, storage = MemoryKeyValueStore(), config = PassengerConfig(defaultApi = "https://default.example/"))
        runCurrent()
        assertEquals(listOf("https://default.example"), backend.probes)
        assertEquals("https://default.example", probed.state.value.api)

        backend.probeOk = false
        val fake = passengerStore(backend, storage = MemoryKeyValueStore(), config = PassengerConfig(defaultApi = "https://default.example"))
        runCurrent()
        assertNull(fake.state.value.api)
    }

    @Test
    fun switchingTheBackendWhileCheckedIn() = runTest {
        val backend = FakeBackend()
        val s = passengerStore(backend)
        s.quickCheckIn(); runCurrent()
        s.setApi("https://rail.example").join()
        runCurrent()
        assertEquals("t1", s.state.value.live?.token)
        s.setApi(null).join()
        runCurrent()
        assertEquals(listOf("t1"), backend.deleted)
        assertNull(s.state.value.api)
        assertEquals(CheckInPhase.CHECKED_IN, s.state.value.phase)
    }
}

private fun FakeBackend.postCheckInFor() = railstatus.model.CheckIn("p1", "t1", train.id, at = "now")
