package railstatus.agent

import railstatus.nextmove.NextMove

/**
 * What the draft agent may look at, for one incident. The server implements it.
 *
 * Rules for implementers:
 * - Everything is read-only. A tool call never changes an incident, a check-in or a published update.
 * - Personal data is scrubbed before it gets here (the server's `Pii.scrub`). The model never sees a staff name or phone number.
 * - [nextMove] and [routesAround] are the same deterministic functions the passenger's card uses. The agent can
 *   only *mention* their result. The card itself never comes from the agent.
 */
interface DraftSources {
    /** One short block: the time now, the line, the location, when it was detected, the published stage, the source system's cause. */
    suspend fun incidentSummary(): String

    /** The internal control-centre ticker, oldest first, with personal data scrubbed. One line per entry: "HH:MM [SOURCE] text". */
    suspend fun ticker(): List<String>

    /** The affected trains, one per line: "<trainId>: IR 90 1734 to Lausanne, now near Allaman". */
    suspend fun affectedTrains(): List<String>

    /** What passengers have already been told, oldest first: "HH:MM title: body" (English). */
    suspend fun publishedUpdates(): List<String>

    /** The next move a passenger on [trainId] gets right now (`railstatus.nextmove.nextMove`), or null when there's nothing to advise. */
    suspend fun nextMove(trainId: String): NextMove?

    /**
     * Scheduled public transport around the closed section, one route per line. Today this is the corridor's detours;
     * with T14 it's OJP (routes from the open journey planner, key on the server). Null when nothing is known.
     */
    suspend fun routesAround(): List<String>?

    /**
     * What checked-in passengers are asking in the support chat, one question group per line: its label and how many
     * passengers asked, e.g. "Connection in Lausanne: 12 passengers". Never their own words. Empty when nobody asked.
     */
    suspend fun passengerQuestions(): List<String> = emptyList()
}
