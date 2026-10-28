package harness

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

suspend fun layer11() {
    heading("Layer 11 · Observability and audit", "Recording every step the agent took, and why.")
    val spans = InMemorySpanExporter.create()
    val agent = AIAgent(
        promptExecutor = llm.executor,
        agentConfig = supportConfig(),
        strategy = supportStrategy,
        toolRegistry = supportTools(Shop(), readAndRefund("CS-11"), true),
    ) {
        install(OpenTelemetry) {
            setServiceInfo("support-agent", "1.0")
            addSpanExporter(spans) // in production: addLangfuseExporter() or addDatadogExporter()
            setVerbose(true)       // also export prompts and tool arguments
        }
    }
    agentSays(agent.run(customerMessage))
    agent.close() // sends the last spans to the exporter

    // The exporter works in the background, so wait for the agent's own span, which ends last.
    withTimeout(10.seconds) { while (spans.finishedSpanItems.none { it.name.startsWith("invoke_agent") }) delay(50) }

    println()
    println("  OpenTelemetry spans, oldest first (Koog also exports one span per graph node; they're left out here):")
    spans.finishedSpanItems
        .filter { it.name.startsWith("invoke_agent") || it.name.startsWith("chat") || it.name.startsWith("execute_tool") }
        .sortedBy { it.startEpochNanos }
        .forEach { s ->
            val a = s.attributes.asMap().mapKeys { it.key.key }
            val detail = when (a["gen_ai.operation.name"]) {
                "invoke_agent" -> "the whole run"
                "execute_tool" -> "arguments ${a["gen_ai.tool.call.arguments"]}"
                else -> "tokens in ${a["gen_ai.usage.input_tokens"]}, out ${a["gen_ai.usage.output_tokens"]}"
            }
            val name = if (s.name.startsWith("execute_tool")) s.name else s.name.substringBefore(" ")
            println("  %-32s %6d ms  %s".format(name, (s.endEpochNanos - s.startEpochNanos) / 1_000_000, detail))
        }
}
