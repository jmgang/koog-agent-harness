package harness

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.delay

/**
 * A deterministic stand-in for the model, so the demo works offline and every run is the same.
 * It reads the conversation so far and picks the next step like a careful support agent would.
 * With [wander] it keeps re-checking the tracking and never answers (layer 1).
 */
class ScriptedClient(private val latencyMs: Long = 150, private val wander: Boolean = false) : LLMClient() {
    private var calls = 0
    override fun llmProvider() = LLMProvider.Bedrock

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        delay(latencyMs)
        val seen = prompt.messages.joinToString("\n") { m -> m.parts.joinToString(" ") { it.toString() } }
        val canRefund = tools.any { it.name == "issueRefund" }
        val part: MessagePart.ResponsePart = when {
            prompt.id == "ticket-summary" || "TicketSummary" in seen -> MessagePart.Text(
                """{"category":"delivery","resolution":"Parcel lost by the courier; refunded in full",""" +
                    """"actions":["Refunded €89.00 to the card ending 4242","Told the customer the refund takes 3–5 working days"],""" +
                    """"refundVerified":${"verified" in seen.lowercase()}}""",
            )
            tools.isEmpty() -> MessagePart.Text(
                "Summary so far: [order] #1042, headphones, €89.00, due 17 Oct. [tracking] 60 'in transit' scans; ParcelGo marked the parcel as lost on 23 Oct." +
                    if ("[policy]" in seen) " [policy] lost parcels get a full refund." else "",
            )
            wander && "[order]" in seen -> call("trackDelivery", """{"orderId":"1042"}""")
            "[escalated]" in seen -> MessagePart.Text("The refund didn't go through twice, so I've passed your case to a colleague, who will contact you within 24 hours.")
            "[denied]" in seen -> MessagePart.Text("Your parcel was lost by the courier, so you're owed a full refund of €89.00. I can't issue refunds myself, so a colleague will do it today.")
            "[verified]" in seen -> MessagePart.Text("I'm sorry, the courier lost your parcel. I've refunded €89.00 to your card, and we verified with the payment provider that it went through.")
            "[not-confirmed]" in seen -> MessagePart.Text("I've asked for a refund of €89.00, but the payment provider hasn't confirmed it. A colleague will check and get back to you.")
            "[refund-failed]" in seen && canRefund -> call("issueRefund", """{"orderId":"1042"}""")
            "[refunded]" in seen -> MessagePart.Text("I'm sorry, the courier lost your parcel. I've refunded €89.00 to your card; it shows up in 3–5 working days.")
            "[pending-approval]" in seen -> MessagePart.Text("Your parcel was lost, so you're owed a full refund of €89.00. A colleague needs to approve it; you'll hear from us today.")
            "[policy]" in seen && canRefund -> call("issueRefund", """{"orderId":"1042"}""")
            "[policy]" in seen -> MessagePart.Text("I'm sorry, the courier lost your parcel #1042. Our policy gives you a full refund of €89.00; a colleague will issue it.")
            "[tracking]" in seen -> call("searchHelpCenter", """{"query":"lost parcel"}""")
            "[order]" in seen -> call("trackDelivery", """{"orderId":"1042"}""")
            else -> call("getOrder", """{"orderId":"1042"}""")
        }
        return Message.Assistant(part, ResponseMetaInfo.Empty)
    }

    private fun call(tool: String, args: String) = MessagePart.Tool.Call("call-${++calls}", tool, args)
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("No moderation")
    override fun close() {}
}
