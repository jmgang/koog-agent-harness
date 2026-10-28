package harness

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.features.eventHandler.feature.handleEvents

/** Layer 5: the support graph, plus one node that summarizes the history when it gets too long. */
val withCompression = strategy<String, String>("support-compressing") {
    val think by nodeLLMRequest()
    val act by nodeExecuteTools()
    val report by nodeLLMSendToolResults()
    val compress by nodeLLMCompressHistory<ReceivedToolResults>()
    edge(nodeStart forwardTo think)
    edge(think forwardTo act onToolCalls { true })
    edge(think forwardTo nodeFinish onTextMessage { true })
    // When the conversation gets too long, summarize it before the next model call.
    edge(act forwardTo compress onCondition { _ -> llm.readSession { prompt.messages.sumOf { it.toString().length } > 3_000 } })
    edge(act forwardTo report)
    edge(compress forwardTo report)
    edge(report forwardTo act onToolCalls { true })
    edge(report forwardTo nodeFinish onTextMessage { true })
}

suspend fun layer5() {
    heading("Layer 5 · Context", "Managing what's in the conversation the model sees on every call.")
    val trace = Trace()
    val agent = AIAgent(
        promptExecutor = llm.executor,
        agentConfig = supportConfig(),
        strategy = withCompression,
        // The tracking returns 60 extra "in transit" scans.
        toolRegistry = supportTools(Shop(), readOnly("CS-5"), verboseTracking = true),
    ) {
        handleEvents { trace.install(this, live = false) }
    }
    val answer = agent.run(customerMessage)
    println("  Characters sent to the model on each call: " + trace.promptSizes.joinToString(" -> ") { "%,d".format(it) })
    println("  Summaries made: ${trace.summaries}")
    agentSays(answer)
}
