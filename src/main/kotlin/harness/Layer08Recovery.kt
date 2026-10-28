package harness

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.features.eventHandler.feature.EventHandlerConfig
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.agents.snapshot.providers.file.JVMFilePersistenceStorageProvider
import java.io.File

/** Everything layer 8 keeps on disk, so it survives the crash: the refund count, the checkpoints, and whether we crashed. */
val demoDir = File("build/demo-state").apply { mkdirs() }
val shop8 = Shop(stateFile = File(demoDir, "refunds.txt"))
val checkpoints = JVMFilePersistenceStorageProvider(File(demoDir, "checkpoints").toPath())
private val crashedOnce = File(demoDir, "crashed-once")

/** Layer 8: Koog's Persistence feature saves a checkpoint after every step of the graph. */
fun agent8(crash: Boolean) = AIAgent(
    promptExecutor = llm.executor,
    agentConfig = supportConfig(),
    toolRegistry = supportTools(shop8, readAndRefund("ticket-42"), allowHighRisk = true),
    id = "support",
) {
    install(Persistence) {
        storage = checkpoints
        enableAutomaticPersistence = true   // a checkpoint after every step
    }
    handleEvents { Trace().install(this); if (crash) crashOnceAfterRefund() }
}

/** Our code: continue from the latest checkpoint if there is one, or start the ticket. */
suspend fun resumeOrStart(sessionId: String, input: String, crash: Boolean): String {
    val latest = checkpoints.getLatestCheckpoint(sessionId)
    return if (latest != null) Persistence.runFromCheckpoint(agent8(crash), input, latest)
    else agent8(crash).run(input, sessionId)
}

/** A simulated outage: kill the whole JVM right after the refund, before the agent replies. Only once. */
fun EventHandlerConfig.crashOnceAfterRefund() = onLLMCallStarting { ctx ->
    val afterRefund = ctx.prompt.messages.any { m -> m.parts.any { "[refunded]" in it.toString() } }
    if (afterRefund && !crashedOnce.exists()) {
        crashedOnce.writeText("yes")
        println("  The process dies right after the refund. Run layer 8 again to resume.")
        Runtime.getRuntime().halt(1)
    }
}

fun resetLayer8() = demoDir.listFiles()?.forEach { it.deleteRecursively() }

suspend fun layer8(crash: Boolean) {
    heading("Layer 8 · Durable state and recovery", "Saving the agent's progress, so a crash loses no work and repeats no actions.")
    val answer = resumeOrStart("ticket-42", customerMessage, crash)
    agentSays(answer)
    println("  Refunds in total: ${shop8.refunds}")
}
