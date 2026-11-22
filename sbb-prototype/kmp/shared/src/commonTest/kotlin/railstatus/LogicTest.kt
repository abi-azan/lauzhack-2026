package railstatus

import railstatus.model.*
import kotlin.test.Test
import kotlin.test.assertEquals

class LogicTest {
    private val bern = Station("8507000", "Bern")
    private val thun = Station("8507100", "Thun")
    private val fribourg = Station("8504100", "Fribourg/Freiburg")

    private fun train(id: String, line: String, vararg stations: Station) =
        Train(id, line.substringBefore(' '), line, id.substringAfter('-'), stations.last().name,
            stations.map { Stop(it) }, cars = 8)

    private val trains = listOf(
        train("IC8-1234", "IC 8", bern, thun),
        train("IC1-720", "IC 1", fribourg, bern),
        train("S1-15123", "S 1", fribourg, bern, thun),
    )

    private fun event(lines: List<String>) = TickerEvent(
        id = "t1", sourceEventId = "e1", kind = TickerKind.CREATED, source = TickerSource.VIA,
        ts = "2026-09-01T04:06:07+02:00", line = Section(bern, thun), lines = lines,
    )

    @Test
    fun matchesTrainsOverTheSection() {
        assertEquals(listOf("IC8-1234", "S1-15123"), affectedTrains(event(emptyList()), trains).map { it.id })
    }

    @Test
    fun filtersByLineLabels() {
        assertEquals(listOf("IC8-1234"), affectedTrains(event(listOf("IC6", "IC8")), trains).map { it.id })
    }

    @Test
    fun fillsMissingLanguages() {
        val t = fillLanguages(mapOf(Lang.DE to "Hallo", Lang.EN to "Hello"))
        assertEquals("Hello", t[Lang.FR])
        assertEquals("Hallo", t[Lang.DE])
    }

    @Test
    fun rendersTemplateWithCause() {
        val tpl = Template("x", Stage.IDENTIFIED, "x",
            title = mapOf(Lang.EN to "Cause: {cause}", Lang.DE to "Ursache: {cause}"),
            body = mapOf(Lang.EN to "Between {section}.", Lang.DE to "Zwischen {section}."))
        val r = tpl.render(mapOf("section" to "Bern – Thun"),
            cause = mapOf(Lang.EN to "an accident", Lang.DE to "ein Unfall"))
        assertEquals("Ursache: ein Unfall", r.title[Lang.DE])
        assertEquals("Between Bern – Thun.", r.body[Lang.EN])
    }
}
