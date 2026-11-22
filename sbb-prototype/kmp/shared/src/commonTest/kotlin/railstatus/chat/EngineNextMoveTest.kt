package railstatus.chat

import kotlinx.datetime.TimeZone
import railstatus.model.*
import railstatus.nextmove.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Route questions answer like the passenger's "Our suggestion" card, never against it. */
class EngineNextMoveTest {
    @BeforeTest
    fun localTime() { JsLocal.zone = TimeZone.of("Europe/Zurich") }

    private val lausanne = Station("8501120", "Lausanne")
    private val update = IncidentUpdate(
        id = "upd-1", incidentId = "inc-1", seq = 1, stage = Stage.INVESTIGATING,
        title = mapOf(Lang.EN to "Disruption between Renens VD and Allaman"), body = mapOf(Lang.EN to "Nothing more is known yet."),
        publishedAt = "2026-11-22T17:36:00+01:00", nextUpdateBy = "2026-11-22T18:15:00+01:00", author = Author.ADMIN,
    )
    private fun facts(alternatives: List<Alternative> = emptyList()) = IncidentFacts(
        id = "inc-1", version = 1, stage = Stage.INVESTIGATING, trainIds = listOf("IR90-1734"),
        alternatives = alternatives, nextUpdateBy = "2026-11-22T18:15:00+01:00", updates = listOf(update),
    )
    private val route = MoveOption(
        id = "ojp-0", source = MoveSource.TIMETABLE, title = mapOf(Lang.EN to "Train RE33 · Bus 724"),
        legs = listOf(
            MoveLeg("train", "RE33", "Nyon", "Allaman", "17:50", "18:02"),
            MoveLeg("walk", null, "Allaman", "Allaman, gare"),
            MoveLeg("bus", "724", "Allaman, gare", "Morges, gare", "18:16", "18:40"),
        ),
        departAt = "17:50", arriveEarliest = "19:30", arriveLatest = "19:30",
    )
    private val stay = MoveOption(id = "stay", source = MoveSource.STAY, title = mapOf(Lang.EN to "Stay on this train"))
    private fun move(decision: MoveDecision) = NextMove(
        decision = decision, headline = mapOf(Lang.EN to "Change at Nyon"), why = mapOf(Lang.EN to "…"),
        destination = lausanne, options = if (decision == MoveDecision.GO) listOf(route, stay) else listOf(stay, route), basedOnVersion = 1,
    )
    private fun ask(text: String, f: IncidentFacts = facts(), nm: NextMove? = null, lang: Lang = Lang.EN) =
        answer(Question(text, lang = lang, facts = f, nextMove = nm, forwarded = true))

    @Test
    fun theChatGivesTheCardsRouteWhenSbbHasNoAlternativeYet() {
        val r = ask("Is there another way to Lausanne?", nm = move(MoveDecision.GO))
        assertEquals(Topic.ALTERNATIVE, r.topic)
        assertTrue(r.text.contains("Our suggestion"), r.text)
        assertTrue(r.text.contains("not confirmed by SBB"), r.text)
        assertTrue(r.text.contains("Nyon") && r.text.contains("17:50") && r.text.contains("19:30"), r.text)
        assertFalse(r.text.contains("isn't known"), r.text)
    }

    @Test
    fun whileWaitingItIsOfferedAsABackup() {
        val r = ask("Should I take the bus?", nm = move(MoveDecision.WAIT_FOR_INFO))
        assertTrue(r.text.contains("If you'd rather not wait"), r.text)
        assertTrue(r.text.contains("train RE33 at 17:50 from Nyon"), r.text)
    }

    @Test
    fun sbbsAlternativesStillComeFirst() {
        val alt = Alternative("a1", mapOf(Lang.EN to "Replacement buses Allaman – Renens VD"))
        val r = ask("Is there another way to Lausanne?", f = facts(listOf(alt)), nm = move(MoveDecision.GO))
        assertTrue(r.text.contains("Replacement buses"), r.text)
        assertFalse(r.text.contains("Our suggestion"), r.text)
    }

    @Test
    fun withoutACardItIsStillNotKnown() {
        val r = ask("Is there another way to Lausanne?")
        assertFalse(r.text.contains("Our suggestion"), r.text)
        assertEquals(Source.NOT_KNOWN, r.source)
    }

    @Test
    fun inEveryLanguage() {
        val de = ask("Wie komme ich nach Lausanne?", nm = move(MoveDecision.GO), lang = Lang.DE)
        assertTrue(de.text.contains("Unser Vorschlag") && de.text.contains("Zug RE33"), de.text)
        val fr = ask("Y a-t-il un autre moyen d'aller à Lausanne ?", nm = move(MoveDecision.GO), lang = Lang.FR)
        assertTrue(fr.text.contains("Notre suggestion"), fr.text)
    }
}
