package harness

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.agents.features.eventHandler.feature.handleEvents

/** Layer 1: a step limit ends the run, whatever the model thinks. */
val stepLimit = AIAgentConfig(supportPrompt, llm.model, maxAgentIterations = 8)

suspend fun layer1() {
    heading("Layer 1 · Scope and control flow", "Deciding which steps the agent takes, and when it must stop.")
    // A model that keeps re-checking the tracking. With the scripted model it never answers.
    val wanderer = AIAgent(
        promptExecutor = if (llm.isScripted) executorFor(ScriptedClient(wander = true)) else llm.executor,
        agentConfig = stepLimit,
        strategy = supportStrategy,
        toolRegistry = supportTools(Shop(), readOnly("CS-1")),
    ) {
        handleEvents { Trace().install(this) }
    }
    try {
        agentSays(wanderer.run(customerMessage))
    } catch (e: AIAgentMaxNumberOfIterationsReachedException) {
        println("  Stopped by the step limit (8) instead of looping forever.")
    }
}
