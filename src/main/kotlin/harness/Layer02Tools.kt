package harness

/** Layer 2: which tools are risky is our decision. See supportTools in Tools.kt. */
val toolRisk = mapOf("getOrder" to "low", "trackDelivery" to "low", "searchHelpCenter" to "low", "issueRefund" to "high: moves money")

fun layer2() {
    heading("Layer 2 · Tools and least privilege", "Giving the agent only the tools it needs, with risky ones off by default.")
    println("  Without allowHighRisk: " + supportTools(Shop(), readOnly("demo")).tools.joinToString { it.name })
    println("  With allowHighRisk:    " + supportTools(Shop(), readAndRefund("demo"), allowHighRisk = true).tools.joinToString { it.name })
    println()
    toolRisk.forEach { (tool, risk) -> println("  %-18s %s".format(tool, risk)) }
}
