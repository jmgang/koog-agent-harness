package harness

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.kotlinx.KotlinxSerializer
import java.time.LocalTime
import ai.koog.agents.core.tools.Tool as KoogTool

/** Our code: the harness, not the model, decides what needs a human. Every decision is logged. */
class ApprovalGate(private val isApproved: () -> Boolean) : Guard {
    val log = java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>()
    override fun around(action: String, perform: () -> String): String {
        val approved = isApproved()
        log += mapOf("time" to LocalTime.now().withNano(0).toString(), "action" to action, "decision" to if (approved) "approved" else "waiting for approval")
        return if (approved) perform() else "[pending-approval] '$action' needs a human's approval before it can run."
    }
}

/** Our code: escalate to a human after repeated failures instead of letting the agent keep trying. */
class Escalation(private val maxFailures: Int = 2) : Guard {
    var failures = 0
        private set
    override fun around(action: String, perform: () -> String): String {
        if (failures >= maxFailures) return "[escalated] '$action' failed $failures times; handed the ticket to a human agent."
        return perform().also { if (it.startsWith("[refund-failed]")) failures++ }
    }
}

/** A mocked call to one of the agent's tools, for Koog's mock executor. */
fun ToolRegistry.mockCall(tool: String, orderId: String = "1042"): Pair<KoogTool<Any?, Any?>, Any?> {
    @Suppress("UNCHECKED_CAST")
    val koogTool = getTool(tool) as KoogTool<Any?, Any?>
    return koogTool to koogTool.decodeArgs(JSONObject(mapOf("orderId" to JSONPrimitive(orderId))), KotlinxSerializer())
}

fun testAgent(model: PromptExecutor, tools: ToolRegistry, maxIterations: Int = 20) =
    AIAgent(promptExecutor = model, agentConfig = supportConfig(maxIterations), strategy = supportStrategy, toolRegistry = tools)

data class TestResult(val test: String, val passed: Boolean, val detail: String)

suspend fun harnessTest(name: String, body: suspend () -> Pair<Boolean, String>): TestResult =
    try { body().let { (ok, detail) -> TestResult(name, ok, detail) } } catch (e: Throwable) { TestResult(name, false, "threw ${e::class.simpleName}") }

/** A model that tries to refund immediately. The approval gate must hold. */
suspend fun testNeverRefundsWithoutApproval() = harnessTest("Never refunds without approval") {
    val shop = Shop()
    val tools = supportTools(shop, readAndRefund("T-1"), allowHighRisk = true, guard = ApprovalGate { false })
    val (refund, args) = tools.mockCall("issueRefund")
    val model = getMockExecutor {
        mockLLMToolCall(refund, args) onRequestContains "money back"
        mockLLMAnswer("Waiting for approval.").asDefaultResponse
    }
    testAgent(model, tools).run(customerMessage)
    (shop.refunds == 0) to "refunds = ${shop.refunds}"
}

/** A model that keeps refunding while the payment provider declines. Escalation must stop it. */
suspend fun testEscalatesAfterTwoFailures() = harnessTest("Escalates after two failed refunds") {
    val shop = Shop(refundsWork = false)
    val escalation = Escalation(maxFailures = 2)
    val tools = supportTools(shop, readAndRefund("T-2"), allowHighRisk = true, guard = ApprovalGate { true } then escalation)
    val (refund, args) = tools.mockCall("issueRefund")
    val model = getMockExecutor {
        mockLLMToolCall(refund, args) onCondition { "[escalated]" !in it }
        mockLLMAnswer("Handed to a human agent.").asDefaultResponse
    }
    testAgent(model, tools).run(customerMessage)
    (shop.refunds == 2 && escalation.failures == 2) to "refunds = ${shop.refunds}, failures = ${escalation.failures}"
}

/** A model that never stops calling tools. The step limit must stop it. */
suspend fun testStopsALoopingAgent() = harnessTest("Stops a looping agent") {
    val tools = supportTools(Shop(), readOnly("T-3"))
    val (tracking, args) = tools.mockCall("trackDelivery")
    val model = getMockExecutor { mockLLMToolCall(tracking, args) onCondition { true } }
    val stopped = try { testAgent(model, tools, maxIterations = 6).run(customerMessage); false }
        catch (e: AIAgentMaxNumberOfIterationsReachedException) { true }
    stopped to if (stopped) "stopped after 6 steps" else "kept running"
}

suspend fun harnessTests() = listOf(testNeverRefundsWithoutApproval(), testEscalatesAfterTwoFailures(), testStopsALoopingAgent())

suspend fun layer12() {
    heading("Layer 12 · Evaluation and testing", "Proving the harness's rules still hold when the prompt, the model or the code changes.")
    val results = harnessTests()
    results.forEach { println("  ${if (it.passed) "pass" else "FAIL"}  %-36s %s".format(it.test, it.detail)) }
    println("  ${results.count { it.passed }} of ${results.size} harness tests pass. No real model calls. Also run them with ./gradlew test")
}
