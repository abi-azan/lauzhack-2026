@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package railstatus.mobile.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The inline logic of the web screens, the clock's pinning, and a few fixed points of the timetable. */
class ScreenDataTest {
    private val zurich = TimeZone.of("Europe/Zurich")
    private val savedZone = AppClock.zone
    private val savedWall = AppClock.wallClock

    /** 2026-11-21 19:35 in Zurich. */
    private val t1935 = Instant.fromEpochMilliseconds(1795286100000)

    @BeforeTest fun setUp() { AppClock.zone = zurich }

    @AfterTest fun tearDown() {
        AppClock.zone = savedZone
        AppClock.wallClock = savedWall
        AppClock.pin(null)
    }

    private fun conn(id: String) = getConnection(id)!!

    @Test
    fun lausanneBernEveningMatchesTheRealApp() {
        val list = findConnections(station("lausanne"), station("bern"), t1935)
        assertEquals(
            listOf("19:40 IR 15 Luzern", "20:17 IC 1 St. Gallen", "20:34 IC 5 Zürich HB", "20:40 IR 15 Luzern", "21:17 IC 1 Zürich HB", "21:34 IC 5 Zürich HB"),
            list.map { "${hhmm(it.dep)} ${it.legs[0].lineText} ${it.legs[0].direction}" },
        )
        val ir15 = list[0]
        assertEquals("2539", ir15.legs[0].number) // 2501 + 19 * 2
        assertEquals("1 h 16 min", durationLabel(ir15.durationMin))
        assertEquals("18.00", formatPrice(ir15.priceFrom!!))
        assertEquals(listOf("renens-allaman"), ir15.allDisruptions().map { it.id })
        assertEquals(
            "Limited train service between Renens VD and Allaman.\nThis is due to construction work.\n" +
                "The restriction lasts from 21.11.2026, 06:00 until 22.11.2026, 23:59.\nFor your connection, please check the online timetable.",
            ir15.allDisruptions()[0].body,
        )
        // IR 15 at an even hour: platform 5 instead of 1, and the platform note goes first.
        val ir15Even = list[3]
        assertEquals("5", ir15Even.boardingStop.platform)
        assertTrue(ir15Even.boardingStop.platformChanged)
        assertEquals(listOf(DisruptionKind.PLATFORM_CHANGE, DisruptionKind.CONSTRUCTION), ir15Even.legs[0].disruptionKinds())
        assertEquals("renens-allaman", ir15Even.legs[0].banner()?.id)
        // IC 5 at 21:34 (hour divisible by 3): +3 on every stop of the first leg.
        val ic5 = list[5]
        assertEquals(listOf(3, 3, 3), ic5.legs[0].stops.map { it.delay })
        assertEquals("Delay of approx. 3 minutes", ic5.legs[0].disruptions.last().summary)
        assertEquals(listOf(null, null, null, null), ic5.legs[1].stops.map { it.delay })
        assertEquals("ca +3'", approxDelayLabel(ic5.boardingStop.delay!!))
        assertEquals(11, ic5.transfers()[0].minutes)
    }

    @Test
    fun noGeneratedLegHasANegativePlatformOrNumberOrMissingLine() {
        // The web app once derived seeds with a signed `>>`, which gave platforms like "-9", negative train numbers
        // and legs without a line. Check every leg of every pair at two times, and every departure board.
        val times = listOf(t1935, Instant.fromEpochMilliseconds(1795330680000)) // 21.11. 19:35, 22.11. 07:58
        var legs = 0
        for (a in STATIONS) for (b in STATIONS) for (t in times) {
            if (a.id == b.id) continue
            for (c in findConnections(a, b, t)) for (l in c.legs) {
                legs++
                val what = "${l.id}: line ${l.line}, number ${l.number}, platforms ${l.stops.map { it.platform }}"
                assertTrue(!l.line.isNullOrEmpty() && l.lineText != null, what)
                assertTrue(l.number!!.toInt() > 0, what)
                assertTrue(l.stops.all { !it.platform.isNullOrEmpty() && !it.platform!!.startsWith("-") }, what)
            }
        }
        assertTrue(legs > 20_000, "checked $legs legs")
        for (s in STATIONS) for (t in times) for (r in departures(s, t, 50)) {
            assertTrue(!r.platform.startsWith("-") && r.line.isNotEmpty(), "board ${s.id}: $r")
        }
        // Two connections that showed it on the web before the fix (values from the fixed TS).
        assertEquals("7", getConnection("bern>lausanne#g0@29393260")!!.legs[0].lastStop.platform)
        assertEquals("IC 21", getConnection("basel>lugano#g1@29393300")!!.legs[1].lineText)
    }

