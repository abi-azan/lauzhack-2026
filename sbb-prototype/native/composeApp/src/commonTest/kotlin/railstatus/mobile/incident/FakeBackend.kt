package railstatus.mobile.incident

import kotlinx.coroutines.CoroutineScope
import railstatus.model.CheckIn
import railstatus.model.CheckInRequest
import railstatus.model.CheckInResponse
import railstatus.model.IncidentFacts
import railstatus.model.Lang
import railstatus.model.Station
import railstatus.model.Stop
import railstatus.model.Train

/** A scripted backend for the store tests. */
internal class FakeBackend : PassengerBackend {
    var probeOk = true
    var reachable = true
    /** How long postCheckIn takes, to test what happens while one is in flight. */
    var checkInDelayMs = 0L
    var incident: IncidentFacts? = null
    var chatResult: ChatPost = ChatPost.Failed(0)

    val train = Train(
        id = "IR90-1734", product = "IR", line = "IR 90", number = "1734", direction = "Brig",
        stops = listOf(
            Stop(Station("8501008", "Genève"), dep = "17:15"),
            Stop(Station("8501120", "Lausanne"), arr = "17:53", dep = "17:56"),
            Stop(Station("8501506", "Sion"), arr = "18:50"),
        ),
    )

    val probes = mutableListOf<String>()
    val checkIns = mutableListOf<Pair<String, CheckInRequest>>()
    val deleted = mutableListOf<String>()
    val chats = mutableListOf<Triple<String, String, Lang>>()
    val sockets = mutableListOf<Socket>()
    private var tokens = 0

    class Socket(val api: String, val token: String, val on: PassengerSocketListener) {
        var closed = false
    }

    val openSockets get() = sockets.filter { !it.closed }

    override suspend fun probe(api: String): Boolean { probes.add(api); return probeOk }

    override suspend fun getTrain(api: String, id: String): Train {
        if (!reachable) throw BackendException(0, "offline")
        if (id != train.id) throw BackendException(404, "GET $api/api/trains/$id: 404")
        return train
    }

    override suspend fun postCheckIn(api: String, body: CheckInRequest): CheckInResponse {
        if (!reachable) throw BackendException(0, "offline")
        checkInDelayMs.takeIf { it > 0 }?.let { kotlinx.coroutines.delay(it) }
        checkIns.add(api to body)
        val token = "t${++tokens}"
        return CheckInResponse(
            checkIn = CheckIn(passengerId = "p$tokens", token = token, trainId = body.trainId, car = body.car, lang = body.lang, method = body.method, destinationId = body.destinationId, at = "2026-09-17T15:00:00Z"),
            train = train,
            incident = incident,
        )
    }

    override suspend fun deleteCheckIn(api: String, token: String) { deleted.add(token) }

    override suspend fun postChat(api: String, token: String, text: String, lang: Lang): ChatPost {
        chats.add(Triple(token, text, lang))
        return chatResult
    }

    override fun openPassengerSocket(scope: CoroutineScope, api: String, token: String, on: PassengerSocketListener): () -> Unit {
        val s = Socket(api, token, on)
        sockets.add(s)
        return { s.closed = true }
    }
}
