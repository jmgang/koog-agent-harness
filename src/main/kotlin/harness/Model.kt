package harness

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.bedrock.BedrockClientSettings
import ai.koog.prompt.executor.clients.bedrock.BedrockLLMClient
import ai.koog.prompt.executor.clients.bedrock.BedrockModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import aws.sdk.kotlin.runtime.auth.credentials.ProfileCredentialsProvider

fun executorFor(client: LLMClient): PromptExecutor = MultiLLMPromptExecutor(client.llmProvider() to client)

/** The client and models every layer uses. */
data class Llm(val client: LLMClient, val model: LLModel, val backupModel: LLModel, val isScripted: Boolean) {
    val executor: PromptExecutor = executorFor(client)
}

/**
 * The model, picked with the HARNESS_MODEL environment variable:
 * - `scripted` (the default): offline, and every run is the same.
 * - `sonnet` or `haiku`: Claude on Amazon Bedrock, with the AWS profile in AWS_PROFILE and the region in AWS_REGION.
 */
val llm: Llm = when (val choice = System.getenv("HARNESS_MODEL")?.lowercase() ?: "scripted") {
    "sonnet", "haiku" -> {
        val bedrock = BedrockLLMClient(
            identityProvider = ProfileCredentialsProvider(profileName = System.getenv("AWS_PROFILE") ?: "default"),
            settings = BedrockClientSettings(region = System.getenv("AWS_REGION") ?: "us-east-1"),
        )
        val haiku = choice == "haiku"
        Llm(
            bedrock,
            if (haiku) BedrockModels.AnthropicClaude4_5Haiku else BedrockModels.AnthropicClaude4_5Sonnet,
            backupModel = if (haiku) BedrockModels.AnthropicClaude4_5Sonnet else BedrockModels.AnthropicClaude4_5Haiku,
            isScripted = false,
        )
    }
    "scripted" -> Llm(ScriptedClient(), BedrockModels.AnthropicClaude4_5Sonnet, BedrockModels.AnthropicClaude4_5Haiku, isScripted = true)
    else -> error("HARNESS_MODEL must be scripted, sonnet or haiku, not '$choice'")
}
