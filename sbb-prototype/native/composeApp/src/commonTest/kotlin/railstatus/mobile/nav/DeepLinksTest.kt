package railstatus.mobile.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepLinksTest {
    @Test fun appScheme() {
        assertEquals(DeepLink.CheckIn("IC-1-1734", 7), DeepLinks.parse("railstatus://checkin/IC-1-1734?car=7"))
        assertEquals(DeepLink.CheckIn("90_1734", null), DeepLinks.parse("railstatus://checkin/90_1734"))
        assertEquals(DeepLink.Incident("90_1734"), DeepLinks.parse("railstatus://incident/90_1734"))
    }

    @Test fun webHashRoutes() {
        assertEquals(DeepLink.CheckIn("90 1734", 3), DeepLinks.parse("https://example.org/#/checkin/90%201734?car=3"))
        assertEquals(DeepLink.Incident("x"), DeepLinks.parse("http://localhost:5174/?frame=0#/incident/x"))
        assertEquals(DeepLink.Incident("x"), DeepLinks.parse("/easyride/incident/x"))
    }

    @Test fun unknown() {
        assertNull(DeepLinks.parse("railstatus://plan"))
        assertNull(DeepLinks.parse("https://example.org/"))
    }
}
