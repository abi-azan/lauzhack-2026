package railstatus.server

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import railstatus.model.*
import kotlin.test.*

class EndToEndTest {
    private fun ApplicationTestBuilder.setup(autoAckSeconds: Long = 0, chatKey: String? = null, adminKey: String? = null): HttpClient {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = MemoryBus()
        val hub = Hub()
        val service = IncidentService(bus, hub, scope, autoAckSeconds)
        service.start()
        application { railModule(service, hub, bus, null, chat = ChatConfig(key = chatKey), adminKey = adminKey) }
        return createClient {
            install(ContentNegotiation) { json(RailJson) }
            install(WebSockets)
        }
    }

    private suspend fun HttpClient.checkIn(trainId: String, lang: Lang = Lang.EN): CheckInResponse =
        post("/api/checkins") {
            contentType(ContentType.Application.Json)
            setBody(CheckInRequest(trainId, car = 7, lang = lang, method = CheckInMethod.QR))
        }.body()

    private suspend fun HttpClient.fireAccident() {
        val r = post("/api/ingest/ticker") {
            contentType(ContentType.Application.Json)
            setBody(
                TickerInput(
                    sourceEventId = "2195005188970", kind = TickerKind.CREATED, source = TickerSource.VIA,
                    reasonCode = "EG_008", line = Section(Fixtures.lausanne, Fixtures.geneva),
                    location = Section(Fixtures.renens, Fixtures.allaman),
                    lines = listOf("IC1", "IR90", "RE33"), text = "Fahrleitungsstörung",
                ),
            )
        }
        assertEquals(HttpStatusCode.Accepted, r.status)
    }

