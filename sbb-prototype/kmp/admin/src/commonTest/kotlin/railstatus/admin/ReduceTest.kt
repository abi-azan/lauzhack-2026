package railstatus.admin

import railstatus.model.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ReduceTest {
    private fun incident(id: String, stage: Stage = Stage.DETECTED) = AdminIncident(
        facts = IncidentFacts(id, 0, stage, listOf("IC8-1234")),
        sourceEventId = "src-$id",
        detectedAt = "2026-11-21T22:00:00+01:00",
        passengersByTrain = mapOf("IC8-1234" to 3, "S1-15146" to 2),
    )

    @Test
    fun newIncidentIsAddedOnTopAndSelected() {
        val s1 = reduce(AdminState(), IncidentChanged("t", incident("a")))
        val s2 = reduce(s1, IncidentChanged("t", incident("b")))
        assertEquals(listOf("b", "a"), s2.incidents.map { it.facts.id })
        assertEquals("b", s2.selected?.facts?.id)
        assertEquals(5, s2.selected?.passengers)
    }

    @Test
    fun updateReplacesInPlaceAndKeepsSelection() {
        var s = reduce(AdminState(), IncidentChanged("t", incident("a")))
        s = reduce(s, IncidentChanged("t", incident("b")))
        s = s.copy(selectedId = "a")
        s = reduce(s, IncidentChanged("t", incident("a", Stage.INVESTIGATING)))
        assertEquals(Stage.INVESTIGATING, s.incidents.last().facts.stage)
        assertEquals("a", s.selectedId)
    }

    @Test
    fun resetClearsAndTickerIsCapped() {
        var s = AdminState()
        val ev = TickerEvent("x", "src", TickerKind.UPDATED, TickerSource.KULT, "2026-11-21T22:00:00+01:00")
        repeat(TICKER_LIMIT + 5) { s = reduce(s, TickerEntry("t", ev)) }
        assertEquals(TICKER_LIMIT, s.ticker.size)
        s = reduce(reduce(s, IncidentChanged("t", incident("a"))), Reset("t"))
        assertEquals(0, s.ticker.size)
        assertEquals(0, s.incidents.size)
    }

    @Test
    fun formatsTimes() {
        assertEquals("04:06", hhmm("2026-09-01T04:06:07+02:00"))
        assertEquals("–", hhmm(null))
    }
}
