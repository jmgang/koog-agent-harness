package harness

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.executeStructured
import kotlinx.serialization.Serializable

/** Our code: after the refund, ask the payment provider instead of trusting the agent's word. */
class VerifyRefund(private val shop: Shop) : Guard {
    override fun around(action: String, perform: () -> String): String {
        val result = perform()
        if (!result.startsWith("[refunded]")) return result
        return if (shop.providerConfirmsRefund) "$result [verified] the payment provider confirms the refund"
        else "$result [not-confirmed] the payment provider has no record of the refund"
    }
}

/** The summary other systems (the ticketing tool, the CRM) read. */
@Serializable
data class TicketSummary(val category: String, val resolution: String, val actions: List<String>, val refundVerified: Boolean)

/** Our code: plain rules before any other system acts on the summary. */
fun summaryProblems(summary: TicketSummary, shop: Shop) = buildList {
    if (summary.category !in setOf("delivery", "billing", "product", "account")) add("unknown category '${summary.category}'")
    if (summary.actions.isEmpty()) add("no actions listed")
    if (summary.refundVerified != shop.providerConfirmsRefund) add("claims refundVerified=${summary.refundVerified}, but the payment provider says otherwise")
}

suspend fun layer10() {
    heading("Layer 10 · Verification and guardrails", "Checking the agent's work and output instead of trusting it.")
    val shop = Shop()
    val agent = AIAgent(
        promptExecutor = llm.executor,
        agentConfig = supportConfig(),
        strategy = supportStrategy,
        toolRegistry = supportTools(shop, readAndRefund("CS-10"), allowHighRisk = true, guard = VerifyRefund(shop)),
    ) {
        handleEvents { Trace().install(this) }
    }
    val answer = agent.run(customerMessage)
    agentSays(answer)

    // Koog: ask for a typed summary instead of free text.
    val summary = llm.executor.executeStructured<TicketSummary>(
        prompt("ticket-summary") { system("Write the ticket summary."); user("Ticket CS-10. The agent's reply: $answer") },
        llm.model,
    ).getOrThrow().data
    println("  Ticket summary: category '${summary.category}', refund verified: ${summary.refundVerified}")

    val problems = summaryProblems(summary, shop)
    println(if (problems.isEmpty()) "  Summary passes every rule." else "  Rejected: " + problems.joinToString("; "))
}
