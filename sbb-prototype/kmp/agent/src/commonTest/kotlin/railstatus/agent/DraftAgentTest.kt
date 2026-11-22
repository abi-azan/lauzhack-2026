package railstatus.agent

import ai.koog.agents.testing.tools.getMockExecutor
import kotlinx.coroutines.test.runTest
import railstatus.model.Lang
import railstatus.model.Station
import railstatus.nextmove.MoveDecision
import railstatus.nextmove.MoveOption
import railstatus.nextmove.MoveSource
import railstatus.nextmove.NextMove
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The agent loop with a scripted model (Koog's mock executor matches on the last message):
 * task → ticker_events → next_move → "ready" → the typed draft. No network, no key.
 */
class DraftAgentTest {
    private class FakeSources : DraftSources {
        val nextMoveAsked = mutableListOf<String>()
        override suspend fun incidentSummary() = "Now: 19:05\nAffected line: Genève – Lausanne\nLocation: between Allaman and Renens VD"
        override suspend fun ticker() = listOf("18:52 [KULT] Incendie talus ROLLE-ALLAMAN, voie 1+2 interdite, [name] sur place")
        override suspend fun affectedTrains() = listOf("IR90-1734: IR 90 1734 to Lausanne")
        override suspend fun publishedUpdates() = emptyList<String>()
        override suspend fun nextMove(trainId: String): NextMove {
            nextMoveAsked += trainId
            return NextMove(
                decision = MoveDecision.WAIT_FOR_INFO,
                headline = mapOf(Lang.EN to "Stay on board for now"),
                why = mapOf(Lang.EN to "SBB hasn't said how long yet."),
                destination = Station("lausanne", "Lausanne"),
                options = listOf(MoveOption("stay", MoveSource.STAY, mapOf(Lang.EN to "Stay on this train"))),
                decideBy = "19:11",
                basedOnVersion = 1,
            )
        }
        override suspend fun routesAround() = listOf("Bus 724 + 701 via Morges, hourly at :16")
        override suspend fun passengerQuestions() = listOf("Connection in Lausanne: 12 passengers")
    }

    private val draftJson = """
        {"stage":"investigating",
         "title":{"de":"Unterbruch Genf–Lausanne","fr":"Interruption Genève–Lausanne","it":"Interruzione Ginevra–Losanna","en":"Interrupted service Geneva–Lausanne"},
         "body":{"de":"Grund unbekannt. Nächste Information um {next}.","fr":"Raison inconnue. Prochaine information à {next}.","it":"Motivo sconosciuto. Prossimo aggiornamento alle {next}.","en":"The reason is not yet known. Next update by {next}."},
         "alternatives":[],"nextUpdateInMin":15}
    """.trimIndent()

    @Test
    fun readsTheEvidenceThroughToolsThenReturnsATypedDraft() = runTest {
        val sources = FakeSources()
        val tools = draftTools(sources)
        val ticker = tools.filterIsInstance<TickerTool>().single()
        val nextMove = tools.filterIsInstance<NextMoveTool>().single()
        val executor = getMockExecutor {
            mockLLMToolCall(ticker, NoArgs()) onRequestContains "write the next status update"
            mockLLMToolCall(nextMove, NextMoveArgs("IR90-1734")) onRequestContains "ROLLE-ALLAMAN"
            mockLLMAnswer("I have what I need.") onRequestContains "Decision: wait_for_info"
            mockLLMAnswer(draftJson) onRequestContains "structured JSON"
            mockLLMAnswer("unexpected request").asDefaultResponse
        }

        val run = DraftAgent(executor, DraftAgent.openRouterModel("test/model")).draft(sources, rules = "Write status updates.")

        assertEquals("investigating", run.output.stage)
        assertEquals("Interrupted service Geneva–Lausanne", run.output.title.en)
        assertTrue(run.output.body.de.contains("{next}"))
        assertEquals(listOf("ticker_events", "next_move"), run.toolCalls.map { it.tool })
        assertTrue(run.toolCalls[0].resultPreview.contains("ROLLE-ALLAMAN"))
        assertTrue(run.toolCalls[1].resultPreview.contains("Stay on board for now"))
        assertEquals(listOf("IR90-1734"), sources.nextMoveAsked)
    }

    @Test
    fun describesTheNextMoveWithItsSources() = runTest {
        val text = describe(FakeSources().nextMove("IR90-1734"))
        assertTrue(text.startsWith("Decision: wait_for_info: Stay on board for now"))
        assertTrue(text.contains("[stay] Stay on this train"))
        assertTrue(text.contains("Decide by: 19:11"))
    }

    @Test
    fun readsWhatPassengersAskBeforeWriting() = runTest {
        val sources = FakeSources()
        val questions = draftTools(sources).filterIsInstance<PassengerQuestionsTool>().single()
        val executor = getMockExecutor {
            mockLLMToolCall(questions, NoArgs()) onRequestContains "write the next status update"
            mockLLMAnswer("I have what I need.") onRequestContains "Connection in Lausanne"
            mockLLMAnswer(draftJson) onRequestContains "structured JSON"
            mockLLMAnswer("unexpected request").asDefaultResponse
        }

        val run = DraftAgent(executor, DraftAgent.openRouterModel("test/model")).draft(sources, rules = "Write status updates.")

        assertEquals(listOf("passenger_questions"), run.toolCalls.map { it.tool })
        assertEquals("Connection in Lausanne: 12 passengers", run.toolCalls.single().resultPreview)
    }

    @Test
    fun saysWhenNobodyHasAskedYet() = runTest {
        val none = object : DraftSources by FakeSources() {
            override suspend fun passengerQuestions() = emptyList<String>()
        }
        assertEquals("(no passenger questions yet)", PassengerQuestionsTool(none).execute(NoArgs()))
    }
}
