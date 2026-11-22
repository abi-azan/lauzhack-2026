package railstatus

import railstatus.model.*
import railstatus.nextmove.*
import kotlin.test.*

class NextMoveTest {
    private val gva = Station("8501008", "Genève")
    private val nyon = Station("8501030", "Nyon")
    private val allaman = Station("8501035", "Allaman")
    private val morges = Station("8501037", "Morges")
    private val renens = Station("8501118", "Renens VD")
    private val lausanne = Station("8501120", "Lausanne")
    private val sion = Station("8501506", "Sion")
    private val bern = Station("8507000", "Bern")
    private val thun = Station("8507100", "Thun")

    private fun stop(s: Station, arr: String?, dep: String?) = Stop(s, arr, dep)

    /**
     * The judges' train, as in the server fixtures. The real IR 90 doesn't stop at Allaman; [allamanStop]
     * adds a stop there (like a held train's extra stop, or an RE 33) to test the detour.
     */
    private fun ir90(shift: Int = 0, allamanStop: Boolean = true) = Train(
        "IR90-1734", "IR", "IR 90", "1734", "Brig",
        listOfNotNull(
            stop(gva, "17:10", "17:13"), stop(nyon, "17:27", "17:28"),
            if (allamanStop) stop(allaman, "17:33", "17:34") else null,
            stop(morges, "17:41", "17:42"), stop(lausanne, "17:51", "17:54"), stop(sion, "18:48", "18:50"),
        ).map { it.copy(arr = it.arr?.let { t -> sh(t, shift) }, dep = it.dep?.let { t -> sh(t, shift) }) },
    )

    private fun corridor(shift: Int = 0) = Corridor(
        order = listOf(gva.id, nyon.id, allaman.id, morges.id, renens.id, lausanne.id),
        westEdge = allaman.id, eastEdge = renens.id,
        eastbound = listOf(
            Detour(
                id = "bus-724-701", title = mapOf(Lang.EN to "Regional buses via Morges"),
                leaveAt = allaman, rejoinAt = lausanne,
                departures = listOf("18:16", "19:16", "20:16", "21:16", "22:16").map { sh(it, shift) },
                rideMin = 87,
                legs = listOf(
                    MoveLeg("walk", null, "Allaman", "Allaman, gare"),
                    MoveLeg("bus", "724", "Allaman, gare", "Morges, gare"),
                    MoveLeg("bus", "701", "Morges, gare", "Lausanne, Bourdonnette"),
                    MoveLeg("metro", "m1", "Lausanne, Bourdonnette", "Lausanne"),
                ),
            ),
        ),
    )

    private fun facts(
        stage: Stage = Stage.INVESTIGATING, delay: DelayRange? = null, alternatives: List<Alternative> = emptyList(),
    ) = IncidentFacts(
        id = "inc-1", version = 1, stage = stage, trainIds = listOf("IR90-1734"),
        delayMin = delay, alternatives = alternatives, nextUpdateBy = "2026-09-17T17:50:00+02:00",
    )

    private fun checkIn(dest: String? = null, boarding: String? = null) =
        CheckIn("p-1", "tok", "IR90-1734", destinationId = dest, at = "2026-09-17T17:20:00+02:00", boardingId = boarding)

    private fun sh(t: String, d: Int): String {
        val (h, m) = t.split(":").map(String::toInt)
        val x = (((h * 60 + m + d) % 1440) + 1440) % 1440
        return "${(x / 60).toString().padStart(2, '0')}:${(x % 60).toString().padStart(2, '0')}"
    }

    @Test
    fun noEstimateYetMeansWaitWithABackupAndADeadline() {
        val nm = nextMove(facts(), ir90(), checkIn(), "17:35", corridor())!!
        assertEquals(MoveDecision.WAIT_FOR_INFO, nm.decision)
        assertEquals("Lausanne", nm.destination.name)
        assertTrue(nm.destinationAssumed)
        val bus = nm.options.first { it.source == MoveSource.TIMETABLE }
        assertEquals("18:16", bus.departAt)
        assertEquals("19:43", bus.arriveLatest)
        assertEquals("18:11", nm.decideBy)
        assertNull(nm.options.first { it.source == MoveSource.STAY }.arriveEarliest)
        // The next-update time stays a field for the page; the card's text doesn't repeat it (T18 F5).
        assertEquals("2026-09-17T17:50:00+02:00", nm.nextUpdateBy)
        assertFalse(nm.why[Lang.EN]!!.contains("Next update"))
    }

