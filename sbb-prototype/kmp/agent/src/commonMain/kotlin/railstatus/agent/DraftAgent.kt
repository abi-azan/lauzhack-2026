package railstatus.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OpenRouterLLMProvider
import ai.koog.prompt.message.MessagePart
import kotlinx.serialization.Serializable

/**
 * The status-update draft agent, in commonMain. Given one incident's [DraftSources], it reads the evidence through
 * tools (the ticker, the trains, what's already published, the next move, the routes around the closure) and
 * returns a typed [DraftOutput] in four languages. A human checks and publishes every draft. This agent never
 * publishes anything itself.
 *
 * It's the Koog version of the server's single-call `AiDrafter`. The server maps [DraftOutput] onto a
 * `PublishUpdateRequest` and runs the same output guard (personal data, `{next}` placeholder) on it.
 */
class DraftAgent(
    private val executor: PromptExecutor,
    private val model: LLModel,
    private val maxToolRounds: Int = 6,
) {
    /**
     * @param rules the writing rules for status updates: stage meanings, length, wording, the four languages,
     *   no personal data. The server passes the same text the single-call drafter uses, so both paths write alike.
     */
    suspend fun draft(sources: DraftSources, rules: String): DraftRun {
        val trace = mutableListOf<ToolCallTrace>()
        val strategy = functionalStrategy<String, DraftOutput>("railstatus-draft") { task ->
            var reply = requestLLM(task)
            var rounds = 0
            while (reply.parts.any { it is MessagePart.Tool.Call } && rounds < maxToolRounds) {
                val results = executeTools(reply)
                results.forEach { trace += ToolCallTrace(it.tool, it.toolArgs.toString(), it.output.take(TRACE_CHARS)) }
                reply = sendToolResults(results)
                rounds++
            }
            requestLLMStructured<DraftOutput>(WRITE_NOW).getOrThrow().data
        }
        val agent = AIAgent(
            promptExecutor = executor,
            agentConfig = AIAgentConfig(
                prompt = prompt("railstatus-draft") { system(rules + "\n\n" + TOOL_RULES) },
                model = model,
                maxAgentIterations = 50,
            ),
            strategy = strategy,
            toolRegistry = ToolRegistry { tools(draftTools(sources)) },
        )
        val output = agent.run("Incident:\n" + sources.incidentSummary() + "\n\n" + TASK)
        return DraftRun(output, trace)
    }

    companion object {
        private const val TRACE_CHARS = 400

        private val TOOL_RULES = """
            You have tools. Before writing, call ticker_events, published_updates and passenger_questions. Where the
            ticker answers what passengers ask most, say it in the update in plain words. Call affected_trains, and
            next_move for the most affected train, if passengers need to know what to do. Call routes_around_closure
            only if the line is closed. Base every statement on a tool result or the incident block.
            The update is SBB's official word. next_move and routes_around_closure are OUR estimate, shown to
            passengers separately on their card. Use them only to avoid contradicting that card. Never copy them
            into the update or its alternatives, and never let them change the stage. Only the ticker sets the
            stage and the alternatives (for example, replacement buses the ticker confirms).
        """.trimIndent()

        private const val TASK = "Look up what you need with the tools, then write the next status update for passengers."

        private const val WRITE_NOW = "Now write the status update as the structured JSON. Use only what the tools and the incident block said."

        /** A Koog executor for OpenRouter, the provider the rest of the server uses. */
        fun openRouterExecutor(apiKey: String): PromptExecutor = MultiLLMPromptExecutor(
            mapOf(OpenRouterLLMProvider to OpenRouterLLMClient(apiKey, httpClientFactory = KtorKoogHttpClient.Factory())),
        )

        /** Any OpenRouter model id, e.g. "openai/gpt-6-luna" (the server's `DRAFT_MODEL`). It must support tools and JSON schema. */
        fun openRouterModel(id: String) = LLModel(
            provider = OpenRouterLLMProvider,
            id = id,
            capabilities = listOf(
                LLMCapability.Temperature, LLMCapability.Tools, LLMCapability.ToolChoice,
                LLMCapability.Completion, LLMCapability.Schema.JSON.Standard,
            ),
            contextLength = 128_000,
        )
    }
}

/** One agent run: the draft and every tool call it made, in order, for the admin to see. */
data class DraftRun(val output: DraftOutput, val toolCalls: List<ToolCallTrace>)

@Serializable
data class ToolCallTrace(val tool: String, val args: String, val resultPreview: String)

/** The draft, as the model returns it. The server turns it into a `PublishUpdateRequest`. */
@Serializable
data class DraftOutput(
    @property:LLMDescription("investigating (cause unclear), identified (cause known), monitoring (a working alternative exists), resolved (traffic running again)")
    val stage: String,
    @property:LLMDescription("At most 60 characters, in each language")
    val title: Langs,
    @property:LLMDescription("2 to 3 short, calm sentences, then the next-update sentence with the literal {next} placeholder (left out when resolved)")
    val body: Langs,
    @property:LLMDescription("Concrete things passengers can do, only if the evidence supports them; else empty")
    val alternatives: List<Langs> = emptyList(),
    @property:LLMDescription("Minutes until the next update, 10 to 30")
    val nextUpdateInMin: Int = 15,
    @property:LLMDescription("The cause as the latest ticker entries state it, short, for passengers (e.g. 'a fire next to the track'); null while it is unclear")
    val cause: Langs? = null,
)

@Serializable
data class Langs(val de: String, val fr: String, val it: String, val en: String)
