package railstatus.chat

import kotlinx.datetime.TimeZone
import railstatus.model.Alternative
import railstatus.model.DelayRange
import railstatus.model.IncidentFacts
import railstatus.model.IncidentUpdate
import railstatus.model.Lang
import railstatus.model.Section
import railstatus.model.Stage
import railstatus.model.Station
import railstatus.model.Stop
import railstatus.model.Train
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The chat items of the 22 Nov review (docs/review-2026-11-22.md): C1, C2, C4, C5. */
class EngineReviewTest {
    @BeforeTest
    fun localTime() { JsLocal.zone = TimeZone.of("Europe/Zurich") }

    private fun st(id: String, name: String) = Station(id, name)
    private val train = Train(
        id = "IR90-1734", product = "IR", line = "IR 90", number = "1734", direction = "Brig",
        stops = listOf(
            Stop(st("8501026", "Genève-Aéroport"), dep = "17:20"),
            Stop(st("8501008", "Genève"), arr = "17:27", dep = "17:30"),
            Stop(st("8501030", "Nyon"), arr = "17:43", dep = "17:44"),
            Stop(st("8501037", "Morges"), arr = "18:00", dep = "18:01"),
            Stop(st("8501120", "Lausanne"), arr = "18:10", dep = "18:13"),
            Stop(st("8501506", "Sion"), arr = "19:23", dep = "19:24"),
            Stop(st("8501609", "Brig"), arr = "20:12"),
        ),
    )

    private fun u(id: String, seq: Int, stage: Stage, en: String, publishedAt: String) =
        IncidentUpdate(id, "inc-1", seq, stage, mapOf(Lang.EN to en), mapOf(Lang.EN to en), publishedAt)

    // The first update at 17:45 Zurich: Genève (17:30) and Nyon (17:44) are behind the train.
    private val identified = IncidentFacts(
        id = "inc-1", version = 2, stage = Stage.IDENTIFIED, trainIds = listOf(train.id),
        section = Section(st("8501118", "Renens VD"), st("8501035", "Allaman")),
        cause = mapOf(Lang.EN to "an overhead line fire"), delayMin = DelayRange(25, 35),
        nextUpdateBy = "2026-09-17T16:10:00Z",
        alternatives = listOf(Alternative("a1", mapOf(Lang.EN to "replacement buses run between Renens VD and Allaman"))),
        updates = listOf(
            u("u1", 1, Stage.INVESTIGATING, "Train stopped: we are finding out why", "2026-09-17T15:45:00Z"),
            u("u2", 2, Stage.IDENTIFIED, "Fire on the overhead line", "2026-09-17T15:55:00Z"),
        ),
    )
    private val resolved = identified.copy(
        version = 3, stage = Stage.RESOLVED, nextUpdateBy = null, resolvedAt = "2026-09-17T19:00:00Z",
        updates = identified.updates + u("u3", 3, Stage.RESOLVED, "The line is open again. Trains are running normally.", "2026-09-17T19:00:00Z"),
    )

    private fun ask(text: String, facts: IncidentFacts = identified, dest: String? = "8501506") =
        answer(Question(text = text, facts = facts, train = train, destination = dest, timeZone = "Europe/Zurich"))

    // ---- C1: real emergencies the rules missed ---------------------------------------------------------

    @Test
    fun c1RealEmergenciesGetTheSafetyText() {
        for (q in listOf(
            "I cannot breathe", "I can't breathe", "Ich kann nicht atmen", "Je ne peux pas respirer", "Non riesco a respirare",
            "My son is choking", "My husband has chest pain", "Someone is attacking people with a knife",
            "Ein Mann greift Leute mit einem Messer an", "Quelqu'un agresse des passagers", "Un uomo ha un coltello",
            "Mein Vater hat Brustschmerzen", "Il a une douleur dans la poitrine", "Ho un dolore al petto",
        )) {
            val r = ask(q)
            assertEquals(Topic.EMERGENCY, r.topic, q)
            assertTrue(r.urgent, q)
            assertTrue("112" in r.text, q)
        }
    }

