package harness

import java.io.File

/** A fake online shop, so the demo is safe and repeatable. */
class Shop(private val stateFile: File? = null, var refundsWork: Boolean = true) {
    fun order(orderId: String) =
        "[order] #$orderId: wireless headphones, €89.00, paid by card, shipped 14 Oct with ParcelGo, due 17 Oct"

    fun tracking(orderId: String, verbose: Boolean = false): String =
        "[tracking] #$orderId: last scan 16 Oct at the Leipzig depot; on 23 Oct ParcelGo marked the parcel as lost" +
            if (verbose) "\n" + (1..60).joinToString("\n") { "[tracking] #$orderId: scan $it, in transit, Leipzig depot" } else ""

    fun helpCenter(query: String) =
        "[policy] Parcel lost by the courier: refund the full amount to the original payment method. No return needed."

    /** What the payment provider has on record. The harness checks this, not the agent's word (layer 10). */
    var providerConfirmsRefund = false
        private set

    /** Refunds survive a crash when the count is kept in a file (layer 8). */
    var refunds: Int
        get() = stateFile?.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: memoryRefunds
        private set(value) { memoryRefunds = value; stateFile?.writeText(value.toString()) }
    private var memoryRefunds = 0

    fun refund(orderId: String): String {
        refunds += 1
        if (!refundsWork) return "[refund-failed] the payment provider declined the refund for #$orderId (attempt #$refunds)"
        providerConfirmsRefund = true
        return "[refunded] €89.00 for #$orderId to the card ending 4242 (refund #$refunds)"
    }
}

/** A credential for one ticket. Its secret goes to the API client, never into a prompt (layer 3). */
class Credential(val ticket: String, val scopes: Set<String>) {
    val secret = "tok_" + java.util.UUID.randomUUID().toString().take(12)
    fun allows(scope: String) = scope in scopes
    override fun toString() = "Credential($ticket, $scopes)"
}

fun readOnly(ticket: String) = Credential(ticket, setOf("read:order-1042"))
fun readAndRefund(ticket: String) = Credential(ticket, setOf("read:order-1042", "refund:order-1042"))
