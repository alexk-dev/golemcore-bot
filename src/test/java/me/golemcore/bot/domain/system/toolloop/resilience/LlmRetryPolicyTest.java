package me.golemcore.bot.domain.system.toolloop.resilience;

import me.golemcore.bot.domain.model.RuntimeConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRetryPolicyTest {

    @Test
    void shouldRetryOnlyWhileAttemptIsBelowConfiguredBudget() {
        LlmRetryPolicy policy = new LlmRetryPolicy();
        RuntimeConfig.ResilienceConfig config = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(2)
                .build();

        assertTrue(policy.shouldRetry(0, config));
        assertTrue(policy.shouldRetry(1, config));
        assertFalse(policy.shouldRetry(2, config));
    }

    @Test
    void shouldKeepJitterDelayWithinExponentialCeilingAndCap() {
        LlmRetryPolicy policy = new LlmRetryPolicy();
        RuntimeConfig.ResilienceConfig config = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryBaseDelayMs(10L)
                .hotRetryCapMs(25L)
                .build();

        for (int index = 0; index < 64; index++) {
            long firstAttempt = policy.computeDelay(0, config);
            long cappedAttempt = policy.computeDelay(5, config);
            assertTrue(firstAttempt >= 0 && firstAttempt < 10);
            assertTrue(cappedAttempt >= 0 && cappedAttempt < 25);
        }
    }

    @Test
    void shouldTreatNegativeAttemptsAsFirstAttemptWhenComputingDelay() {
        LlmRetryPolicy policy = new LlmRetryPolicy();
        RuntimeConfig.ResilienceConfig config = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryBaseDelayMs(7L)
                .hotRetryCapMs(100L)
                .build();

        for (int index = 0; index < 32; index++) {
            long delay = policy.computeDelay(-5, config);
            assertTrue(delay >= 0 && delay < 7);
        }
    }

    @Test
    void shouldStayWithinCapForLargeAttemptCountsWithoutOverflow() {
        LlmRetryPolicy policy = new LlmRetryPolicy();
        RuntimeConfig.ResilienceConfig config = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryBaseDelayMs(5_000L)
                .hotRetryCapMs(60_000L)
                .build();

        for (int attempt : new int[] { 63, 100, 10_000, Integer.MAX_VALUE }) {
            long delay = policy.computeDelay(attempt, config);
            assertTrue(delay >= 0 && delay < 60_000L, "delay=" + delay + " at attempt=" + attempt);
        }
    }

    @Test
    void shouldReturnImmediatelyWhenSleepDelayIsNonPositive() {
        LlmRetryPolicy policy = new LlmRetryPolicy();

        policy.sleep(0);
        policy.sleep(-1);

        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void shouldRestoreInterruptedFlagWhenSleepIsInterrupted() throws InterruptedException {
        LlmRetryPolicy policy = new LlmRetryPolicy();
        Thread thread = new Thread(() -> {
            Thread.currentThread().interrupt();
            policy.sleep(10);
            assertTrue(Thread.currentThread().isInterrupted());
        });

        thread.start();
        thread.join();
    }
}
