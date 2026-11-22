package railstatus.agent

import kotlinx.coroutines.runBlocking
import railstatus.model.Lang
import railstatus.model.Station
import railstatus.nextmove.MoveDecision
import railstatus.nextmove.MoveOption
import railstatus.nextmove.MoveSource
import railstatus.nextmove.NextMove
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * One real run against OpenRouter, with real 17 Sep 2026 ticker lines (incident 24605 in Daten/intern-liveticker).
 * It's skipped unless OPENROUTER_API_KEY is set, so CI and the normal test run stay offline:
 *   OPENROUTER_API_KEY=… DRAFT_MODEL=openai/gpt-6-luna ./gradlew :agent:jvmTest --tests '*LiveDraftAgentTest*' -i
 */
class LiveDraftAgentTest {
    private val sources = object : DraftSources {
        override suspend fun incidentSummary() = """
            Now: 17:50
            Affected line: Genève – Lausanne
            Location: between Renens VD and Allaman
            Incident detected: 17:32
            Current published stage: investigating
        """.trimIndent()
        override suspend fun ticker() = listOf(
            "17:36 [TCC] Decription de la perturbation: - Avarie ligne de contact avec incendie - COLGEN dès 17h30",
            "17:42 [TCC] 18474 rebrousse à Lausanne sur 18479, circule Lausanne - Martigny comme 88479.",
            "17:43 [TCC] 18479 rebrousse à Morgen sur 18474, circule Morges - Annemasse comme 187xx.",
            "17:44 [TCC] Le trafic ferroviaire est interrompu entre Lausanne et Genève.",
            "17:45 [TCC] 3579 supprimé Genève - Nyon.",
        )
        override suspend fun affectedTrains() = listOf("IR90-1734: IR 90 1734 Genève-Aéroport to Brig, stopped before Allaman")
        override suspend fun publishedUpdates() = listOf("17:33 Disruption Genève – Lausanne: The reason is not yet known. Next update by 17:50.")
        override suspend fun nextMove(trainId: String) = NextMove(
            decision = MoveDecision.WAIT_FOR_INFO,
            headline = mapOf(Lang.EN to "Stay on board for now"),
            why = mapOf(Lang.EN to "SBB hasn't said how long the interruption lasts."),
            destination = Station("8501120", "Lausanne"),
            destinationAssumed = true,
            options = listOf(
                MoveOption("stay", MoveSource.STAY, mapOf(Lang.EN to "Stay on this train")),
                MoveOption("bus-724-701", MoveSource.TIMETABLE, mapOf(Lang.EN to "Regional buses via Morges"),
                    departAt = "18:16", arriveEarliest = "19:43", arriveLatest = "19:43"),
            ),
            decideBy = "18:11",
            basedOnVersion = 1,
        )
        override suspend fun routesAround() = listOf("Regional buses 724 + 701 via Morges, from Allaman hourly at :16, 87 min to Lausanne")
    }

    @Test
    fun draftsFromTheRealTicker() {
        val key = System.getenv("OPENROUTER_API_KEY")?.takeIf { it.isNotBlank() } ?: return println("skipped: no OPENROUTER_API_KEY")
        val model = System.getenv("DRAFT_MODEL") ?: "openai/gpt-6-luna"
        val agent = DraftAgent(DraftAgent.openRouterExecutor(key), DraftAgent.openRouterModel(model))
        val run = runBlocking {
            agent.draft(sources, rules = "You write the next status update for passengers on trains hit by a rail disruption in Switzerland. " +
                "Write German, French, Italian and English. Never invent times or numbers.")
        }
        println("tool calls: " + run.toolCalls.joinToString { "${it.tool}(${it.args})" })
        println("draft: ${run.output}")
        assertTrue(run.toolCalls.any { it.tool == "ticker_events" }, "the agent should read the ticker")
        assertTrue(run.output.title.en.isNotBlank() && run.output.body.de.isNotBlank())
    }
}
