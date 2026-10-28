# Koog agent harness

The demo from the talk **Building an Agent Harness with Koog**: a customer service agent for an online shop, built with [Koog](https://github.com/JetBrains/koog) 1.3.0, and the harness around it, one layer at a time.

The customer writes: *"Where's my order #1042? It was due a week ago. If it's lost, I want my money back."* The agent has three tools that read data (`getOrder`, `trackDelivery`, `searchHelpCenter`) and one that moves money (`issueRefund`). Every layer works on that ticket.

## Run it

You need JDK 21.

```bash
./gradlew run
```

This runs every layer with a scripted model: offline, no API key, and the same output every time.

```bash
./gradlew run --args="5 8"
```

This runs only layers 5 and 8. Layer 0 is the agent without a harness.

```bash
./gradlew test
```

This runs the layer 12 harness tests as unit tests. They use Koog's mock executor, so they make no model calls.

### With Claude on Amazon Bedrock

```bash
HARNESS_MODEL=sonnet AWS_PROFILE=your-profile ./gradlew run
```

`HARNESS_MODEL=haiku` works too. `AWS_REGION` defaults to `us-east-1`, and your account needs access to Claude Sonnet 4.5 or Haiku 4.5 in Bedrock. On a real model, layer 7 skips its 25 parallel tickets unless you set `HARNESS_CAPACITY=yes`.

### Layer 8: crash and resume

```bash
./gradlew run --args="reset"
./gradlew run --args="8"
./gradlew run --args="8"
```

The first run of layer 8 kills the process right after the refund, so Gradle reports a failure. The second run resumes from the last checkpoint, replies to the customer, and prints `Refunds in total: 1`.

## The layers

| Layer | File | What Koog gives you | What's yours |
|---|---|---|---|
| 1 Scope and control flow | `Layer01ScopeAndControlFlow.kt`, `Agent.kt` | the graph strategy, the step limit (`maxAgentIterations`) | one job per agent, the limit itself |
| 2 Tools and least privilege | `Tools.kt`, `Layer02Tools.kt` | tool sets and the tool registry | which tools are risky; the refund tool is off by default |
| 5 Context | `Layer05Context.kt` | `nodeLLMCompressHistory` | when to summarize |
| 6 Model-call reliability | `Layer06Reliability.kt` | `RetryingLLMClient` | failover to a backup model |
| 7 Cost and capacity | `Layer07Cost.kt` | event hooks, the tokenizer, agents that run in parallel | the token budget |
| 8 Durable state and recovery | `Layer08Recovery.kt` | `Persistence`: checkpoints, storage and resume | finding the checkpoint to resume |
| 10 Verification and guardrails | `Layer10Verification.kt` | structured output (`executeStructured`) | asking the payment provider; the summary rules |
| 11 Observability and audit | `Layer11Observability.kt` | the `OpenTelemetry` feature | where the spans go, and what goes into them |
| 12 Evaluation and testing | `Layer12Testing.kt`, `HarnessTest.kt` | the mock executor (`getMockExecutor`) | the tests |

Layers 3, 4 and 9 (identity and secrets, isolation, human oversight) live in your infrastructure and your policies. The demo touches two of them: each ticket gets a `Credential` with its own scopes (layer 3), and `ApprovalGate` decides when a refund needs a person (layer 9).

## What's fake

`Shop.kt` fakes the orders, the tracking, the help center and the payment provider. `ScriptedClient.kt` is the offline model: it picks the next step from what's already in the conversation. It reports no token usage, so the layer 11 spans show 0 tokens unless you run on Claude.
