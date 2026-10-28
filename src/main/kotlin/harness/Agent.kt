package harness

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.features.eventHandler.feature.EventHandlerConfig
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.prompt

val customerMessage = "Where's my order #1042? It was due a week ago. If it's lost, I want my money back."

val systemPrompt = """
    You are a customer service assistant for an online shop. Help customers with the tools:
    look up the order, track the delivery, and check the help center for the policy. If the
    policy says a refund is due and you have a refund tool, issue it. Answer in at most three sentences.
""".trimIndent()

val supportPrompt = prompt("support") { system(systemPrompt) }

/** Every agent in this demo shares the system prompt and model; only the step limit varies. */
fun supportConfig(maxIterations: Int = 30) = AIAgentConfig(supportPrompt, llm.model, maxAgentIterations = maxIterations)

/** The agent's steps as a graph: ask the model, run the tools it asks for, send back the results. */
val supportStrategy = strategy<String, String>("support") {
    val think by nodeLLMRequest()
    val act by nodeExecuteTools()
    val report by nodeLLMSendToolResults()
    edge(nodeStart forwardTo think)
    edge(think forwardTo act onToolCalls { true })
    edge(think forwardTo nodeFinish onTextMessage { true })
    edge(act forwardTo report)
    edge(report forwardTo act onToolCalls { true })
    edge(report forwardTo nodeFinish onTextMessage { true })
}

/** The agent with no harness around it yet: the model, the settings, the graph and the read tools. */
val supportAgent = AIAgent(
    promptExecutor = llm.executor,
    agentConfig = supportConfig(),
    strategy = supportStrategy,
    toolRegistry = supportTools(Shop(), readOnly("CS-0")),
) { handleEvents { Trace().install(this) } }

/** Prints each tool call as it happens, and records the size of every prompt (layer 5). */
class Trace {
    val promptSizes = java.util.concurrent.CopyOnWriteArrayList<Int>()
    var summaries = 0
        private set

    fun install(config: EventHandlerConfig, live: Boolean = true) = with(config) {
        onLLMCallStarting { ctx -> promptSizes += ctx.prompt.messages.sumOf { it.toString().length } }
        onLLMCallCompleted { ctx -> if (ctx.tools.isEmpty()) summaries++ }
        onToolCallStarting { ctx ->
            val args = Regex("\"(\\w+)\"\\s*:\\s*(\"[^\"]*\"|[^,}]+)").findAll(ctx.toolArgs.toString())
                .joinToString(", ") { m -> "${m.groupValues[1]} = ${m.groupValues[2]}" }
            if (live) println("  tool  ${ctx.toolName}($args)")
        }
        onToolCallCompleted { ctx ->
            if (live) println("        -> ${ctx.toolResult.toString().removePrefix("\"").lineSequence().first().removeSuffix("\"").take(110)}")
        }
    }
}

fun heading(title: String, what: String) {
    println()
    println("== $title")
    println("   $what")
    println()
}

fun agentSays(answer: String) = println("  Agent: $answer")
