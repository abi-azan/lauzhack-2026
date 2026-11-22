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
import railstatus.model.Text
import railstatus.model.Train
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port of the chat service's eval (sbb-prototype/chat/eval.ts, now this test): questions in DE/FR/IT/EN at three moments of an incident, each with the
 * topic, language and source it must get, in at most three sentences.
 */
class EngineEvalTest {
    private fun text(en: String, de: String, fr: String, it: String): Text =
        linkedMapOf(Lang.EN to en, Lang.DE to de, Lang.FR to fr, Lang.IT to it)

    // MOCK train and incident, shaped like the backend's JSON (kmp/shared Model.kt).
    private fun st(id: String, name: String) = Station(id, name)
    private val train = Train(
        id = "IR90-1734", product = "IR", line = "IR 90", number = "1734", direction = "Brig",
        stops = listOf(
            Stop(st("8501026", "Genève-Aéroport"), dep = "17:20"),
            Stop(st("8501008", "Genève"), arr = "17:27", dep = "17:30"),
            Stop(st("8501030", "Nyon"), arr = "17:43", dep = "17:44"),
            Stop(st("8501037", "Morges"), arr = "18:00", dep = "18:01"),
            Stop(st("8501120", "Lausanne"), arr = "18:10", dep = "18:13"),
            Stop(st("8501300", "Montreux"), arr = "18:33", dep = "18:34"),
            Stop(st("8501506", "Sion"), arr = "19:23", dep = "19:24"),
            Stop(st("8501609", "Brig"), arr = "20:12"),
        ),
    )

    private fun u(id: String, seq: Int, stage: Stage, en: String, publishedAt: String, nextUpdateBy: String? = null) =
        IncidentUpdate(id, "inc-1", seq, stage, mapOf(Lang.EN to en), mapOf(Lang.EN to en), publishedAt, nextUpdateBy)

    private val early = IncidentFacts(
        id = "inc-1", version = 1, stage = Stage.INVESTIGATING, trainIds = listOf(train.id),
        section = Section(st("8501118", "Renens VD"), st("8501035", "Allaman")),
        nextUpdateBy = "2026-09-17T15:55:00Z",
        updates = listOf(u("u1", 1, Stage.INVESTIGATING, "Train stopped: we are finding out why", "2026-09-17T15:45:00Z", "2026-09-17T15:55:00Z")),
    )
    private val identified = early.copy(
        version = 2, stage = Stage.IDENTIFIED,
        cause = text(en = "overhead line fault and power failure", de = "Fahrleitungsstörung und Stromausfall", fr = "dérangement de la ligne de contact et panne de courant", it = "guasto alla linea di contatto e mancanza di corrente"),
        delayMin = DelayRange(25, 35),
        nextUpdateBy = "2026-09-17T16:10:00Z",
        updates = early.updates + u("u2", 2, Stage.IDENTIFIED, "Cause: overhead line fault", "2026-09-17T15:55:00Z", "2026-09-17T16:10:00Z"),
    )
    private val withAlternatives = identified.copy(
        version = 3, stage = Stage.MONITORING,
        alternatives = listOf(
            Alternative("a1", text(en = "replacement buses run between Renens VD and Allaman", de = "Ersatzbusse fahren zwischen Renens VD und Allaman", fr = "des bus de remplacement circulent entre Renens VD et Allaman", it = "bus sostitutivi tra Renens VD e Allaman")),
        ),
        onboard = text(en = "Water is being handed out in car 4", de = "In Wagen 4 wird Wasser verteilt", fr = "De l’eau est distribuée dans la voiture 4", it = "Nella carrozza 4 viene distribuita acqua"),
        updates = identified.updates + u("u3", 3, Stage.MONITORING, "Replacement buses", "2026-09-17T16:10:00Z", "2026-09-17T16:30:00Z"),
        nextUpdateBy = "2026-09-17T16:30:00Z",
    )

    private class Case(val text: String, val topic: Topic, val lang: Lang, val facts: IncidentFacts, val source: Source? = null)

