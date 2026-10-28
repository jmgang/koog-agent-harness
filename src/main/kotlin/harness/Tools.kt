package harness

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet

/** Low risk: read-only tools, checked against the ticket's credential (layer 3). */
class ReadTools(private val shop: Shop, private val credential: Credential, private val verboseTracking: Boolean = false) : ToolSet {
    private fun read(orderId: String, body: () -> String) =
        if (credential.allows("read:order-$orderId")) body() else "[denied] no read:order-$orderId scope"

    @Tool @LLMDescription("Look up an order: what was bought, the price, and when it shipped")
    fun getOrder(@LLMDescription("Order number, e.g. 1042") orderId: String): String = read(orderId) { shop.order(orderId) }

    @Tool @LLMDescription("Track an order's delivery with the courier")
    fun trackDelivery(@LLMDescription("Order number, e.g. 1042") orderId: String): String = read(orderId) { shop.tracking(orderId, verboseTracking) }

    @Tool @LLMDescription("Search the help center for the shop's policies")
    fun searchHelpCenter(@LLMDescription("What to search for, e.g. lost parcel") query: String): String = shop.helpCenter(query)
}

/** A harness policy wrapped around a risky action: approval, escalation, verification (layers 9 and 10). */
fun interface Guard {
    fun around(action: String, perform: () -> String): String
    companion object { val None = Guard { _, perform -> perform() } }
}
infix fun Guard.then(next: Guard): Guard = Guard { action, perform -> around(action) { next.around(action, perform) } }

/** High risk: moves money. Checked against the credential, then run through the harness's guards. */
class RefundTools(private val shop: Shop, private val credential: Credential, private val guard: Guard = Guard.None) : ToolSet {
    @Tool @LLMDescription("Refund an order in full to the original payment method. Use only when the policy says a refund is due.")
    fun issueRefund(@LLMDescription("Order number") orderId: String): String =
        if (!credential.allows("refund:order-$orderId")) "[denied] the ticket's credential has no refund:order-$orderId scope."
        else guard.around("refund order $orderId") { shop.refund(orderId) }
}

/** Layer 2: the read tools always, the refund tool only when the caller asks for it. */
fun supportTools(
    shop: Shop, credential: Credential,
    allowHighRisk: Boolean = false, guard: Guard = Guard.None, verboseTracking: Boolean = false,
) = ToolRegistry {
    tools(ReadTools(shop, credential, verboseTracking))
    if (allowHighRisk) tools(RefundTools(shop, credential, guard))
}
