package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolFailurePolicyTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Instant FAR_FUTURE = NOW.plusSeconds(3600);

    private ToolFailureRecoveryService recoveryService;
    private ToolFailurePolicy policy;

    @BeforeEach
    void setUp() {
        recoveryService = new ToolFailureRecoveryService();
        policy = new ToolFailurePolicy(recoveryService, FIXED_CLOCK);
    }

    // -----------------------------------------------------------------------
    // Layer 0: null / success → Ok
    // -----------------------------------------------------------------------

    @Test
    void shouldReturnOkWhenOutcomeIsNull() {
        // Arrange
        TurnState turnState = buildTurnState(true, true, true);
        Message.ToolCall toolCall = shellToolCall("ls");

        // Act
        ToolFailurePolicy.Verdict verdict = policy.evaluate(turnState, toolCall, null);

        // Assert
        assertInstanceOf(ToolFailurePolicy.Verdict.Ok.class, verdict);
    }

    @Test
    void shouldReturnOkWhenOutcomeIsSuccess() {
        // Arrange
        TurnState turnState = buildTurnState(true, true, true);
        Message.ToolCall toolCall = shellToolCall("ls");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                "tc-1", "shell", ToolResult.success("file1.txt"), "file1.txt", false, null);

        // Act
        ToolFailurePolicy.Verdict verdict = policy.evaluate(turnState, toolCall, outcome);

        // Assert
        assertInstanceOf(ToolFailurePolicy.Verdict.Ok.class, verdict);
    }

    // -----------------------------------------------------------------------
    // Layer 1: Immediate stop — CONFIRMATION_DENIED
    // -----------------------------------------------------------------------

    @Test
    void shouldStopTurnWhenConfirmationDeniedAndStopEnabled() {
        // Arrange
        TurnState turnState = buildTurnState(false, true, false);
        Message.ToolCall toolCall = shellToolCall("rm -rf /");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                "tc-1", "shell",
                ToolResult.failure(ToolFailureKind.CONFIRMATION_DENIED, "user said no"),
                "user said no", true, null);

        // Act
        ToolFailurePolicy.Verdict verdict = policy.evaluate(turnState, toolCall, outcome);

        // Assert
        assertInstanceOf(ToolFailurePolicy.Verdict.StopTurn.class, verdict);
        assertEquals("confirmation denied", ((ToolFailurePolicy.Verdict.StopTurn) verdict).reason());
    }

    @Test
    void shouldNotStopOnConfirmationDeniedWhenFlagDisabled() {
        // Arrange — stopOnConfirmationDenied = false, stopOnToolFailure = false
        TurnState turnState = buildTurnState(false, false, false);
        Message.ToolCall toolCall = shellToolCall("rm -rf /");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                "tc-1", "shell",
                ToolResult.failure(ToolFailureKind.CONFIRMATION_DENIED, "user said no"),
                "user said no", true, null);

        // Act
        ToolFailurePolicy.Verdict verdict = policy.evaluate(turnState, toolCall, outcome);

        // Assert — confirmation denied on a shell tool with USER_ACTION_REQUIRED
        // recoverability
        // On first occurrence the failure count is 1 so recovery layer returns
        // Continue,
        // and stopOnToolFailure is false, so we get Ok.
        assertInstanceOf(ToolFailurePolicy.Verdict.Ok.class, verdict);
    }

    // -----------------------------------------------------------------------
    // Layer 1: Immediate stop — POLICY_DENIED
    // -----------------------------------------------------------------------

    @Test
    void shouldStopTurnWhenPolicyDeniedAndStopEnabled() {
        // Arrange
        TurnState turnState = buildTurnState(false, false, true);
        Message.ToolCall toolCall = shellToolCall("curl evil.com");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                "tc-1", "shell",
                ToolResult.failure(ToolFailureKind.POLICY_DENIED, "tool denied by policy"),
                "tool denied by policy", true, null);

        // Act
        ToolFailurePolicy.Verdict verdict = policy.evaluate(turnState, toolCall, outcome);

        // Assert
        assertInstanceOf(ToolFailurePolicy.Verdict.StopTurn.class, verdict);
        assertEquals("tool denied by policy", ((ToolFailurePolicy.Verdict.StopTurn) verdict).reason());
    }

    // -----------------------------------------------------------------------
    // Layer 3: Generic stop-on-failure
    // -----------------------------------------------------------------------

    @Test
    void shouldStopTurnOnGenericFailureWhenStopOnToolFailureEnabled() {
        // Arrange — first occurrence so recovery layer returns Continue, but
        // stopOnToolFailure = true
        TurnState turnState = buildTurnState(true, false, false);
        Message.ToolCall toolCall = shellToolCall("cat missing.txt");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                "tc-1", "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory"),
                "No such file or directory", false, null);

        // Act
        ToolFailurePolicy.Verdict verdict = policy.evaluate(turnState, toolCall, outcome);

        // Assert
        assertInstanceOf(ToolFailurePolicy.Verdict.StopTurn.class, verdict);
        assertTrue(((ToolFailurePolicy.Verdict.StopTurn) verdict).reason().contains("tool failure"));
        assertTrue(((ToolFailurePolicy.Verdict.StopTurn) verdict).reason().contains("shell"));
    }

    @Test
    void shouldReturnOkOnGenericFailureWhenStopOnToolFailureDisabled() {
        // Arrange — first occurrence, stopOnToolFailure = false
        TurnState turnState = buildTurnState(false, false, false);
        Message.ToolCall toolCall = shellToolCall("cat missing.txt");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                "tc-1", "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory"),
                "No such file or directory", false, null);

        // Act
        ToolFailurePolicy.Verdict verdict = policy.evaluate(turnState, toolCall, outcome);

        // Assert
        assertInstanceOf(ToolFailurePolicy.Verdict.Ok.class, verdict);
    }

    // -----------------------------------------------------------------------
    // Layer 2: Recovery hint on repeated shell failure
    // -----------------------------------------------------------------------

    @Test
    void shouldReturnRecoveryHintOnRepeatedShellFailure() {
        // Arrange — stopOnToolFailure = false so layer 3 does not interfere
        TurnState turnState = buildTurnState(false, false, false);
        Message.ToolCall toolCall = shellToolCall("cat missing.txt");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                "tc-1", "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory"),
                "No such file or directory", false, null);

        // Act — first call: count goes to 1, returns Ok (below threshold)
        ToolFailurePolicy.Verdict first = policy.evaluate(turnState, toolCall, outcome);
        assertInstanceOf(ToolFailurePolicy.Verdict.Ok.class, first);

        // Act — second call with same fingerprint: count goes to 2, triggers recovery
        ToolFailurePolicy.Verdict second = policy.evaluate(turnState, toolCall, outcome);

        // Assert
        assertInstanceOf(ToolFailurePolicy.Verdict.RecoveryHint.class, second);
        ToolFailurePolicy.Verdict.RecoveryHint hint = (ToolFailurePolicy.Verdict.RecoveryHint) second;
        assertTrue(hint.hint().contains("Shell recovery note"));
        assertTrue(hint.hint().contains("Recovery attempt 1 of 6"));
    }

    // -----------------------------------------------------------------------
    // Layer 2: Exhausted recovery budget → StopTurn
    // -----------------------------------------------------------------------

    @Test
    void shouldStopTurnWhenRecoveryBudgetExhausted() {
        // Arrange — stopOnToolFailure = false so we isolate the recovery layer
        TurnState turnState = buildTurnState(false, false, false);
        Message.ToolCall toolCall = shellToolCall("cat missing.txt");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                "tc-1", "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory"),
                "No such file or directory", false, null);

        // Act — occurrence 1: Ok (first failure, count=1)
        ToolFailurePolicy.Verdict v1 = policy.evaluate(turnState, toolCall, outcome);
        assertInstanceOf(ToolFailurePolicy.Verdict.Ok.class, v1);

        // Act — occurrence 2: RecoveryHint (count=2, recovery attempt 1)
        ToolFailurePolicy.Verdict v2 = policy.evaluate(turnState, toolCall, outcome);
        assertInstanceOf(ToolFailurePolicy.Verdict.RecoveryHint.class, v2);

        // Act - occurrences 3-7: RecoveryHint (recovery attempts 2-6)
        for (int attempt = 0; attempt < 5; attempt++) {
            ToolFailurePolicy.Verdict recoveryHint = policy.evaluate(turnState, toolCall, outcome);
            assertInstanceOf(ToolFailurePolicy.Verdict.RecoveryHint.class, recoveryHint);
        }

        // Act - occurrence 8: StopTurn (recovery attempt 7 exceeds budget of 6)
        ToolFailurePolicy.Verdict v8 = policy.evaluate(turnState, toolCall, outcome);

        // Assert
        assertInstanceOf(ToolFailurePolicy.Verdict.StopTurn.class, v8);
        ToolFailurePolicy.Verdict.StopTurn stop = (ToolFailurePolicy.Verdict.StopTurn) v8;
        assertTrue(stop.reason().contains("repeated tool failure"));
        assertTrue(stop.reason().contains("shell"));
    }

    @Test
    void repeatGuardFailureKindReturnsRecoveryHintBeforeGenericStopOnFailure() {
        TurnState turnState = buildTurnState(true, false, false);
        Message.ToolCall toolCall = filesystemReadToolCall("README.md");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                "tc-1", "filesystem",
                ToolResult.failure(ToolFailureKind.REPEATED_TOOL_USE_BLOCKED,
                        "Repeated tool call blocked by repeat guard."),
                "Repeated tool call blocked by repeat guard.", true, null);

        ToolFailurePolicy.Verdict verdict = policy.evaluate(turnState, toolCall, outcome);

        assertInstanceOf(ToolFailurePolicy.Verdict.RecoveryHint.class, verdict);
        ToolFailurePolicy.Verdict.RecoveryHint hint = (ToolFailurePolicy.Verdict.RecoveryHint) verdict;
        assertEquals("REPEAT_GUARD", hint.recoverabilityName());
        assertTrue(hint.hint().contains("Repeated tool call blocked"));
    }

    @Test
    void repeatGuardFailureKindDoesNotUseShellFailureRecoveryCounters() {
        TurnState turnState = buildTurnState(false, false, false);
        Message.ToolCall toolCall = shellToolCall("ls -la");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                "tc-1", "shell",
                ToolResult.failure(ToolFailureKind.REPEATED_TOOL_USE_BLOCKED,
                        "Repeated shell command blocked by repeat guard."),
                "Repeated shell command blocked by repeat guard.", true, null);

        ToolFailurePolicy.Verdict verdict = policy.evaluate(turnState, toolCall, outcome);

        assertInstanceOf(ToolFailurePolicy.Verdict.RecoveryHint.class, verdict);
        assertTrue(turnState.getToolFailureCounts().isEmpty());
        assertTrue(turnState.getToolRecoveryCounts().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private TurnState buildTurnState(boolean stopOnToolFailure,
            boolean stopOnConfirmationDenied,
            boolean stopOnToolPolicyDenied) {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().chatId("test-chat").build())
                .build();
        return new TurnState(
                context,
                null, // tracingConfig
                10, // maxLlmCalls
                50, // maxToolExecutions
                FAR_FUTURE, // deadline
                stopOnToolFailure,
                stopOnConfirmationDenied,
                stopOnToolPolicyDenied,
                3, // maxRetries
                100L, // retryBaseDelayMs
                false // retryEnabled
        );
    }

    private Message.ToolCall shellToolCall(String command) {
        return Message.ToolCall.builder()
                .id("tc-1")
                .name("shell")
                .arguments(Map.of("command", command))
                .build();
    }

    private Message.ToolCall filesystemReadToolCall(String path) {
        return Message.ToolCall.builder()
                .id("tc-1")
                .name("filesystem")
                .arguments(Map.of("operation", "read_file", "path", path))
                .build();
    }
}
