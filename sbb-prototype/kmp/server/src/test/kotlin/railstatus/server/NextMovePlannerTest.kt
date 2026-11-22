package railstatus.server

import kotlinx.coroutines.runBlocking
import railstatus.model.*
import railstatus.nextmove.MoveDecision
import railstatus.nextmove.nextMove
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class NextMovePlannerTest {
    // ---- OJP parsing, on a recorded official OJP 2.0 response (ojp-tests-public, TR-1f NotVia) -----

    @Test
    fun parsesARecordedOjpResponse() {
        val xml = javaClass.getResource("/ojp/notvia-zurich-schaffhausen.response.xml")!!.readText()
        val trips = Ojp.parseTrips(xml)
        assertEquals(5, trips.size)
        val direct = trips[0]
        assertEquals(Instant.parse("2024-12-05T23:04:00Z"), direct.start)
        assertEquals("rail", direct.legs.single().mode)
        assertEquals("RE48", direct.legs.single().line)
        assertEquals("8503000", direct.legs.single().board.stationId) // ch:1:sloid:3000:501:33
        assertEquals("8503424", direct.legs.single().alight.stationId)
        assertTrue(direct.legs.single().calls.size > 2) // intermediate stops included
        // The real walk from the station to the bus stop: Rafz → Rafz, Bahnhof.
        val walk = trips[1].legs.first { it.mode == "walk" }
        assertEquals("8503404", walk.board.stationId)
        assertEquals("8573709", walk.alight.stationId)
    }

    @Test
    fun requestIsWellFormedWithNotVia() {
        val xml = Ojp.tripRequest(TripQuery("8501030", "8501120", Instant.parse("2026-11-22T16:35:00Z"), notVia = listOf("8501037"), noRail = true))
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(xml.byteInputStream())
        assertEquals("8501037", doc.getElementsByTagNameNS("*", "NotVia").item(0).textContent.trim())
        assertEquals("false", doc.getElementsByTagNameNS("*", "IncludeSituationsContext").item(0).textContent)
        assertEquals("true", doc.getElementsByTagNameNS("*", "Exclude").item(0).textContent) // no rail
        assertEquals("rail", doc.getElementsByTagNameNS("*", "PtMode").item(0).textContent)
    }

    @Test
    fun stationIdsFromSloids() {
        assertEquals("8501120", Ojp.stationId("ch:1:sloid:1120"))
        assertEquals("8570084", Ojp.stationId("ch:1:sloid:70084:0:1"))
        assertEquals("8501030", Ojp.stationId("8501030"))
        assertNull(Ojp.stationId(""))
    }

    // ---- the planner, with OJP answers shaped like the 17 Sep closure -----------------------------

    private val nyon = Station("8501030", "Nyon")
    private val allaman = Station("8501035", "Allaman")
    private val etoy = "8501042"
    private val morges = Station("8501037", "Morges")
    private val renens = Station("8501118", "Renens VD")
    private val lausanne = Station("8501120", "Lausanne")
    private val gva = Station("8501008", "Genève")
    private val zone = ZoneId.of("Europe/Zurich")
    private val now = ZonedDateTime.of(2026, 11, 22, 17, 35, 0, 0, zone)
    private fun t(hhmm: String) = ZonedDateTime.of(2026, 11, 22, hhmm.take(2).toInt(), hhmm.takeLast(2).toInt(), 0, 0, zone).toInstant()
    private fun c(id: String, name: String = id, at: String? = null) = OjpCall(id, name, at?.let(::t))
    private fun rail(vararg ids: String) = OjpLeg("rail", "R", ids.map { c(it) })
    private fun trip(start: String, end: String, vararg legs: OjpLeg) = OjpTrip(t(start), t(end), legs.toList())

    /** The judges' train: it stops at Morges, inside the closure. */
    private val ir90 = Train(
        "IR90-1734", "IR", "IR 90", "1734", "Brig",
        listOf(Stop(gva, null, "17:13"), Stop(nyon, "17:27", "17:28"), Stop(morges, "17:41", "17:42"), Stop(lausanne, "17:51", null)),
    )

    private val queries = mutableListOf<TripQuery>()
    private val fake = object : OjpApi {
        override suspend fun trips(q: TripQuery): List<OjpTrip> {
            queries += q
            return when {
                // 1. the closed stations
                q.from == allaman.id && q.to == renens.id -> listOf(trip("17:40", "18:00", rail(allaman.id, etoy, morges.id, renens.id)))
                q.from == renens.id && q.to == allaman.id -> listOf(trip("17:40", "18:00", rail(renens.id, morges.id, etoy, allaman.id)))
                // 2. the line around the train
                q.railOnly && q.from == nyon.id && q.to == lausanne.id -> listOf(
                    trip("17:40", "18:00", rail(nyon.id, morges.id, lausanne.id)), // non-stop: no edges
                    trip("17:45", "18:20", rail(nyon.id, allaman.id, etoy, morges.id, renens.id, lausanne.id)),
                )
                // 3a. to the near edge of the closure (trains are fine here)
                q.from == nyon.id && q.to == allaman.id && !q.noRail -> listOf(
                    trip("17:50", "18:02", OjpLeg("rail", "R3", listOf(c(nyon.id, "Nyon", "17:50"), c(allaman.id, "Allaman", "18:02")))),
                    trip("17:40", "18:30", rail(nyon.id, morges.id, allaman.id)), // nonsense through the closure: dropped
                )
                // 3b. around the closure, no trains
                q.from == allaman.id && q.to == lausanne.id && q.noRail -> listOf(
                    trip(
                        "18:02", "19:30",
                        OjpLeg("walk", null, listOf(c(allaman.id, "Allaman"), c("8579079", "Allaman, gare"))),
                        OjpLeg("bus", "724", listOf(c("8579079", "Allaman, gare", "18:16"), c("8570084", "Morges, gare", "18:40"))),
                        OjpLeg("bus", "701", listOf(c("8570084", "Morges, gare", "18:47"), c("8592000", "Lausanne, Bourdonnette", "19:15"))),
                        OjpLeg("metro", "m1", listOf(c("8592000", "Lausanne, Bourdonnette", "19:18"), c(lausanne.id, "Lausanne", "19:30"))),
                    ),
                )
                else -> emptyList()
            }
        }
    }

    @Test
    fun findsTheRealWayAroundAndDropsTrainsThroughTheClosure() = runBlocking {
        val planner = NextMovePlanner(fake, this)
        val plan = planner.plan(Section(renens, allaman), ir90, null, now)!!
        assertEquals(allaman.id, plan.corridor.westEdge) // the near edge, from the line order
        assertEquals(renens.id, plan.corridor.eastEdge)
        val route = plan.routes.single()
        assertEquals("17:50", route.departAt)
        assertEquals("19:30", route.arriveLatest)
        assertEquals(listOf("train", "walk", "bus", "bus", "metro"), route.legs.map { it.mode })
        // From Nyon (the last stop before the closure) to the edge, then around it with no trains.
        assertTrue(queries.any { it.from == nyon.id && it.to == allaman.id && !it.noRail })
        val around = queries.last()
        assertEquals(allaman.id, around.from)
        assertTrue(around.noRail)
    }

    @Test
    fun theDecisionUsesThePlannersRoutes() = runBlocking {
        val plan = NextMovePlanner(fake, this).plan(Section(allaman, renens), ir90, null, now)!!
        val facts = IncidentFacts(id = "inc-1", version = 2, stage = Stage.IDENTIFIED, trainIds = listOf(ir90.id), delayMin = DelayRange(180, 240))
        val ci = CheckIn("p", "tok", ir90.id, at = "2026-11-22T17:20:00+01:00")
        val nm = nextMove(facts, ir90, ci, "17:35", plan.corridor, plan.routes)!!
        assertEquals(MoveDecision.GO, nm.decision) // 19:30 beats staying: 20:51 at the earliest
        assertEquals("Change at Nyon", nm.headline[Lang.EN])
        assertEquals("17:50", nm.decideBy) // OJP's departure already includes the walk: no assumed transfer time
    }

    @Test
    fun noRouteForSomeoneWaitingPastTheClosure() = runBlocking {
        // Phone 3 in the demo: on the platform at Lausanne. "Change at Nyon" would be nonsense there.
        val plan = NextMovePlanner(fake, this).plan(Section(allaman, renens), ir90, null, now, boardingId = lausanne.id)!!
        assertTrue(plan.routes.isEmpty())
        assertTrue(queries.none { !it.railOnly }) // and no trip asked for them
    }

    @Test
    fun noOjpAnswerMeansNoPlan() = runBlocking {
        val failing = object : OjpApi {
            override suspend fun trips(q: TripQuery): List<OjpTrip> = error("timeout")
        }
        val planner = NextMovePlanner(failing, this)
        assertNull(planner.cached(Section(allaman, renens), ir90, null)) // the caller falls back to the corridor
    }
}
