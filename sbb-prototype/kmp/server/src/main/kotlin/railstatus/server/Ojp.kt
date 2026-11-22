package railstatus.server

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SBB's journey planner, OJP 2.0 (opentransportdata.swiss): real trips between two stations, with
 * real walking transfers (e.g. platform → bus stop) and every public transport mode. Used by
 * [NextMovePlanner] to find the ways around a closed section; see docs/next-move research on the
 * `research/next-move` branch for the API facts.
 */
interface OjpApi {
    suspend fun trips(q: TripQuery): List<OjpTrip>
}

data class TripQuery(
    /** Station ids, "8501030". */
    val from: String,
    val to: String,
    val depart: Instant,
    /** Stations the trip must not pass: the closed section's interior. */
    val notVia: List<String> = emptyList(),
    val results: Int = 6,
    /** Only rail: used to learn the stations along the line. */
    val railOnly: Boolean = false,
    /** No rail: the way around a closed section. */
    val noRail: Boolean = false,
)

data class OjpCall(val stationId: String?, val name: String, val time: Instant?)

data class OjpLeg(
    /** OJP's PtMode ("rail", "bus", "tram", "metro", "water", …) or "walk" for transfers. */
    val mode: String,
    /** "R3", "724", "EV1"; null for walks. */
    val line: String?,
    /** Board, the intermediate stops, alight; just start and end for walks. */
    val calls: List<OjpCall>,
) {
    val board get() = calls.first()
    val alight get() = calls.last()
}

data class OjpTrip(val start: Instant, val end: Instant, val legs: List<OjpLeg>)

class OjpClient(
    private val key: String,
    private val endpoint: String = "https://api.opentransportdata.swiss/ojp20",
    timeoutMs: Long = 3_000,
) : OjpApi {
    private val http = HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = timeoutMs }
        expectSuccess = true
    }

    override suspend fun trips(q: TripQuery): List<OjpTrip> {
        val res = http.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $key")
            header(HttpHeaders.UserAgent, "railstatus")
            contentType(ContentType.Application.Xml)
            setBody(Ojp.tripRequest(q))
        }
        return Ojp.parseTrips(res.bodyAsText())
    }
}

object Ojp {
    fun tripRequest(q: TripQuery, at: Instant = Instant.now()): String {
        val ts = at.truncatedTo(ChronoUnit.SECONDS)
        val dep = q.depart.truncatedTo(ChronoUnit.SECONDS)
        val notVia = q.notVia.joinToString("") { "<NotVia><StopPlaceRef>$it</StopPlaceRef></NotVia>" }
        val modes = when {
            q.railOnly -> "<ModeAndModeOfOperationFilter><Exclude>false</Exclude><PtMode>rail</PtMode></ModeAndModeOfOperationFilter>"
            q.noRail -> "<ModeAndModeOfOperationFilter><Exclude>true</Exclude><PtMode>rail</PtMode></ModeAndModeOfOperationFilter>"
            else -> ""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri" version="2.0">
  <OJPRequest><siri:ServiceRequest>
    <siri:RequestTimestamp>$ts</siri:RequestTimestamp>
    <siri:RequestorRef>railstatus_prod</siri:RequestorRef>
    <OJPTripRequest>
      <siri:RequestTimestamp>$ts</siri:RequestTimestamp>
      <Origin><PlaceRef><StopPlaceRef>${q.from}</StopPlaceRef><Name><Text>${q.from}</Text></Name></PlaceRef><DepArrTime>$dep</DepArrTime></Origin>
      <Destination><PlaceRef><StopPlaceRef>${q.to}</StopPlaceRef><Name><Text>${q.to}</Text></Name></PlaceRef></Destination>
      $notVia
      <Params>$modes<NumberOfResults>${q.results}</NumberOfResults><UseRealtimeData>explanatory</UseRealtimeData><IncludeSituationsContext>false</IncludeSituationsContext><IncludeIntermediateStops>true</IncludeIntermediateStops></Params>
    </OJPTripRequest>
  </siri:ServiceRequest></OJPRequest>
</OJP>"""
    }

    /** Our station id from an OJP stop reference: "ch:1:sloid:3404:2:3" → "8503404"; "8503404" stays. */
    fun stationId(ref: String?): String? {
        val r = ref?.trim().orEmpty()
        if (r.startsWith("ch:1:sloid:")) return r.removePrefix("ch:1:sloid:").substringBefore(':').toIntOrNull()?.let { "85" + it.toString().padStart(5, '0') }
        return r.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    fun parseTrips(xml: String): List<OjpTrip> {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        return doc.documentElement.all("Trip").mapNotNull { trip ->
            val start = trip.child("StartTime")?.instant() ?: return@mapNotNull null
            val end = trip.child("EndTime")?.instant() ?: return@mapNotNull null
            val legs = trip.children("Leg").mapNotNull { leg ->
                leg.child("TimedLeg")?.let(::timedLeg)
                    ?: (leg.child("TransferLeg") ?: leg.child("ContinuousLeg"))?.let(::walkLeg)
            }
            OjpTrip(start, end, legs)
        }
    }

    private fun timedLeg(e: Element): OjpLeg? {
        val board = e.child("LegBoard")?.let { call(it, "ServiceDeparture") } ?: return null
        val alight = e.child("LegAlight")?.let { call(it, "ServiceArrival") } ?: return null
        val mid = e.children("LegIntermediate").map { call(it, "ServiceDeparture", "ServiceArrival") }
        val service = e.child("Service")
        val mode = service?.child("Mode")?.child("PtMode")?.text() ?: "rail"
        val line = service?.child("PublishedServiceName")?.text() ?: service?.child("Mode")?.child("ShortName")?.text()
        return OjpLeg(mode, line, listOf(board) + mid + alight)
    }

    private fun walkLeg(e: Element): OjpLeg? {
        val start = e.child("LegStart") ?: return null
        val end = e.child("LegEnd") ?: return null
        fun place(p: Element) = OjpCall(stationId(p.child("StopPointRef")?.text() ?: p.child("StopPlaceRef")?.text()), p.child("Name")?.text().orEmpty(), null)
        return OjpLeg("walk", null, listOf(place(start), place(end)))
    }

    private fun call(e: Element, vararg timeElems: String): OjpCall {
        val time = timeElems.firstNotNullOfOrNull { t -> e.child(t)?.let { it.child("EstimatedTime") ?: it.child("TimetabledTime") }?.instant() }
        return OjpCall(stationId(e.child("StopPointRef")?.text()), e.child("StopPointName")?.text().orEmpty(), time)
    }

    // Namespace-agnostic DOM helpers: OJP mixes the ojp and siri namespaces.
    private fun Element.children(name: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.localName == name }
    private fun Element.child(name: String): Element? = children(name).firstOrNull()
    private fun Element.all(name: String): List<Element> =
        getElementsByTagNameNS("*", name).let { nl -> (0 until nl.length).map { nl.item(it) as Element } }
    private fun Element.text(): String? = (child("Text") ?: this).textContent?.trim()?.takeIf { it.isNotEmpty() }
    private fun Element.instant(): Instant? = runCatching { java.time.OffsetDateTime.parse(textContent.trim()).toInstant() }.getOrNull()
}