    @Test
    fun shortDelayMeansStay() {
        val nm = nextMove(facts(delay = DelayRange(25, 35)), ir90(), checkIn(), "17:35", corridor())!!
        assertEquals(MoveDecision.STAY, nm.decision)
        val stay = nm.options.first()
        assertEquals(MoveSource.STAY, stay.source)
        assertEquals("18:16", stay.arriveEarliest)
        assertEquals("18:26", stay.arriveLatest)
    }

    @Test
    fun longDelayMeansTakeTheBus() {
        val nm = nextMove(facts(delay = DelayRange(180, 240)), ir90(), checkIn(), "17:35", corridor())!!
        assertEquals(MoveDecision.GO, nm.decision)
        assertEquals("bus-724-701", nm.options.first().id)
        assertEquals("18:11", nm.decideBy)
        assertEquals("Change at Allaman", nm.headline[Lang.EN])
    }

    @Test
    fun overlappingRangesPreferStaying() {
        // Stay 19:31–19:51 vs bus 19:43: not certainly earlier, so stay (fewer changes).
        val nm = nextMove(facts(delay = DelayRange(100, 120)), ir90(), checkIn(), "17:35", corridor())!!
        assertEquals(MoveDecision.STAY, nm.decision)
    }

    @Test
    fun sbbAlternativesComeFirst() {
        val alt = Alternative("a1", mapOf(Lang.EN to "Replacement buses Allaman – Renens VD from 19:30"))
        val nm = nextMove(facts(Stage.MONITORING, alternatives = listOf(alt)), ir90(), checkIn(), "19:00", corridor())!!
        assertEquals(MoveDecision.GO, nm.decision)
        assertEquals(MoveSource.SBB, nm.options.first().source)
        assertTrue(nm.options.any { it.source == MoveSource.STAY })
        // SBB's text is on the page already; the card points to it and adds our route, without our deadline (T18 #3).
        assertTrue(nm.why[Lang.EN]!!.startsWith("SBB has published other ways to travel (see Information)."), nm.why[Lang.EN])
        assertFalse(nm.why[Lang.EN]!!.contains("Replacement buses"))
        assertNull(nm.decideBy)
    }

    @Test
    fun onwardDestinationGetsARangeForTheConnection() {
        val nm = nextMove(facts(delay = DelayRange(180, 240)), ir90(), checkIn(sion.id), "17:35", corridor())!!
        assertEquals("Sion", nm.destination.name)
        assertFalse(nm.destinationAssumed)
        val bus = nm.options.first { it.source == MoveSource.TIMETABLE }
        assertEquals("20:37", bus.arriveEarliest) // 19:43 at Lausanne + 54 min Lausanne → Sion
        assertEquals("21:07", bus.arriveLatest)   // + up to 30 min wait for the onward train
        assertEquals(MoveDecision.GO, nm.decision) // staying: 21:48 at the earliest
    }

    @Test
    fun noDetourFromAStationTheTrainDoesNotStopAt() {
        // The real IR 90 runs through Allaman: never "change to the bus at Allaman", whatever the delay.
        val nm = nextMove(facts(delay = DelayRange(180, 240)), ir90(allamanStop = false), checkIn(), "17:35", corridor())!!
        assertTrue(nm.options.none { it.source == MoveSource.TIMETABLE })
        assertEquals(MoveDecision.STAY, nm.decision)
        val early = nextMove(facts(), ir90(allamanStop = false), checkIn(), "17:35", corridor())!!
        assertEquals(MoveDecision.WAIT_FOR_INFO, early.decision)
        assertNull(early.decideBy)
    }

    @Test
    fun waitingPastTheClosureIgnoresSbbsWayAroundIt() {
        // Phone 3: on the platform at Lausanne, going to Sion. SBB's buses Allaman – Renens aren't for them (T18 #2).
        val alt = Alternative("a1", mapOf(Lang.EN to "Replacement buses Allaman – Renens VD from 19:30"))
        val ci = CheckIn("p-1", "tok", "IR90-1734", destinationId = sion.id, boardingId = lausanne.id, at = "2026-09-17T17:20:00+02:00")
        val nm = nextMove(facts(Stage.MONITORING, alternatives = listOf(alt)), ir90(), ci, "19:00", corridor())!!
        assertEquals(MoveDecision.WAIT_FOR_INFO, nm.decision)
        assertEquals("Wait at the station for now", nm.headline[Lang.EN])
        assertTrue(nm.why[Lang.EN]!!.contains("for the closed section"), nm.why[Lang.EN])
        assertTrue(nm.options.none { it.source == MoveSource.SBB })
    }

