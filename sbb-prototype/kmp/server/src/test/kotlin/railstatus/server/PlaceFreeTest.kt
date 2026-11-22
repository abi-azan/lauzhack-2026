package railstatus.server

import kotlinx.coroutines.*
import railstatus.model.*
import kotlin.test.*

/**
 * R8: a passenger never reads a "?" or a raw `{placeholder}`: not when the incident's line and location are
 * unknown, not when the cause or the delay is.
 */
class PlaceFreeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stop() = scope.cancel()

    private fun service(autoAckSeconds: Long = 0) =
        IncidentService(MemoryBus(), Hub(), scope, autoAckSeconds).also { it.start() }

    private suspend fun IncidentService.open(line: Section? = null, location: Section? = null, lines: List<String> = emptyList()): String {
        val source = newId("src")
        onTicker(
            TickerEvent(
                id = newId("tk"), sourceEventId = source, kind = TickerKind.CREATED, source = TickerSource.KULT,
                ts = now().iso(), line = line, location = location, lines = lines, text = "Störung",
            ),
        )
        return adminIncidents().first { it.sourceEventId == source }.facts.id
    }

    /** Every template, drafted with a cause and a delay so only the place can be missing. */
    private suspend fun IncidentService.draftAll(id: String): Map<String, PublishUpdateRequest> =
        templates().associate { tpl ->
            tpl.id to draft(id, DraftRequest(tpl.id, cause = mapOf(Lang.EN to "a technical fault"), delayMin = DelayRange(20, 30)))
        }

    private fun PublishUpdateRequest.texts(): List<String> = title.values + body.values

    private fun assertNoPlaceholders(templateId: String, draft: PublishUpdateRequest) {
        assertEquals(Lang.entries.toSet(), draft.title.keys, "$templateId: title languages")
        assertEquals(Lang.entries.toSet(), draft.body.keys, "$templateId: body languages")
        for (s in draft.texts()) {
            assertFalse("?" in s, "$templateId: '?' in \"$s\"")
            assertFalse("{" in s || "}" in s, "$templateId: placeholder left in \"$s\"")
        }
    }

    @Test
    fun noLineNoLocationNeverSendsAQuestionMark() = runBlocking {
        val svc = service()
        val id = svc.open(lines = listOf("IR90"))
        for ((tplId, draft) in svc.draftAll(id)) assertNoPlaceholders(tplId, draft)
        val first = svc.draft(id, DraftRequest(Fixtures.AUTO_ACK_TEMPLATE))
        assertEquals("Disruption on your line", first.title[Lang.EN])
        assertTrue(first.body[Lang.EN]!!.startsWith("There is a disruption on your line. Nothing more is known yet."), first.body[Lang.EN])
        assertEquals(Fixtures.AUTO_ACK_TEMPLATE, first.templateId)
    }

    @Test
    fun safetyNetWithoutAPlaceNeverSendsAQuestionMark() = runBlocking {
        val svc = service(autoAckSeconds = 1)
        val id = svc.open()
        val update = withTimeout(5000) {
            while (true) {
                svc.adminIncident(id).facts.updates.firstOrNull()?.let { return@withTimeout it }
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        assertEquals(Author.AUTO, update.author)
        for (s in update.title.values + update.body.values) assertFalse("?" in s || "{" in s, "auto update: \"$s\"")
        assertEquals("Disruption on your line", update.title[Lang.EN])
    }

    @Test
    fun aLocationWithoutALineNamesThePlaceEverywhere() = runBlocking {
        val svc = service()
        val id = svc.open(location = Section(Fixtures.renens, Fixtures.allaman))
        val drafts = svc.draftAll(id)
        for ((tplId, draft) in drafts) assertNoPlaceholders(tplId, draft)
        assertEquals("Disruption between Renens VD and Allaman", drafts.getValue("investigating-unknown").title[Lang.EN])
        assertTrue("on the Renens VD – Allaman line" in drafts.getValue("investigating-unknown").body[Lang.EN]!!)
    }

    @Test
    fun aLineAndALocationKeepTheirWording() = runBlocking {
        val svc = service()
        val id = svc.open(line = Section(Fixtures.lausanne, Fixtures.geneva), location = Section(Fixtures.renens, Fixtures.allaman))
        val drafts = svc.draftAll(id)
        for ((tplId, draft) in drafts) assertNoPlaceholders(tplId, draft)
        val first = drafts.getValue("investigating-unknown")
        assertEquals("Disruption between Renens VD and Allaman", first.title[Lang.EN])
        assertEquals("Störung zwischen Renens VD und Allaman", first.title[Lang.DE])
        assertTrue("on the Lausanne – Genève line" in first.body[Lang.EN]!!)
    }

    @Test
    fun noCauseNeverSendsARawPlaceholder() = runBlocking {
        // Demo step 4 (scripts/e2e/smoke.mjs) drafts "identified-cause" without a cause: it used to say "Cause: {cause}".
        val svc = service()
        val id = svc.open(line = Section(Fixtures.lausanne, Fixtures.geneva), location = Section(Fixtures.renens, Fixtures.allaman))
        val draft = svc.draft(id, DraftRequest("identified-cause"))
        assertNoPlaceholders("identified-cause", draft)
        assertEquals("Cause identified", draft.title[Lang.EN])
        assertEquals("Ursache bekannt", draft.title[Lang.DE])
        assertTrue(draft.body[Lang.EN]!!.startsWith("The Lausanne – Genève line is interrupted between Renens VD and Allaman. Expect delays."), draft.body[Lang.EN])
        // A known cause is still named.
        val withCause = svc.draft(id, DraftRequest("identified-cause", cause = mapOf(Lang.EN to "a broken overhead line")))
        assertEquals("Cause: a broken overhead line", withCause.title[Lang.EN])
    }

    @Test
    fun everyTemplateWithEveryGapHasAFullWording() = runBlocking {
        val svc = service()
        val incidents = mapOf(
            "no place" to svc.open(),
            "location only" to svc.open(location = Section(Fixtures.renens, Fixtures.allaman)),
            "line only" to svc.open(line = Section(Fixtures.lausanne, Fixtures.geneva)),
            "line and location" to svc.open(line = Section(Fixtures.lausanne, Fixtures.geneva), location = Section(Fixtures.renens, Fixtures.allaman)),
        )
        val causes = listOf(null, mapOf(Lang.EN to "a technical fault"))
        val delays = listOf(null, DelayRange(20, 30))
        for ((place, id) in incidents) for (tpl in svc.templates()) for (cause in causes) for (delay in delays) {
            val draft = svc.draft(id, DraftRequest(tpl.id, cause = cause, delayMin = delay))
            assertNoPlaceholders("${tpl.id} ($place, cause=${cause != null}, delay=${delay != null})", draft)
        }
    }
}
