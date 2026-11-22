package railstatus.mobile.data

import kotlin.test.Test
import kotlin.test.assertEquals

// Expected values from the web app (router.ts / App.tsx) and the browser's URI functions.
class RoutesTest {
    @Test
    fun segmentsAndParentPath() {
        val cases = listOf(
            Triple("/plan", listOf("plan"), "/plan"),
            Triple("", emptyList(), "/"),
            Triple("/", emptyList(), "/"),
            Triple("/plan/connections", listOf("plan", "connections"), "/plan"),
            Triple("/plan/search/to", listOf("plan", "search", "to"), "/plan"),
            Triple("/plan/c/x", listOf("plan", "c", "x"), "/plan"),
            Triple("/plan/c/x/map", listOf("plan", "c", "x", "map"), "/plan/c/x"),
            Triple("/plan/c/x/leg/1", listOf("plan", "c", "x", "leg", "1"), "/plan/c/x"),
            Triple("/plan/c/x/leg/1?tab=formation", listOf("plan", "c", "x", "leg", "1"), "/plan/c/x"),
            Triple("/trips/c/x", listOf("trips", "c", "x"), "/trips"),
            Triple("/profile/notifications", listOf("profile", "notifications"), "/profile"),
            Triple("/c/x", listOf("c", "x"), "/c"),
            Triple("c", listOf("c"), "/c"),
            Triple("/a/b/c/d", listOf("a", "b", "c", "d"), "/a/b"),
        )
        for ((path, segs, parent) in cases) {
            assertEquals(segs, segments(path), path)
            assertEquals(parent, parentPath(path), path)
        }
    }

    @Test
    fun parseRoutes() {
        val id = "lausanne>bern#ic1@29393255"
        val enc = "lausanne%3Ebern%23ic1%4029393255"
        val cases = mapOf(
            "" to Route.Plan(false),
            "/plan" to Route.Plan(false),
            "/plan/touch" to Route.Plan(true),
            "/plan/search/to" to Route.StationSearch(StationField.TO),
            "/plan/search/x" to Route.StationSearch(StationField.FROM),
            "/plan/connections" to Route.Connections,
            "/plan/map" to Route.NetworkMap,
            "/plan/c/$enc" to Route.ConnectionDetail(id, Tab.PLAN, ConnectionView.LIST),
            "/trips/c/$enc/map" to Route.ConnectionDetail(id, Tab.TRIPS, ConnectionView.MAP),
            "/plan/c/$enc/leg/1" to Route.Journey(id, Tab.PLAN, 1, false),
            "/plan/c/$enc/leg" to Route.Journey(id, Tab.PLAN, 0, false),
            "/plan/c/$enc/leg/x" to Route.Journey(id, Tab.PLAN, null, false),
            "/plan/c/$enc/leg/1.5" to Route.Journey(id, Tab.PLAN, null, false),
            "/trips/c/$enc/leg/0?tab=formation" to Route.Journey(id, Tab.TRIPS, 0, true),
            "/plan/c" to Route.Plan(false),
            "/trips" to Route.Trips(false),
            "/trips/commuting" to Route.Trips(true),
            "/easyride" to Route.CheckIn,
            "/easyride/incident" to Route.EasyRideIncident,
            "/easyride/chat" to Route.Chat,
            "/incident/abc" to Route.Incident,
            "/checkin/IR90-1734?car=3&car=4" to Route.CheckInLink("IR90-1734", "3"),
            "/checkin" to Route.CheckInLink(null, null),
            "/tickets" to Route.Tickets,
            "/shop" to Route.Shop,
            "/profile" to Route.Profile,
            "/profile/notifications" to Route.Notifications,
            "/nope" to Route.Plan(false),
        )
        for ((path, route) in cases) assertEquals(route, parseRoute(path), path)
    }

    @Test
    fun pathsRoundTrip() {
        val id = "basel>lugano#g1@29393300"
        assertEquals("/plan/c/basel%3Elugano%23g1%4029393300", connectionPath(Tab.PLAN, id))
        assertEquals(Route.ConnectionDetail(id, Tab.TRIPS, ConnectionView.MAP), parseRoute(connectionPath(Tab.TRIPS, id, ConnectionView.MAP)))
        assertEquals(Route.Journey(id, Tab.PLAN, 1, true), parseRoute(journeyPath(Tab.PLAN, id, 1, formation = true)))
    }

    @Test
    fun uriComponents() {
        assertEquals("lausanne%3Ebern%23ic1%4029393255", encodeURIComponent("lausanne>bern#ic1@29393255"))
        assertEquals("Z%C3%BCrich%20HB%20%2F%20%C3%84%C3%96", encodeURIComponent("Zürich HB / ÄÖ"))
        assertEquals("a-_.!~*'()b", encodeURIComponent("a-_.!~*'()b"))
        assertEquals("a%20b%2Bc%26d%3De%3F", encodeURIComponent("a b+c&d=e?"))
        assertEquals("%F0%9F%98%80", encodeURIComponent("😀"))

        assertEquals("lausanne>bern#ic1@29393255", decodeURIComponent("lausanne%3Ebern%23ic1%4029393255"))
        assertEquals("a+b", decodeURIComponent("a+b"))
        assertEquals("ü", decodeURIComponent("%C3%BC"))
        // These throw in JS; here they come back unchanged.
        for (bad in listOf("%", "%E0%A4%A", "%FF", "x%2")) assertEquals(bad, decodeURIComponent(bad))
    }

    @Test
    fun queryParameters() {
        assertEquals(Route.CheckInLink("t", "c d"), parseRoute("/checkin/t?a+b=1&car=c+d"))
        assertEquals(Route.CheckInLink("t", "%zz"), parseRoute("/checkin/t?car=%zz"))
        assertEquals(Route.CheckInLink("t", "3"), parseRoute("/checkin/t?=5&car=%33"))
        assertEquals(Route.CheckInLink("t", ""), parseRoute("/checkin/t?car"))
    }
}
