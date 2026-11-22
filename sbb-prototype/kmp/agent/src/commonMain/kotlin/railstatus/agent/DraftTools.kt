package railstatus.agent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import railstatus.model.Lang
import railstatus.nextmove.NextMove

// The draft agent's tools. They're class-based Koog tools, the multiplatform kind (annotation-based ToolSets are
// JVM-only), so they live in commonMain. Each one is a read-only view of [DraftSources].

/** Args for tools that take nothing. Koog needs a serializable type even for no arguments. */
@Serializable
class NoArgs

class TickerTool(private val sources: DraftSources) : SimpleTool<NoArgs>(
    argsType = typeToken<NoArgs>(),
    name = "ticker_events",
    description = "The internal control-centre ticker for this incident, oldest first: staff shorthand in French and " +
        "German, with personal data already removed. This is the main evidence for what happened.",
) {
    override suspend fun execute(args: NoArgs): String =
        sources.ticker().ifEmpty { listOf("(no ticker entries yet)") }.joinToString("\n")
}

class AffectedTrainsTool(private val sources: DraftSources) : SimpleTool<NoArgs>(
    argsType = typeToken<NoArgs>(),
    name = "affected_trains",
    description = "The trains affected by this incident, one per line, with the train id to pass to next_move.",
) {
    override suspend fun execute(args: NoArgs): String =
        sources.affectedTrains().ifEmpty { listOf("(no trains matched yet)") }.joinToString("\n")
}

class PublishedUpdatesTool(private val sources: DraftSources) : SimpleTool<NoArgs>(
    argsType = typeToken<NoArgs>(),
    name = "published_updates",
    description = "What passengers have already been told, oldest first. Don't contradict it without new evidence from the ticker.",
) {
    override suspend fun execute(args: NoArgs): String =
        sources.publishedUpdates().ifEmpty { listOf("(nothing published yet)") }.joinToString("\n")
}

@Serializable
data class NextMoveArgs(
    @property:LLMDescription("A train id from affected_trains, e.g. IR90-1734")
    val trainId: String,
)

class NextMoveTool(private val sources: DraftSources) : SimpleTool<NextMoveArgs>(
    argsType = typeToken<NextMoveArgs>(),
    name = "next_move",
    description = "The deterministic next-move suggestion a passenger on this train sees right now: stay, change, or " +
        "wait for information, with the options and their sources. It's computed from SBB's published facts and " +
        "the timetable, never by a model. Use it to keep the update consistent with what passengers see on their card. " +
        "It is our estimate, not an SBB instruction.",
) {
    override suspend fun execute(args: NextMoveArgs): String =
        sources.nextMove(args.trainId)?.let(::describe) ?: "No next move for ${args.trainId}: nothing to advise yet."
}

class RoutesAroundTool(private val sources: DraftSources) : SimpleTool<NoArgs>(
    argsType = typeToken<NoArgs>(),
    name = "routes_around_closure",
    description = "Scheduled public transport around the closed section, from the timetable or journey planner. These are " +
        "regular services, not SBB replacement buses. Only mention them as options passengers can check.",
) {
    override suspend fun execute(args: NoArgs): String =
        sources.routesAround()?.ifEmpty { null }?.joinToString("\n") ?: "No scheduled routes around the closure are known."
}

class PassengerQuestionsTool(private val sources: DraftSources) : SimpleTool<NoArgs>(
    argsType = typeToken<NoArgs>(),
    name = "passenger_questions",
    description = "What checked-in passengers are asking in the support chat right now: each question group with how " +
        "many passengers asked, most asked first (their own words are never shown). Answer the most asked ones in " +
        "the update where the ticker or the incident block states the answer. Where it doesn't, say plainly that " +
        "it isn't known yet. Never invent an answer to satisfy a question.",
) {
    override suspend fun execute(args: NoArgs): String =
        sources.passengerQuestions().ifEmpty { listOf("(no passenger questions yet)") }.joinToString("\n")
}

/** All draft tools for one incident. */
fun draftTools(sources: DraftSources) = listOf(
    TickerTool(sources), AffectedTrainsTool(sources), PublishedUpdatesTool(sources),
    NextMoveTool(sources), RoutesAroundTool(sources), PassengerQuestionsTool(sources),
)

/** A compact English rendering of a [NextMove] for the model. */
internal fun describe(m: NextMove): String = buildString {
    appendLine("Decision: ${m.decision.name.lowercase()}: ${m.headline[Lang.EN] ?: ""}")
    m.why[Lang.EN]?.let { appendLine("Why: $it") }
    appendLine("Destination: ${m.destination.name}${if (m.destinationAssumed) " (assumed)" else ""}")
    m.decideBy?.let { appendLine("Decide by: $it") }
    m.options.forEach { o ->
        val arrive = listOfNotNull(o.arriveEarliest, o.arriveLatest).distinct().joinToString("–").ifEmpty { "unknown" }
        appendLine("- [${o.source.name.lowercase()}] ${o.title[Lang.EN] ?: o.id}: leave ${o.departAt ?: "-"}, arrive $arrive")
    }
}.trimEnd()
