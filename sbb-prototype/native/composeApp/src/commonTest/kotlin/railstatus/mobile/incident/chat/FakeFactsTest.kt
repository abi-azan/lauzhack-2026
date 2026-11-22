package railstatus.mobile.incident.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import railstatus.mobile.incident.GoldenData
import railstatus.mobile.incident.incidentFrom
import railstatus.model.IncidentFacts
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The offline demo's facts must be what the web client's fakeFacts() built (GoldenData, from golden/gen.ts).
 * The engine itself is tested in kmp/shared (railstatus.chat.EngineGoldenTest, EngineEvalTest).
 */
class FakeFactsTest {
    private val golden: JsonObject = Json.parseToJsonElement(GoldenData.json).jsonObject

    @Test
    fun fakeFactsLikeTheWebClient() {
        val sent = golden.getValue("sent").jsonArray.map { it.jsonPrimitive.long }
        val raw = golden.getValue("fakeRaw").jsonObject
        val compact = Json { explicitNulls = false; encodeDefaults = false }
        for (k in 1..5) {
            val f = fakeFacts(incidentFrom(sent.take(k))!!)
            val json = compact.encodeToJsonElement(IncidentFacts.serializer(), f).jsonObject
            // client.ts leaves incidentId out of the updates; the Kotlin model requires it.
            val updates = JsonArray(json.getValue("updates").jsonArray.map { u -> JsonObject(u.jsonObject - "incidentId") })
            assertEquals(raw.getValue("fake$k"), JsonObject(json + ("updates" to updates)), "fakeFacts after $k updates")
        }
    }
}
