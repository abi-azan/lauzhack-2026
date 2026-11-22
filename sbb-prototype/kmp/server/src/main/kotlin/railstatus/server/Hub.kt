package railstatus.server

import io.ktor.websocket.*
import railstatus.model.RailJson
import railstatus.model.WsEvent
import java.util.concurrent.ConcurrentHashMap

/** Open WebSocket sessions, by audience. */
class Hub {
    private val passengers = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()
    private val admins: MutableSet<WebSocketSession> = ConcurrentHashMap.newKeySet()
    private val status: MutableSet<WebSocketSession> = ConcurrentHashMap.newKeySet()

    fun addPassenger(token: String, s: WebSocketSession) {
        passengers.getOrPut(token) { ConcurrentHashMap.newKeySet() }.add(s)
    }

    fun removePassenger(token: String, s: WebSocketSession) {
        passengers[token]?.remove(s)
    }

    fun addAdmin(s: WebSocketSession) = admins.add(s)
    fun removeAdmin(s: WebSocketSession) = admins.remove(s)
    fun addStatus(s: WebSocketSession) = status.add(s)
    fun removeStatus(s: WebSocketSession) = status.remove(s)

    /** Returns how many of the passenger's sessions got it. */
    suspend fun toPassenger(token: String, e: WsEvent): Int = sendAll(passengers[token].orEmpty(), e)

    suspend fun toAdmins(e: WsEvent) = sendAll(admins, e)
    suspend fun toStatus(e: WsEvent) = sendAll(status, e)

    suspend fun toEveryone(e: WsEvent) {
        toAdmins(e)
        toStatus(e)
        passengers.values.forEach { sendAll(it, e) }
    }

    private suspend fun sendAll(sessions: Collection<WebSocketSession>, e: WsEvent): Int {
        if (sessions.isEmpty()) return 0
        val text = RailJson.encodeToString(WsEvent.serializer(), e)
        return sessions.count { s -> runCatching { s.send(Frame.Text(text)) }.isSuccess }
    }
}