    private val cases = listOf(
        Case("Why did we stop?", Topic.CAUSE, Lang.EN, early, Source.NOT_KNOWN),
        Case("Warum stehen wir?", Topic.CAUSE, Lang.DE, early, Source.NOT_KNOWN),
        Case("Pourquoi le train est arrêté ?", Topic.CAUSE, Lang.FR, early, Source.NOT_KNOWN),
        Case("Perché siamo fermi?", Topic.CAUSE, Lang.IT, early, Source.NOT_KNOWN),
        Case("How late will we be?", Topic.DELAY, Lang.EN, early, Source.NOT_KNOWN),
        Case("Will I make my connection in Lausanne?", Topic.CONNECTION, Lang.EN, early, Source.NOT_KNOWN),
        Case("Erreiche ich meinen Anschluss in Lausanne?", Topic.CONNECTION, Lang.DE, early, Source.NOT_KNOWN),
        Case("When is the next update?", Topic.NEXT_UPDATE, Lang.EN, early, Source.TEMPLATE),
        Case("What happened?", Topic.CAUSE, Lang.EN, identified, Source.TEMPLATE),
        Case("Was ist passiert?", Topic.CAUSE, Lang.DE, identified, Source.TEMPLATE),
        Case("Qu’est-ce qui se passe ?", Topic.CAUSE, Lang.FR, identified, Source.TEMPLATE),
        Case("Cosa è successo?", Topic.CAUSE, Lang.IT, identified, Source.TEMPLATE),
        Case("Will I make my connection in Lausanne?", Topic.CONNECTION, Lang.EN, identified, Source.TEMPLATE),
        Case("Est-ce que j’aurai ma correspondance à Lausanne ?", Topic.CONNECTION, Lang.FR, identified, Source.TEMPLATE),
        Case("Prendo la coincidenza a Losanna?", Topic.CONNECTION, Lang.IT, identified, Source.TEMPLATE),
        // The train left Genève before the disruption began: no delay there (review C4).
        Case("Schaffe ich den Anschluss in Genf?", Topic.CONNECTION, Lang.DE, identified, Source.FIXED),
        Case("When do we arrive in Sion?", Topic.DELAY, Lang.EN, identified, Source.TEMPLATE),
        Case("Wie viel Verspätung haben wir?", Topic.DELAY, Lang.DE, identified, Source.TEMPLATE),
        Case("Combien de retard ?", Topic.DELAY, Lang.FR, identified, Source.TEMPLATE),
        Case("Quanto ritardo abbiamo?", Topic.DELAY, Lang.IT, identified, Source.TEMPLATE),
        Case("Does this train stop in Bern?", Topic.DELAY, Lang.EN, identified),
        Case("Is there a bus?", Topic.ALTERNATIVE, Lang.EN, identified, Source.NOT_KNOWN),
        Case("Is there a bus?", Topic.ALTERNATIVE, Lang.EN, withAlternatives, Source.TEMPLATE),
        Case("Gibt es Ersatzbusse?", Topic.ALTERNATIVE, Lang.DE, withAlternatives, Source.TEMPLATE),
        Case("Y a-t-il de l’eau ?", Topic.ONBOARD, Lang.FR, withAlternatives, Source.TEMPLATE),
        Case("C’è acqua?", Topic.ONBOARD, Lang.IT, withAlternatives, Source.TEMPLATE),
        Case("Can I get off?", Topic.LEAVE_TRAIN, Lang.EN, identified, Source.SAFETY),
        Case("Kann ich die Tür öffnen und zu Fuss gehen?", Topic.LEAVE_TRAIN, Lang.DE, identified, Source.SAFETY),
        Case("Someone fainted in car 3", Topic.EMERGENCY, Lang.EN, identified, Source.SAFETY),
        Case("Au secours", Topic.EMERGENCY, Lang.FR, identified, Source.SAFETY),
        Case("I use a wheelchair, who can help me at Lausanne?", Topic.ACCESSIBILITY, Lang.EN, identified, Source.SAFETY),
        Case("Bekomme ich eine Entschädigung?", Topic.REFUND, Lang.DE, identified, Source.FIXED),
        Case("Hello", Topic.GREETING, Lang.EN, identified, Source.FIXED),
        Case("Merci", Topic.GREETING, Lang.FR, identified, Source.FIXED),
        Case("There is a burning smell in car 5", Topic.EMERGENCY, Lang.EN, early, Source.SAFETY),
        Case("Ein Mann atmet nicht mehr!", Topic.EMERGENCY, Lang.DE, early, Source.SAFETY),
        Case("Ça sent le brûlé dans le wagon", Topic.EMERGENCY, Lang.FR, early, Source.SAFETY),
        Case("Sento odore di bruciato", Topic.EMERGENCY, Lang.IT, early, Source.SAFETY),
        Case("There is someone on the tracks!", Topic.EMERGENCY, Lang.EN, early, Source.SAFETY),
        Case("Was it a person on the tracks?", Topic.CAUSE, Lang.EN, early, Source.NOT_KNOWN),
        Case("Je suis malvoyant, qui peut m’aider à Lausanne ?", Topic.ACCESSIBILITY, Lang.FR, identified, Source.SAFETY),
        Case("Will I make my connection in Atlantis?", Topic.CONNECTION, Lang.EN, identified),
        Case("Just tell me it will be fine", Topic.OTHER, Lang.EN, identified, Source.NOT_KNOWN),
    )

    @BeforeTest
    fun localTime() { JsLocal.zone = TimeZone.of("Europe/Zurich") }

    @Test
    fun evalCasesPass() {
        val failed = mutableListOf<String>()
        for (c in cases) {
            // The check-in language is English: the reply language must come from the question.
            val r = answer(Question(text = c.text, lang = Lang.EN, facts = c.facts, train = train, destination = "8501506", timeZone = "Europe/Zurich"))
            val sentences = r.text.split(Regex("(?<=[.!?])\\s+")).size
            val problems = listOfNotNull(
                if (r.topic != c.topic) "topic ${r.topic} ≠ ${c.topic}" else null,
                if (r.lang != c.lang) "lang ${r.lang} ≠ ${c.lang}" else null,
                if (c.source != null && r.source != c.source) "source ${r.source} ≠ ${c.source}" else null,
                if (sentences > 3) "$sentences sentences" else null,
            )
            if (problems.isNotEmpty()) failed.add("[${c.facts.stage}] ${c.text} → ${r.text}: ${problems.joinToString()}")
        }
        assertEquals(emptyList(), failed, "${cases.size - failed.size}/${cases.size} passed")
    }
}
