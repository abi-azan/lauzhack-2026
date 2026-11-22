@file:OptIn(ExperimentalTime::class)

package railstatus.mobile.incident

import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import railstatus.chat.JsLocal
import railstatus.chat.jsIsoString
import railstatus.model.DelayRange
import railstatus.model.IncidentFacts
import railstatus.model.Lang
import railstatus.model.RailJson
import railstatus.model.Text
import railstatus.model.Train
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** personal.ts and live.ts's mapping, against what the TypeScript gave (GoldenData, made under TZ=Europe/Zurich). */
class WebParityTest {
    // 22 Nov, reviewed: the Supersaver line is shorter ("…: you may take another train."). personal.ts is gone, so the
    // golden can't be regenerated; the change is applied to it here.
    private val golden = Json.parseToJsonElement(
        GoldenData.json
            .replace(Regex("Supersaver: the train binding is lifted, so you may take another train to [^.\"]*\\."), "Supersaver: you may take another train.")
            .replace(Regex("Sparbillett: Die Zugbindung ist aufgehoben, Sie dürfen einen anderen Zug nach [^.\"]* nehmen\\."), "Sparbillett: Sie dürfen einen anderen Zug nehmen.")
            .replace(Regex("Billet dégriffé : l’obligation de prendre ce train est levée, vous pouvez prendre un autre train pour [^.\"]*\\."), "Billet dégriffé : vous pouvez prendre un autre train.")
            .replace(Regex("Biglietto risparmio: il vincolo al treno è sospeso, potete prendere un altro treno per [^.\"]*\\."), "Biglietto risparmio: potete prendere un altro treno."),
    ).jsonObject
    private val zurich = TimeZone.of("Europe/Zurich")

    @BeforeTest
    fun localTime() { JsLocal.zone = zurich }

    private fun lang(s: String) = Lang.entries.first { it.name.lowercase() == s }
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun textJson(t: Text) = JsonObject(t.entries.associate { it.key.name.lowercase() to JsonPrimitive(it.value) })
    private fun iso(t: Instant) = JsonPrimitive(jsIsoString(t.toEpochMilliseconds()))

    @Test
    fun forYouLikePersonalTs() {
        val sent = golden.getValue("sent").jsonArray.map { it.jsonPrimitive.long }
        val checkedIn = Instant.fromEpochMilliseconds(golden.getValue("checkedIn").jsonPrimitive.long)
        val train = fakeTrain(checkedIn)
        for (c in golden.getValue("personal").jsonArray.map { it.jsonObject }) {
            val persona = PERSONAS.getValue(PersonaId.entries.first { RailJson.encodeToString(PersonaId.serializer(), it) == "\"${c.str("persona")}\"" })
            val dest = destinationOf(persona, c.str("asked"))
            assertEquals(c.str("dest"), dest, "destinationOf($c)")
            val k = c.getValue("k").jsonPrimitive.content.toInt()
            val latest = incidentFrom(sent.take(k))!!.latest
            val got = JsonArray(forYou(latest, persona, dest, train, zurich).map {
                when (it) {
                    is ForYou.Arrival -> buildJsonObject { put("kind", "arrival"); put("text", textJson(it.text)) }
                    is ForYou.TicketLine -> buildJsonObject { put("kind", "ticket"); put("text", textJson(it.text)) }
                    ForYou.Ask -> buildJsonObject { put("kind", "ask") }
                }
            })
            if (latest.delay == null) {
                // personal.ts said nothing without a delay; since T18 #4 the ticket line shows anyway, and only it.
                assertEquals(JsonArray(emptyList()), c.getValue("out"), "golden forYou($c)")
                assertTrue(got.all { it.jsonObject.str("kind") == "ticket" }, "forYou($c) without a delay: $got")
                assertEquals(if (persona.ticket == null) 0 else 1, got.size, "forYou($c) without a delay: $got")
            } else {
                assertEquals(c.getValue("out"), got, "forYou($c)")
            }
        }
        for (c in golden.getValue("names").jsonArray.map { it.jsonObject }) {
            assertEquals(c.str("out"), stationName(c.str("st")!!, lang(c.str("l")!!)))
        }
    }

    private fun updateJson(u: PublishedUpdate): JsonElement = JsonObject(buildMap {
        put("stage", JsonPrimitive(u.stage.name.lowercase()))
        put("title", textJson(u.title))
        put("body", textJson(u.body))
        put("index", JsonPrimitive(u.index))
        put("at", iso(u.at))
        u.nextBy?.let { put("nextBy", iso(it)) }
        u.delay?.let { put("delay", RailJson.encodeToJsonElement(DelayRange.serializer(), it)) }
    })

    @Test
    fun mapIncidentLikeLiveTs() {
        for (c in golden.getValue("mapped").jsonArray.map { it.jsonObject }) {
            val f = RailJson.decodeFromJsonElement(IncidentFacts.serializer(), c.getValue("facts"))
            val got = mapIncident(f)?.let { inc ->
                buildJsonObject {
                    put("id", inc.id); put("trainId", inc.trainId); put("section", inc.section)
                    put("stage", inc.stage.name.lowercase())
                    put("updates", JsonArray(inc.updates.map(::updateJson)))
                    put("latest", updateJson(inc.latest))
                }
            } ?: JsonNull
            assertEquals(c.getValue("out"), got, "mapIncident(${c.str("key")})")
        }
        assertEquals(null, mapIncident(null))
    }

    @Test
    fun mapTrainLikeLiveTs() {
        val now = Instant.fromEpochMilliseconds(golden.getValue("mapNow").jsonPrimitive.long)
        for (c in golden.getValue("mappedTrains").jsonArray.map { it.jsonObject }) {
            val t = mapTrain(RailJson.decodeFromJsonElement(Train.serializer(), c.getValue("train")), now, zurich)
            val got = buildJsonObject {
                put("id", t.id); put("product", t.product); put("line", t.line); put("number", t.number)
                put("direction", t.direction); put("live", t.live)
                put("stops", JsonArray(t.stops.map { s ->
                    buildJsonObject { put("station", s.station); s.stationId?.let { put("stationId", it) }; put("planned", iso(s.planned)) }
                }))
            }
            assertEquals(c.getValue("out"), got, "mapTrain(${c.getValue("train").jsonObject.str("id")})")
        }
    }
}
