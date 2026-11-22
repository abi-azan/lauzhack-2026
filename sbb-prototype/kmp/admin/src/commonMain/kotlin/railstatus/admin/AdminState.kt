package railstatus.admin

import railstatus.model.*

/** Everything the dashboard shows. Changed only through [reduce], so it can be unit-tested. */
data class AdminState(
    val connected: Boolean = false,
    val incidents: List<AdminIncident> = emptyList(),
    /** Latest ticker entries across all incidents, newest first. */
    val ticker: List<TickerEntry> = emptyList(),
    val templates: List<Template> = emptyList(),
    val trains: List<Train> = emptyList(),
    val stations: List<Station> = emptyList(),
    /** Chat question clusters by incident id. */
    val questions: Map<String, List<QuestionCluster>> = emptyMap(),
    val selectedId: String? = null,
    val error: String? = null,
) {
    val selected: AdminIncident? get() = incidents.find { it.facts.id == selectedId } ?: incidents.firstOrNull()
    val active: List<AdminIncident> get() = incidents.filter { it.facts.stage != Stage.RESOLVED }
    fun train(id: String) = trains.find { it.id == id }
}

const val TICKER_LIMIT = 200

/** Apply one server event. Pure: same input, same output. */
fun reduce(state: AdminState, event: WsEvent): AdminState = when (event) {
    is IncidentChanged -> {
        val inc = event.incident
        val exists = state.incidents.any { it.facts.id == inc.facts.id }
        val list = if (exists) state.incidents.map { if (it.facts.id == inc.facts.id) inc else it }
        else listOf(inc) + state.incidents
        // A brand-new incident grabs the focus, like a pager going off.
        state.copy(incidents = list, selectedId = if (exists) state.selectedId else inc.facts.id)
    }
    is TickerEntry -> state.copy(ticker = (listOf(event) + state.ticker).take(TICKER_LIMIT))
    is QuestionsChanged -> state.copy(questions = state.questions + (event.incidentId to event.clusters))
    is Reset -> state.copy(incidents = emptyList(), ticker = emptyList(), questions = emptyMap(), selectedId = null)
    else -> state
}

/** Passengers on board across an incident's trains. */
val AdminIncident.passengers: Int get() = passengersByTrain.values.sum()

/** "Bern – Thun" */
fun Section?.label(): String = this?.let { "${it.from.name} – ${it.to.name}" } ?: "Unknown section"

/** "2026-09-01T04:06:07+02:00" → "04:06" */
fun hhmm(iso: String?): String = iso?.takeIf { it.length >= 16 }?.substring(11, 16) ?: "–"
