package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.RuntimeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnStateTest {

    private AgentContext context;
    private RuntimeConfig.TracingConfig tracingConfig;
    private Instant deadline;
    private TurnState turnState;

    @BeforeEach
    void setUp() {
        AgentSession session = AgentSession.builder()
                .id("session-1")
                .chatId("chat-1")
                .channelType("test")
                .build();

        context = AgentContext.builder()
                .session(session)
                .build();

        tracingConfig = RuntimeConfig.TracingConfig.builder()
                .enabled(true)
                .build();

        deadline = Instant.now().plusSeconds(60);

        turnState = new TurnState(
                context,
                tracingConfig,
                /* maxLlmCalls */ 5,
                /* maxToolExecutions */ 10,
                deadline,
                /* stopOnToolFailure */ true,
                /* stopOnConfirmationDenied */ false,
                /* stopOnToolPolicyDenied */ true,
                /* maxRetries */ 3,
                /* retryBaseDelayMs */ 1000L,
                /* retryEnabled */ true);
    }

    @Test
    void shouldReturnTrueFromCanContinueWhenWithinLimitsAndBeforeDeadline() {
        // Arrange
        Instant now = deadline.minusSeconds(30);

        // Act
        boolean result = turnState.canContinue(now);

        // Assert
        assertTrue(result);
    }

    @Test
    void shouldReturnFalseFromCanContinueWhenLlmCallsReachMax() {
        // Arrange
        for (int i = 0; i < 5; i++) {
            turnState.incrementLlmCalls();
        }
        Instant now = deadline.minusSeconds(30);

        // Act
        boolean result = turnState.canContinue(now);

        // Assert
        assertFalse(result);
    }

    @Test
    void shouldReturnFalseFromCanContinueWhenToolExecutionsReachMax() {
        // Arrange
        for (int i = 0; i < 10; i++) {
            turnState.incrementToolExecutions();
        }
        Instant now = deadline.minusSeconds(30);

        // Act
        boolean result = turnState.canContinue(now);

        // Assert
        assertFalse(result);
    }

    @Test
    void shouldReturnFalseFromCanContinueWhenNowIsAfterDeadline() {
        // Arrange
        Instant now = deadline.plusSeconds(1);

        // Act
        boolean result = turnState.canContinue(now);

        // Assert
        assertFalse(result);
    }

    @Test
    void shouldIncrementLlmCallsAndReturnNewValue() {
        // Act & Assert
        assertEquals(1, turnState.incrementLlmCalls());
        assertEquals(2, turnState.incrementLlmCalls());
        assertEquals(3, turnState.incrementLlmCalls());
        assertEquals(3, turnState.getLlmCalls());
    }

    @Test
    void shouldIncrementToolExecutionsCounter() {
        // Act
        turnState.incrementToolExecutions();
        turnState.incrementToolExecutions();

        // Assert
        assertEquals(2, turnState.getToolExecutions());
    }

    @Test
    void shouldIncrementEmptyFinalResponseRetriesAndReturnNewValue() {
        // Act & Assert
        assertEquals(1, turnState.incrementEmptyFinalResponseRetries());
        assertEquals(2, turnState.incrementEmptyFinalResponseRetries());
        assertEquals(2, turnState.getEmptyFinalResponseRetries());
    }

    @Test
    void shouldIncrementRetryAttemptAndReturnNewValue() {
        // Act & Assert
        assertEquals(1, turnState.incrementRetryAttempt());
        assertEquals(2, turnState.incrementRetryAttempt());
        assertEquals(2, turnState.getRetryAttempt());
    }

    @Test
    void shouldResetRetryStateToZero() {
        // Arrange
        turnState.incrementRetryAttempt();
        turnState.incrementRetryAttempt();
        turnState.setLastRetryCode("overloaded");

        // Act
        turnState.resetRetryState();

        // Assert
        assertEquals(0, turnState.getRetryAttempt());
        assertEquals("", turnState.getLastRetryCode());
    }

    @Test
    void shouldReturnMutableToolFailureCountsMap() {
        // Act
        Map<String, Integer> failureCounts = turnState.getToolFailureCounts();
        failureCounts.put("shell", 2);

        // Assert
        assertNotNull(failureCounts);
        assertEquals(2, turnState.getToolFailureCounts().get("shell"));
    }

    @Test
    void shouldReturnMutableToolRecoveryCountsMap() {
        // Act
        Map<String, Integer> recoveryCounts = turnState.getToolRecoveryCounts();
        recoveryCounts.put("browser", 1);

        // Assert
        assertNotNull(recoveryCounts);
        assertEquals(1, turnState.getToolRecoveryCounts().get("browser"));
    }

    @Test
    void shouldReturnConfigurationFlags() {
        // Assert
        assertTrue(turnState.isStopOnToolFailure());
        assertFalse(turnState.isStopOnConfirmationDenied());
        assertTrue(turnState.isStopOnToolPolicyDenied());
        assertEquals(5, turnState.getMaxLlmCalls());
        assertEquals(10, turnState.getMaxToolExecutions());
        assertSame(deadline, turnState.getDeadline());
        assertEquals(3, turnState.getMaxRetries());
        assertEquals(1000L, turnState.getRetryBaseDelayMs());
        assertTrue(turnState.isRetryEnabled());
    }

    @Test
    void shouldReturnContext() {
        // Act
        AgentContext result = turnState.getContext();

        // Assert
        assertSame(context, result);
    }

    @Test
    void shouldReturnTracingConfig() {
        // Act
        RuntimeConfig.TracingConfig result = turnState.getTracingConfig();

        // Assert
        assertSame(tracingConfig, result);
    }
}