    private suspend fun HttpClient.awaitIncident(predicate: (AdminIncident) -> Boolean = { true }): AdminIncident =
        withTimeout(5000) {
            while (true) {
                val list: List<AdminIncident> = get("/api/admin/incidents").body()
                list.firstOrNull(predicate)?.let { return@withTimeout it }
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    private suspend fun DefaultClientWebSocketSession.nextEvent(): WsEvent = withTimeout(5000) {
        val frame = incoming.receive() as Frame.Text
        RailJson.decodeFromString(WsEvent.serializer(), frame.readText())
    }

    @Test
    fun tickerToPassengerEndToEnd() = testApplication {
        val client = setup()
        val onIr90 = client.checkIn("IR90-1734", Lang.DE)
        val onIc8 = client.checkIn("IC8-1234")

        client.webSocket("/ws/passenger?token=${onIr90.checkIn.token}") {
            val hello = nextEvent()
            assertIs<Hello>(hello)
            assertNull(hello.incident)

            client.fireAccident()
            val inc = client.awaitIncident()
            assertEquals(Stage.DETECTED, inc.facts.stage)
            assertEquals(listOf("IR90-1734", "IC1-725", "RE33-4234"), inc.facts.trainIds)
            assertEquals(1, inc.passengersByTrain["IR90-1734"])

            // Detected incidents are internal.
            assertEquals(HttpStatusCode.NotFound, client.get("/api/incidents/${inc.facts.id}").status)

            val draft: PublishUpdateRequest = client.post("/api/admin/incidents/${inc.facts.id}/draft") {
                contentType(ContentType.Application.Json)
                setBody(DraftRequest("investigating-unknown"))
            }.body()
            assertTrue(draft.title[Lang.DE]!!.contains("Renens VD und Allaman"), draft.title[Lang.DE])

            val published = client.post("/api/admin/incidents/${inc.facts.id}/updates") {
                contentType(ContentType.Application.Json)
                setBody(draft)
            }
            assertEquals(HttpStatusCode.Created, published.status)

            val ev = nextEvent()
            assertIs<IncidentUpdated>(ev)
            assertEquals(Stage.INVESTIGATING, ev.incident.stage)
            assertEquals(1, ev.update.seq)
            assertNotNull(ev.incident.nextUpdateBy)

            // Metrics: one passenger reached.
            val after = client.awaitIncident { it.metrics.firstNotifiedAt != null }
            assertEquals(1, after.metrics.lastDeliveries)
        }

        // Someone checking in now gets the open incident straight away.
        val late = client.checkIn("RE33-4234")
        assertEquals(Stage.INVESTIGATING, late.incident?.stage)
        // The chat service can look a passenger up by token.
        val view: CheckInResponse = client.get("/api/checkins/${late.checkIn.token}").body()
        assertEquals(Stage.INVESTIGATING, view.incident?.stage)
        // The IC 8 passenger isn't affected.
        assertNull(client.checkIn("IC8-1234").incident)
        assertNotNull(onIc8)
    }

    @Test
    fun safetyNetPublishesWhenNobodyDoes() = testApplication {
        val client = setup(autoAckSeconds = 1)
        val p = client.checkIn("IR90-1734")
        client.webSocket("/ws/passenger?token=${p.checkIn.token}") {
            assertIs<Hello>(nextEvent())
            client.fireAccident()
            val ev = nextEvent()
            assertIs<IncidentUpdated>(ev)
            assertEquals(Author.AUTO, ev.update.author)
            assertEquals(Stage.INVESTIGATING, ev.update.stage)
        }
    }

    @Test
    fun delayBelongsToTheUpdateThatStatesIt() = testApplication {
        val client = setup()
        client.fireAccident()
        val id = client.awaitIncident().facts.id
        suspend fun publish(templateId: String, delay: DelayRange? = null): IncidentFacts {
            val draft: PublishUpdateRequest = client.post("/api/admin/incidents/$id/draft") {
                contentType(ContentType.Application.Json)
                setBody(DraftRequest(templateId, cause = mapOf(Lang.EN to "a fault"), delayMin = delay))
            }.body()
            val r = client.post("/api/admin/incidents/$id/updates") {
                contentType(ContentType.Application.Json)
                setBody(draft)
            }
            assertEquals(HttpStatusCode.Created, r.status)
            return client.get("/api/admin/incidents/$id").body<AdminIncident>().facts
        }

        // A diversion with a delay: the text and the facts carry the same numbers.
        val withDelay = publish("monitoring-diversion", DelayRange(180, 240))
        assertEquals(DelayRange(180, 240), withDelay.delayMin)
        assertEquals(Section(Fixtures.renens, Fixtures.allaman), withDelay.location) // the app's map draws the closure
        assertTrue(withDelay.updates.last().title[Lang.EN]!!.contains("180–240"))
        // A later update that states no delay clears it: no stale numbers on the phone.
        assertNull(publish("identified-cause").delayMin)
        // Resolved never keeps a delay.
        publish("monitoring-diversion", DelayRange(20, 30))
        val resolved = publish("resolved")
        assertNull(resolved.delayMin)
        assertTrue(resolved.alternatives.isEmpty()) // C5: no stale alternatives once the line is open
        assertNull(resolved.location) // and the map stops drawing it
    }

    @Test
    fun unknownTokenIsRejected() = testApplication {
        val client = setup()
        client.webSocket("/ws/passenger?token=nope") {
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
    }

    @Test
    fun adminCanOpenAnIncidentByHand() = testApplication {
        val client = setup()
        val r = client.post("/api/admin/incidents") {
            contentType(ContentType.Application.Json)
            setBody(CreateIncidentRequest(line = Section(Fixtures.lausanne, Fixtures.geneva), lines = listOf("IR90")))
        }
        assertEquals(HttpStatusCode.Created, r.status)
        val inc: AdminIncident = r.body()
        assertEquals(listOf("IR90-1734"), inc.facts.trainIds)
        assertEquals(Stage.DETECTED, inc.facts.stage)
        assertEquals(TickerSource.MANUAL, inc.ticker.single().source)
    }

    @Test
    fun chatQuestionsNeedTheKey() = testApplication {
        val client = setup(chatKey = "s3cret")
        val body = """{"type":"questions.changed","incidentId":"inc-1","clusters":[{"id":"c","label":"Connections","count":3}]}"""
        val denied = client.post("/api/internal/questions") { contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        val ok = client.post("/api/internal/questions") {
            contentType(ContentType.Application.Json); header("X-Chat-Key", "s3cret"); setBody(body)
        }
        assertEquals(HttpStatusCode.NoContent, ok.status)
        val stored: List<QuestionsChanged> = client.get("/api/admin/questions").body()
        assertEquals(3, stored.single().clusters.single().count)
    }

    @Test
    fun lateTickerAfterResolveDoesNotReopen() = testApplication {
        val client = setup()
        client.fireAccident()
        val inc = client.awaitIncident()
        val draft: PublishUpdateRequest = client.post("/api/admin/incidents/${inc.facts.id}/draft") {
            contentType(ContentType.Application.Json); setBody(DraftRequest("resolved"))
        }.body()
        client.post("/api/admin/incidents/${inc.facts.id}/updates") { contentType(ContentType.Application.Json); setBody(draft) }
        client.post("/api/ingest/ticker") {
            contentType(ContentType.Application.Json)
            setBody(TickerInput(sourceEventId = "2195005188970", kind = TickerKind.UPDATED, source = TickerSource.KULT, text = "KULT Liveticker terminé"))
        }
        val after = client.awaitIncident { it.ticker.size == 2 }
        val all: List<AdminIncident> = client.get("/api/admin/incidents").body()
        assertEquals(1, all.size, "a late message must not open a second incident")
        assertEquals(Stage.RESOLVED, after.facts.stage)
        assertNull(after.autoAckAt)
    }

    @Test
    fun adminRoutesNeedTheKey() = testApplication {
        val client = setup(adminKey = "k3y")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/admin/incidents").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/control/reset").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/admin/incidents") { header("X-Admin-Key", "k3y") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/admin/incidents?key=k3y").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/trains").status, "passenger routes stay open")
    }

    @Test
    fun dashboardGetsTheSafetyNetTime() = testApplication {
        val client = setup(autoAckSeconds = 15)
        client.fireAccident()
        val inc = client.awaitIncident()
        val at = java.time.OffsetDateTime.parse(inc.autoAckAt!!)
        assertEquals(15, java.time.Duration.between(java.time.OffsetDateTime.parse(inc.detectedAt), at).seconds)
    }
}
