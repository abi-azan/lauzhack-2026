package railstatus.server

import ai.koog.agents.testing.tools.getMockExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import railstatus.agent.DraftAgent
import railstatus.agent.PassengerQuestionsTool
import railstatus.model.*
import kotlin.test.*

class KoogDrafterTest {
    private val draftJson = """
        {"stage":"identified",
         "title":{"de":"Unterbruch Renens VD–Allaman","fr":"Interruption Renens VD–Allaman","it":"Interruzione Renens VD–Allaman","en":"Interrupted service: Renens VD–Allaman"},
         "body":{"de":"Fahrleitungsstörung. Weitere Informationen erhalten Sie um zirka {next}.","fr":"Panne de la ligne de contact. Vous recevrez d’autres informations vers {next}.","it":"Guasto alla linea di contatto. Ulteriori informazioni verso le {next}.","en":"Overhead line fault. Further information at approximately {next}. Call +41 79 123 45 67."},
         "alternatives":[],"nextUpdateInMin":45,
         "cause":{"de":"Fahrleitungsstörung","fr":"panne de la ligne de contact","it":"guasto alla linea di contatto","en":"an overhead line fault"}}
    """.trimIndent()

    private fun service() = IncidentService(MemoryBus(), Hub(), CoroutineScope(SupervisorJob() + Dispatchers.Default), 0)

    private suspend fun IncidentService.incident(): AdminIncident = createManual(
        CreateIncidentRequest(line = Section(Fixtures.lausanne, Fixtures.geneva), location = Section(Fixtures.renens, Fixtures.allaman), lines = listOf("IR90")),
    )

    private val fallback = object : Drafter {
        var called = 0
        override suspend fun draft(inc: AdminIncident, trains: List<Train>): PublishUpdateRequest {
            called++
            return PublishUpdateRequest(Stage.INVESTIGATING, mapOf(Lang.EN to "fallback"), mapOf(Lang.EN to "fallback"), templateId = "ai")
        }
    }
    private val guard = AiDrafter("unused", "unused")::guard

    @Test
    fun theAgentsDraftBecomesAnUpdateWithTheSameGuard() = runBlocking {
        val executor = getMockExecutor {
            mockLLMAnswer(draftJson) onRequestContains "structured JSON"
            mockLLMAnswer("I have what I need.").asDefaultResponse
        }
        val svc = service()
        val drafter = KoogDrafter(DraftAgent(executor, DraftAgent.openRouterModel("test/model")), fallback, svc, guard)
        val req = drafter.draft(svc.incident(), svc.trains())
        assertEquals(Stage.IDENTIFIED, req.stage)
        assertEquals("Interrupted service: Renens VD–Allaman", req.title[Lang.EN])
        assertEquals(30, req.nextUpdateInMin) // clamped to 10..30
        assertFalse(req.body[Lang.EN]!!.contains("{next}"))
        assertFalse(req.body[Lang.EN]!!.contains("79 123"), req.body[Lang.EN]) // the personal-data guard ran
        assertEquals("an overhead line fault", req.cause?.get(Lang.EN)) // the agent's cause, in 4 languages (C3)
        assertEquals(0, fallback.called)
    }

    @Test
    fun aFailingAgentFallsBackToTheSingleCallDrafter() = runBlocking {
        // The model answers garbage where the typed draft should be: the agent throws, the dashboard still gets a draft.
        val broken = getMockExecutor {
            mockLLMAnswer("this is not the draft").asDefaultResponse
        }
        val svc = service()
        val req = KoogDrafter(DraftAgent(broken, DraftAgent.openRouterModel("test/model")), fallback, svc, guard).draft(svc.incident(), svc.trains())
        assertEquals("fallback", req.title[Lang.EN])
        assertEquals(1, fallback.called)
    }

    private fun cluster(label: String, count: Int, urgent: Boolean = false) =
        QuestionCluster(id = label, label = label, count = count, sample = "my number is 079 123 45 67", urgent = urgent)

    @Test
    fun questionGroupsAreLabelsAndCountsNeverPassengersWords() {
        val lines = questionLines(listOf(cluster("Is there a bus?", 1), cluster("Connection in Lausanne", 12), cluster("Medical help", 1, urgent = true)))
        assertEquals(listOf("Medical help: 1 passenger (urgent)", "Connection in Lausanne: 12 passengers", "Is there a bus?: 1 passenger"), lines)
        assertTrue(lines.none { "079" in it })
    }

    @Test
    fun theAgentCanReadTheIncidentsQuestionGroups() = runBlocking {
        val tool = PassengerQuestionsTool(object : railstatus.agent.DraftSources {
            override suspend fun incidentSummary() = ""
            override suspend fun ticker() = emptyList<String>()
            override suspend fun affectedTrains() = emptyList<String>()
            override suspend fun publishedUpdates() = emptyList<String>()
            override suspend fun nextMove(trainId: String) = null
            override suspend fun routesAround() = null
        })
        val executor = getMockExecutor {
            mockLLMToolCall(tool, railstatus.agent.NoArgs()) onRequestContains "write the next status update"
            mockLLMAnswer(draftJson) onRequestContains "structured JSON"
            mockLLMAnswer("I have what I need.").asDefaultResponse
        }
        val svc = service()
        val inc = svc.incident()
        val changed = mutableListOf<String>()
        svc.onChange = { changed += it }
        svc.onQuestions(QuestionsChanged("2026-09-17T19:10:00Z", inc.facts.id, listOf(cluster("Connection in Lausanne", 12))))
        assertEquals(listOf(inc.facts.id), changed) // the draft desk drafts again
        assertEquals(12, svc.questionCount(inc.facts.id))

        val req = KoogDrafter(DraftAgent(executor, DraftAgent.openRouterModel("test/model")), fallback, svc, guard).draft(svc.adminIncident(inc.facts.id), svc.trains())
        assertEquals(listOf("passenger_questions"), req.draftedBy?.tools)
        assertEquals(0, fallback.called)
    }
}
