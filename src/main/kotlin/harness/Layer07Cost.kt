package harness

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.features.eventHandler.feature.EventHandlerConfig
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.tokenizer.SimpleRegexBasedTokenizer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Layer 7, our code on a Koog event hook: count the tokens before each model call, and stop the run over budget. */
class TokenBudget(private val limit: Int) {
    class Exceeded(message: String) : RuntimeException(message)
    private val tokenizer = SimpleRegexBasedTokenizer()
    var used = 0
        private set

    fun install(config: EventHandlerConfig) = config.onLLMCallStarting { ctx ->
        used += ctx.prompt.messages.sumOf { tokenizer.countTokens(it.toString()) }
        if (used > limit) throw Exceeded("token budget of $limit exceeded after $used tokens")
    }
}

/** One small agent per ticket, each with its own budget. Agents don't share history. */
fun newTicketAgent(budget: TokenBudget) = AIAgent(
    promptExecutor = if (llm.isScripted) executorFor(ScriptedClient()) else llm.executor,
    agentConfig = supportConfig(),
    strategy = supportStrategy,
    toolRegistry = supportTools(Shop(), readOnly("CS")),
) {
    handleEvents { budget.install(this) }
}

suspend fun layer7(capacityWithRealModel: Boolean = false) {
    heading("Layer 7 · Cost and capacity", "Keeping token spending and load within limits.")
    // The noisy-tracking ticket from layer 5, without compression.
    val budget = TokenBudget(1_500)
    val agent = AIAgent(
        promptExecutor = llm.executor,
        agentConfig = supportConfig(),
        strategy = supportStrategy,
        toolRegistry = supportTools(Shop(), readOnly("CS-7"), verboseTracking = true),
    ) {
        handleEvents { budget.install(this) }
    }
    try {
        agentSays(agent.run(customerMessage))
    } catch (e: TokenBudget.Exceeded) {
        println("  Stopped: ${e.message}")
    }

    // Capacity: 25 tickets at once. With a real model that's 25 agents' worth of calls, so it needs a yes.
    if (!llm.isScripted && !capacityWithRealModel) {
        println("  Skipped the 25 tickets: they make real model calls. Set HARNESS_CAPACITY=yes to run them.")
        return
    }
    val tickets = (1..25).map { "CS-${1000 + it}" }
    val started = System.nanoTime()
    val results = coroutineScope {
        tickets.map { id ->
            async { val ticketBudget = TokenBudget(5_000); newTicketAgent(ticketBudget).run("$customerMessage ($id)") to ticketBudget.used }
        }.awaitAll()
    }
    println("  %d tickets in %.1f s, %,d tokens in total.".format(results.size, (System.nanoTime() - started) / 1e9, results.sumOf { it.second }))
}
