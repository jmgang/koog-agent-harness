package harness

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** Layer 12 as unit tests: fake models that misbehave, and the harness rules that must stop them. No real model calls. */
class HarnessTest {
    @Test
    fun `never refunds without approval`() = check { testNeverRefundsWithoutApproval() }

    @Test
    fun `escalates after two failed refunds`() = check { testEscalatesAfterTwoFailures() }

    @Test
    fun `stops a looping agent`() = check { testStopsALoopingAgent() }

    private fun check(test: suspend () -> TestResult) = runBlocking {
        val result = test()
        assertTrue(result.passed, "${result.test}: ${result.detail}")
    }
}
