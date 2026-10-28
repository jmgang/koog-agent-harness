package harness

import kotlinx.coroutines.runBlocking

/** The agent with no harness: Claude (or the scripted model) and the three read tools. */
suspend fun theAgent() {
    heading("The agent", "A graph of steps, a model and tools, put together into one agent. No harness yet.")
    agentSays(supportAgent.run(customerMessage))
}

/**
 * Runs the demo.
 *
 *   ./gradlew run                 every layer, in order
 *   ./gradlew run --args="5 8"    only layers 5 and 8 (0 is the agent without a harness)
 *   ./gradlew run --args="reset"  start layer 8 over: clear its refunds and checkpoints
 *
 * Layer 8 on its own crashes the process right after the refund. Run it again to resume from the checkpoint.
 */
fun main(args: Array<String>) = runBlocking {
    io.github.oshai.kotlinlogging.KotlinLoggingConfiguration.logStartupMessage = false
    if ("reset" in args) { resetLayer8(); println("Layer 8 reset."); return@runBlocking }

    val chosen = args.mapNotNull { it.toIntOrNull() }
    val all = chosen.isEmpty()
    println("Model: ${if (llm.isScripted) "scripted (offline)" else llm.model.id}")

    val layers: List<Pair<Int, suspend () -> Unit>> = listOf(
        0 to { theAgent() },
        1 to { layer1() },
        2 to { layer2() },
        5 to { layer5() },
        6 to { layer6(primaryDown = System.getenv("HARNESS_PRIMARY_DOWN") == "yes") },
        7 to { layer7(capacityWithRealModel = System.getenv("HARNESS_CAPACITY") == "yes") },
        // Crashing would end a run of every layer, so layer 8 crashes only when it runs on its own.
        8 to { layer8(crash = chosen == listOf(8)) },
        10 to { layer10() },
        11 to { layer11() },
        12 to { layer12() },
    )
    for ((number, run) in layers) if (all || number in chosen) run()
    println()
}
