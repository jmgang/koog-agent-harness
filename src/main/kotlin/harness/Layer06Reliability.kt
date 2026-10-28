package harness

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.retry.RetryConfig
import ai.koog.prompt.executor.clients.retry.RetryingLLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Simulated provider trouble: the first [failures] calls fail with HTTP 429. */
class RateLimited(private val delegate: LLMClient, private var failures: Int) : LLMClient() {
    var attempts = 0
        private set
    override fun llmProvider() = delegate.llmProvider()
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        attempts++
        if (failures-- > 0) error("HTTP 429: rate limit exceeded")
        return delegate.execute(prompt, model, tools)
    }
    override suspend fun moderate(prompt: Prompt, model: LLModel) = delegate.moderate(prompt, model)
    override fun close() = delegate.close()
}

/** Our code: if the primary model still fails after its retries, fail over to a backup model and stay there. */
class Failover(private val primary: LLMClient, private val backup: LLMClient, private val backupModel: LLModel) : LLMClient() {
    var usedBackup = false
        private set
    override fun llmProvider() = primary.llmProvider()
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        if (usedBackup) backup.execute(prompt, backupModel, tools)
        else try { primary.execute(prompt, model, tools) } catch (e: Exception) { usedBackup = true; backup.execute(prompt, backupModel, tools) }
    override suspend fun moderate(prompt: Prompt, model: LLModel) = primary.moderate(prompt, model)
    override fun close() {}
}

suspend fun layer6(primaryDown: Boolean = false) {
    heading("Layer 6 · Model-call reliability", "Surviving errors from the model provider.")
    // The first two calls fail on purpose. With primaryDown, every call to the primary fails, and failover kicks in.
    val flaky = RateLimited(llm.client, failures = if (primaryDown) Int.MAX_VALUE else 2)
    // Koog: retry with backoff.
    val retrying = RetryingLLMClient(flaky, RetryConfig(maxAttempts = 3, initialDelay = 200.milliseconds, maxDelay = 1.seconds))
    val failover = Failover(retrying, backup = llm.client, backupModel = llm.backupModel)
    val agent = AIAgent(
        promptExecutor = executorFor(failover),
        agentConfig = supportConfig(),
        strategy = supportStrategy,
        toolRegistry = supportTools(Shop(), readOnly("CS-6")),
    )
    agentSays(agent.run(customerMessage))
    println("  Calls to the primary: ${flaky.attempts}, failed over to ${llm.backupModel.id}: ${failover.usedBackup}")
}