    // ---- C2: questions about the incident aren't emergencies ---------------------------------------------

    @Test
    fun c2QuestionsAboutTheIncidentAreTheCause() {
        for (q in listOf(
            "Is the fire out?", "Was anyone injured?", "Ist das Feuer gelöscht?", "Y a-t-il eu un incendie ?",
            "Was it a fire?", "Gab es Verletzte?", "Le feu est éteint ?", "Il fuoco è stato spento?", "Ci sono stati feriti?",
            "Who is the fire chief on site?", "Are the firefighters there yet?", "Les pompiers sont arrivés ?",
        )) {
            val r = ask(q)
            assertFalse(r.topic == Topic.EMERGENCY, "$q → ${r.text}")
            assertFalse(r.urgent, q)
            assertFalse("112" in r.text, q)
        }
        assertEquals(Topic.CAUSE, ask("Is the fire out?").topic)
        assertTrue("overhead line fire" in ask("Is the fire out?").text)
    }

    @Test
    fun c2RealFiresAndInjuriesStillAre() {
        for (q in listOf(
            "Is there a fire?", "Fire!", "Es brennt!", "There is smoke in our coach", "The fire is next to us!",
            "Someone is hurt", "Il y a de la fumée dans le train", "Jemand ist im Zug verletzt", "C'è fumo nel vagone",
            "Stimmt es, dass es brennt? Das hat jemand im Wagen gesagt.",
        )) assertEquals(Topic.EMERGENCY, ask(q).topic, q)
    }

    // ---- C4: no delay at stops already passed -------------------------------------------------------------

    @Test
    fun c4NoDelayAtStopsAlreadyPassed() {
        val nyon = ask("When do we arrive in Nyon?")
        assertFalse("instead of" in nyon.text, nyon.text)
        assertTrue("already passed Nyon" in nyon.text, nyon.text)
        val connection = ask("Will I make my connection in Genève?")
        assertTrue("already passed Genève" in connection.text, connection.text)
        // After the closure the delay applies.
        val lausanne = ask("When do we arrive in Lausanne?")
        assertTrue("instead of 18:10" in lausanne.text, lausanne.text)
        assertTrue("instead of 19:23" in ask("How late will we be?").text)
    }

    // ---- "when do we move again", the way people ask it in German, French and Italian ----------------------

    @Test
    fun whenDoWeMoveAgainIsTheDelay() {
        for (q in listOf(
            "Wann fährt der Zug weiter?", "Wann geht es weiter?", "Wie lange dauert es noch?", "Wie lange stehen wir noch?",
            "Wann fahren wir weiter?", "Quand est-ce qu'on repart ?", "Combien de temps encore ?", "Quando ripartiamo?",
            "Quanto ci vuole ancora?",
        )) {
            val r = ask(q)
            assertEquals(Topic.DELAY, r.topic, q)
            assertTrue("19:23" in r.text, "$q → ${r.text}") // the delay at the destination, Sion
        }
    }

    // ---- C5: after "resolved" ----------------------------------------------------------------------------

    @Test
    fun c5AfterResolvedTheLineIsOpenAgain() {
        for (q in listOf("How late will we be?", "Is there a replacement bus?", "Will I make my connection in Lausanne?", "Wann fährt der Zug weiter?")) {
            val r = ask(q, resolved)
            assertTrue("open again" in r.text, "$q → ${r.text}")
            assertFalse("Options so far" in r.text || "isn’t known" in r.text, q)
            assertEquals(listOf("u3"), r.citedUpdateIds)
        }
        // Questions the resolved update doesn't answer keep their answers.
        assertTrue("112" in ask("Someone fainted", resolved).text)
        assertTrue("compensation" in ask("Do I get my money back?", resolved).text)
    }
}
