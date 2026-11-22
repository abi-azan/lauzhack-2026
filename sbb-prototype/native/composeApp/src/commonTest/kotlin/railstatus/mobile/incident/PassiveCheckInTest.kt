@file:OptIn(ExperimentalCoroutinesApi::class)

package railstatus.mobile.incident

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import railstatus.model.CheckInMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PassiveCheckInTest {
    /** A scanner that counts its scans and finds [beacon] (null: nothing) after 1 s. */
    private class CountingScanner(var beacon: String? = "IR90-1734-Car3") : BeaconScanner {
        var scans = 0
        override suspend fun find(): String? { scans++; kotlinx.coroutines.delay(1000); return beacon }
    }

    private class Wiring(val passive: PassiveCheckIn, val store: PassengerStore, val scanner: CountingScanner, val storage: MemoryKeyValueStore)

    private fun TestScope.wire(
        // Opted out to start with, so each test opts in itself; [onByDefault] covers a fresh install.
        storage: MemoryKeyValueStore = MemoryKeyValueStore().apply { put("sbb-passive-checkin", "0") },
        backend: PassengerBackend? = null,
        scanner: CountingScanner = CountingScanner(),
        platform: PassivePlatform = PassivePlatform.Always,
    ): Wiring {
        val notifier = FakeNotifier()
        val passive = PassiveCheckIn(backgroundScope, storage, scanner, platform, notifier, intervalMs = 20_000)
        val store = if (backend == null) passengerStore(storage = storage, scanner = passive.scanner)
        else passengerStore(backend = backend, storage = storage, scanner = passive.scanner, config = PassengerConfig(api = "http://api"))
        passive.attach(store)
        return Wiring(passive, store, scanner, storage)
    }

    @Test
    fun onByDefault() = runTest {
        // A fresh install (nothing stored) watches for beacons straight away: the demo phones need no setup.
        val w = wire(storage = MemoryKeyValueStore())
        assertTrue(w.passive.state.value.optedIn)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(w.scanner.scans >= 1)
    }

    @Test
    fun nothingHappensAfterAnOptOut() = runTest {
        val w = wire()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, w.scanner.scans)
        assertEquals(PassiveStatus.OFF, w.passive.state.value.status)
        assertEquals(CheckInPhase.IDLE, w.store.state.value.phase)
    }

    @Test
    fun optInFindsTheBeaconAndChecksInWithOneScan() = runTest {
        val backend = FakeBackend()
        val w = wire(backend = backend)
        assertTrue(w.passive.optIn())
        advanceTimeBy(1500); runCurrent()
        with(w.store.state.value) {
            assertEquals(CheckInPhase.CHECKED_IN, phase)
            assertEquals(CheckInVia.BEACON, via)
            assertEquals("IR90-1734", trainId)
            assertEquals("3", car)
        }
        assertEquals(CheckInMethod.BLE, backend.checkIns.single().second.method)
        assertEquals(1, w.scanner.scans) // the store's search took the passive hit, no second scan
        assertEquals(PassiveStatus.CHECKED_IN, w.passive.state.value.status)
        assertEquals("IR90-1734-Car3", w.passive.state.value.lastBeacon)
        // Remembered.
        assertEquals("1", w.storage.get("sbb-passive-checkin"))
    }

    @Test
    fun aCheckOutPausesThatTrainUntilItsBeaconIsGone() = runTest {
        val w = wire()
        w.passive.optIn()
        advanceTimeBy(1500); runCurrent()
        assertEquals(CheckInPhase.CHECKED_IN, w.store.state.value.phase)

        w.store.checkOut()
        runCurrent()
        // At once, not at the next scan round.
        assertEquals(PassiveStatus.PAUSED, w.passive.state.value.status)
        advanceTimeBy(500); runCurrent()
        assertEquals(PassiveStatus.PAUSED, w.passive.state.value.status) // the beacon re-check runs quietly
        advanceTimeBy(60_000); runCurrent()
        assertEquals(CheckInPhase.IDLE, w.store.state.value.phase)
        assertEquals(PassiveStatus.PAUSED, w.passive.state.value.status)

        // Off the train: the beacon is gone once, then a train's beacon checks them in again.
        w.scanner.beacon = null
        advanceTimeBy(21_000); runCurrent()
        assertEquals(PassiveStatus.WATCHING, w.passive.state.value.status)
        w.scanner.beacon = "IR90-1734-Car5"
        advanceTimeBy(21_000); runCurrent()
        assertEquals(CheckInPhase.CHECKED_IN, w.store.state.value.phase)
        assertEquals("5", w.store.state.value.car)
    }

    @Test
    fun nothingFoundLeavesNoErrorAndScansAgainLater() = runTest {
        val w = wire(scanner = CountingScanner(beacon = null))
        w.passive.optIn()
        advanceTimeBy(1500); runCurrent()
        assertEquals(CheckInPhase.IDLE, w.store.state.value.phase)
        assertEquals(null, w.store.state.value.error)
        advanceTimeBy(21_000); runCurrent() // the second scan, then waiting again
        assertEquals(2, w.scanner.scans)
        w.passive.scanNow(); runCurrent() // cuts the wait short
        assertEquals(3, w.scanner.scans)
    }

    @Test
    fun refusedBluetoothMeansNoOptIn() = runTest {
        val refuse = object : PassivePlatform {
            override suspend fun requestScanPermission() = false
            override fun canScan() = false
        }
        val w = wire(platform = refuse)
        assertFalse(w.passive.optIn())
        assertFalse(w.passive.state.value.optedIn)
        assertEquals("0", w.storage.get("sbb-passive-checkin")) // still opted out
    }

    @Test
    fun theOptInSurvivesARestartAndOptOutForgetsIt() = runTest {
        val storage = MemoryKeyValueStore()
        wire(storage = storage).passive.optIn()
        val again = wire(storage = storage)
        assertTrue(again.passive.state.value.optedIn)
        advanceTimeBy(1500); runCurrent()
        assertEquals(CheckInPhase.CHECKED_IN, again.store.state.value.phase)
        again.passive.optOut()
        assertFalse(wire(storage = storage).passive.state.value.optedIn)
    }
}