    @Test
    fun productsAndBadges() {
        assertEquals(listOf("IC", "IR", "RE", "S", "SN", "PE", "EC", "ICE"), Product.entries.filter { it.hasLogo }.map { it.code })
        assertEquals(BadgeTone.RED, Product.IC.badgeTone)
        assertEquals(BadgeTone.RE, Product.RE.badgeTone)
        assertEquals(BadgeTone.S, Product.S.badgeTone)
        assertEquals(BadgeTone.SN, Product.SN.badgeTone)
        assertEquals("M m2", lineText(Product.M, "m2"))
        assertEquals(Product.IR, Product.fromCode("IR"))
        assertNull(Product.fromCode("ir"))
        assertEquals(Mode.METRO, station("epfl").searchMode)
        assertEquals(Mode.TRAIN, station("bern").searchMode)
    }

    @Test
    fun connectionListSearchesFromTheMinute() {
        val at = Instant.fromEpochMilliseconds(t1935.toEpochMilliseconds() + 59_000)
        val st = AppState(fromId = "lausanne", toId = "bern")
        assertEquals(findConnections(station("lausanne"), station("bern"), t1935, 7), connectionsFor(st, at))
        assertEquals(7, connectionsFor(st, at).size)
        assertEquals(emptyList(), connectionsFor(st.copy(toId = null), at))
        assertEquals(emptyList(), connectionsFor(st.copy(toId = "nowhere"), at))
        assertEquals(at, searchTime(st, at))
        assertEquals(t1935, searchTime(st.copy(searchAt = t1935), at))
        assertEquals("nyon", boardStation(AppState(fromId = "nyon")).id)
        assertEquals("lausanne", boardStation(AppState(fromId = null)).id)
    }

    @Test
    fun companionHelpers() {
        val c = conn("lausanne>bern#ic5@29393394")
        val cards = c.stepCards(c.dep)
        assertEquals(listOf(0, 1, 1), cards.map { cardLeg(it, c) })
        assertEquals(listOf("lausanne", "yverdon", "neuchatel", "ins", "kerzers", "bern"), c.mapStations().map { it.id })
        assertEquals(listOf(3, 4), c.mapLines().map { it.coords.size })
        assertEquals(listOf(station("neuchatel").lonLat), c.cameraFocus(cards[1]))
        assertEquals(c.mapLines()[1].coords, c.cameraFocus(cards[2]))
        assertNull(c.trainMarker(c.dep))
        assertEquals(c.trainPosition(addMin(c.dep, 10)), c.trainMarker(addMin(c.dep, 10)))
        // Travel time on the arrival card: what is left, or the whole trip once it is 0.
        val arrival = c.stepCards(c.arr).last() as StepCard.Arrival
        assertEquals(0, arrival.remainingMin)
        assertEquals(c.durationMin, arrival.travelTimeMin(c))
        assertEquals(listOf(Attr.WR, Attr.FZ, Attr.BZ, Attr.R, Attr.VO, Attr.NF, Attr.KW), legendAttrs(c.legs))
        assertEquals(
            listOf(SectorSpan("F", 1), SectorSpan("E", 2), SectorSpan("D", 2), SectorSpan("C", 1), SectorSpan("B", 1), SectorSpan("A", 1)),
            formationSectors(c.legs[0].formation!!),
        )
    }

    @Test
    fun journeyAndTrips() {
        val c = conn("lausanne>bern#ic1@29393255")
        val fribourg = c.legs[0].stops[1]
        assertEquals(fribourg.dep, fribourg.shownTime)
        assertEquals(c.arr, c.legs[0].lastStop.shownTime)
        assertFalse(fribourg.isPassed(fribourg.dep!!))
        assertTrue(fribourg.isPassed(Instant.fromEpochMilliseconds(fribourg.dep!!.toEpochMilliseconds() + 1)))

        assertFalse(c.isActive(addMin(c.dep, -16)))
        assertTrue(c.isActive(addMin(c.dep, -15)))
        assertTrue(c.isActive(c.arr))
        assertFalse(c.isActive(Instant.fromEpochMilliseconds(c.arr.toEpochMilliseconds() + 1)))

        val later = "lausanne>bern#ir15@29393340"
        val trips = savedTrips(listOf(later, "garbage", c.id), c.arr)
        assertEquals(listOf(c.id, later), trips.map { it.id })
        // Dropped once it arrived more than 3 hours ago.
        assertEquals(listOf(later), savedTrips(listOf(c.id, later), addMin(c.arr, 180)).map { it.id })
    }

    @Test
    fun pinnedClockKeepsRunning() {
        var wall = 1_000_000_000_000L
        AppClock.wallClock = { wall }
        assertTrue(AppClock.pin("2026-11-21T19:35"))
        assertEquals(t1935, now())
        wall += 90_000
        assertEquals("19:36", hhmm(now()))
        assertEquals(Instant.fromEpochMilliseconds(wall), AppClock.wallNow())
        assertFalse(AppClock.pin("not a date"))
        assertEquals(Instant.fromEpochMilliseconds(wall), now())
    }

    @Test
    fun nowFlowTicks() = runTest {
        AppClock.wallClock = { testScheduler.currentTime }
        assertEquals(listOf(0L, 20_000L, 40_000L), nowFlow(20_000).take(3).toList().map { it.toEpochMilliseconds() })
    }
}