    @Test
    fun passengerGettingOffBeforeTheClosureIsNotAffected() {
        assertNull(nextMove(facts(), ir90(), checkIn(nyon.id), "17:20", corridor()))
    }

    @Test
    fun trainOffTheCorridorGetsNothing() {
        val t = Train("IC8-1234", "IC", "IC 8", "1234", "Brig", listOf(stop(bern, null, "17:34"), stop(thun, "17:52", null)))
        assertNull(nextMove(facts(), t, checkIn(), "17:35", corridor()))
    }

    @Test
    fun resolvedMeansStay() {
        val nm = nextMove(facts(Stage.RESOLVED, delay = DelayRange(180, 240)), ir90(), checkIn(), "21:10", corridor())!!
        assertEquals(MoveDecision.STAY, nm.decision)
        assertEquals(1, nm.options.size)
        assertNull(nm.options.first().arriveEarliest) // the old delay is stale once resolved
    }

    // ---- on the platform, following a departure (boardingId set) ----------------------------------------

    @Test
    fun onThePlatformNoEstimateMeansWaitAtTheStation() {
        val nm = nextMove(facts(), ir90(), checkIn(boarding = gva.id), "17:05", corridor())!!
        assertEquals(MoveDecision.WAIT_FOR_INFO, nm.decision)
        assertEquals("Wait at the station for now", nm.headline[Lang.EN])
        assertEquals("Take this train", nm.options.first { it.source == MoveSource.STAY }.title[Lang.EN])
        assertEquals("Lausanne", nm.destination.name)
    }

    @Test
    fun onThePlatformShortDelayMeansTakeYourTrain() {
        val nm = nextMove(facts(delay = DelayRange(25, 35)), ir90(), checkIn(boarding = gva.id), "17:05", corridor())!!
        assertEquals(MoveDecision.STAY, nm.decision)
        assertEquals("Take your train as planned", nm.headline[Lang.EN])
        assertEquals("18:16", nm.options.first().arriveEarliest)
    }

    @Test
    fun onThePlatformAtTheDetourStationTakeTheBusInstead() {
        val nm = nextMove(facts(delay = DelayRange(180, 240)), ir90(), checkIn(boarding = allaman.id), "17:35", corridor())!!
        assertEquals(MoveDecision.GO, nm.decision)
        assertEquals("bus-724-701", nm.options.first().id)
        assertEquals("Take the bus 724 from Allaman instead", nm.headline[Lang.EN])
    }

    @Test
    fun onThePlatformNoDetourFromAStationAlreadyBehindYou() {
        // Boarding at Morges, past Allaman: the bus from Allaman isn't reachable.
        val nm = nextMove(facts(delay = DelayRange(180, 240)), ir90(), checkIn(boarding = morges.id), "17:35", corridor())!!
        assertTrue(nm.options.none { it.source == MoveSource.TIMETABLE })
    }

    @Test
    fun onThePlatformTheAssumedDestinationIsAfterTheBoardingStop() {
        // Boarding at Lausanne (where rail resumes): the assumed destination is the next stop, not Lausanne.
        val nm = nextMove(facts(), ir90(), checkIn(boarding = lausanne.id), "17:45", corridor())!!
        assertEquals("Sion", nm.destination.name)
    }

    @Test
    fun onThePlatformADestinationBeforeBoardingIsIgnored() {
        val nm = nextMove(facts(), ir90(), checkIn(dest = nyon.id, boarding = morges.id), "17:35", corridor())!!
        assertTrue(nm.destinationAssumed)
    }

    @Test
    fun everyTextHasItalian() {
        for (f in listOf(facts(), facts(delay = DelayRange(25, 35)), facts(delay = DelayRange(180, 240)), facts(Stage.RESOLVED))) {
            val nm = nextMove(f, ir90(), checkIn(), "17:35", corridor())!!
            assertNotEquals(nm.headline[Lang.EN], nm.headline[Lang.IT])
            assertNotEquals(nm.why[Lang.EN], nm.why[Lang.IT])
        }
    }

    @Test
    fun worksAcrossMidnight() {
        val d = 7 * 60 // the demo runs at 00:35 with the trains anchored to it
        val nm = nextMove(facts(delay = DelayRange(180, 240)), ir90(d), checkIn(), sh("17:35", d), corridor(d))!!
        assertEquals(MoveDecision.GO, nm.decision)
        assertEquals(sh("18:16", d), nm.options.first().departAt)
        assertEquals(sh("19:43", d), nm.options.first().arriveLatest)
    }
}
