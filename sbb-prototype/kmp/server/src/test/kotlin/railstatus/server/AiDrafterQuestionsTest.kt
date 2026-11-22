package railstatus.server

import railstatus.model.*
import kotlin.test.*

/** The single-call draft sees the chat's question groups: labels and counts, never a passenger's own words. */
class AiDrafterQuestionsTest {
    private val clusters = listOf(
        QuestionCluster("c1", "Bus to Geneva", 3, sample = "is there a bus my name is Jean Dupont"),
        QuestionCluster("c2", "Connection in Lausanne", 12, sample = "will I catch the 18:04"),
    )

    private val incident = AdminIncident(
        facts = IncidentFacts(
            id = "inc-test", version = 0, stage = Stage.DETECTED, trainIds = listOf("IR90-1734"),
            section = Section(Fixtures.lausanne, Fixtures.geneva),
        ),
        sourceEventId = "2195005188970", detectedAt = "2026-09-17T17:22:00+02:00", ticker = emptyList(),
    )

    @Test
    fun promptListsQuestionGroupsMostAskedFirst() {
        val prompt = AiDrafter("", "").context(incident, Fixtures.trains, questionLines(clusters))
        val a = prompt.indexOf("Connection in Lausanne: 12 passengers")
        val b = prompt.indexOf("Bus to Geneva: 3 passengers")
        assertTrue(a >= 0 && b > a, prompt)
        assertFalse("Jean Dupont" in prompt)
        assertFalse("18:04" in prompt)
    }

    @Test
    fun noQuestionsNoSection() {
        val prompt = AiDrafter("", "").context(incident, Fixtures.trains)
        assertFalse("support chat" in prompt)
    }
}
