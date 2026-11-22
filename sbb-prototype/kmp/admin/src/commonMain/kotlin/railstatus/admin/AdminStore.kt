package railstatus.admin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import railstatus.model.*

class AdminStore(private val api: AdminApi, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(AdminState())
    val state: StateFlow<AdminState> = _state

    fun start() {
        scope.launch {
            api.events { connected ->
                _state.update { it.copy(connected = connected) }
                if (connected) reload() // catch up on anything missed while disconnected
            }.collect { ev ->
                _state.update { reduce(it, ev) }
                if (ev is Reset) reload()
            }
        }
    }

    private suspend fun reload() {
        try {
            val incidents = api.incidents()
            val templates = api.templates()
            val trains = api.trains()
            val stations = api.stations()
            val questions = api.questions().associate { it.incidentId to it.clusters }
            // Rebuild the ticker history from the incidents, so a page refresh doesn't start empty.
            val history = incidents.flatMap { inc -> inc.ticker.map { TickerEntry(it.ts, it, inc.facts.id) } }
                .sortedByDescending { it.event.ts }.take(TICKER_LIMIT)
            _state.update {
                it.copy(
                    incidents = incidents, templates = templates, trains = trains, stations = stations,
                    questions = questions, error = null,
                    ticker = (it.ticker + history).distinctBy { e -> e.event.id }.sortedByDescending { e -> e.event.ts }.take(TICKER_LIMIT),
                )
            }
        } catch (e: Exception) {
            fail(e)
        }
    }

    fun select(id: String) = _state.update { it.copy(selectedId = id) }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun fail(e: Exception) = _state.update { it.copy(error = e.message ?: e.toString()) }

    /** Renders a template server-side. Returns null and shows an error if it fails. */
    suspend fun draft(incidentId: String, req: DraftRequest): PublishUpdateRequest? =
        try {
            api.draft(incidentId, req)
        } catch (e: Exception) {
            fail(e); null
        }

    suspend fun aiDraft(incidentId: String): PublishUpdateRequest? =
        try {
            api.aiDraft(incidentId)
        } catch (e: Exception) {
            fail(e); null
        }

    suspend fun publish(incidentId: String, req: PublishUpdateRequest): Boolean =
        try {
            api.publish(incidentId, req); true
        } catch (e: Exception) {
            fail(e); false
        }

    /** Returns the new incident's id, or null on failure. */
    suspend fun create(req: CreateIncidentRequest): String? =
        try {
            val inc = api.createIncident(req)
            _state.update { reduce(it, IncidentChanged(inc.detectedAt, inc)).copy(selectedId = inc.facts.id) }
            inc.facts.id
        } catch (e: Exception) {
            fail(e); null
        }

    /** The demo strip: null when the server has no demo control (an older server). */
    suspend fun demo(): DemoStatus? = runCatching { api.demo() }.getOrNull()
    suspend fun demoBeat(n: Int): DemoStatus? = try { api.demoBeat(n) } catch (e: Exception) { fail(e); null }
    suspend fun resetDemo(): Boolean = try { api.reset(); reload(); true } catch (e: Exception) { fail(e); false }

    suspend fun fontBytes(url: String): ByteArray? = runCatching { api.bytes(url) }.getOrNull()
}
