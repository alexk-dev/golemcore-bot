package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolFailureRecoverability;
import me.golemcore.bot.domain.model.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolFailureRecoveryServiceTest {

    private ToolFailureRecoveryService service;
    private Map<String, Integer> recoveryCounts;

    @BeforeEach
    void setUp() {
        service = new ToolFailureRecoveryService();
        recoveryCounts = new HashMap<>();
    }

    // -----------------------------------------------------------------------
    // 1. evaluate() with null/success outcome -> Continue
    // -----------------------------------------------------------------------

    @Test
    void shouldReturnContinueWhenOutcomeIsNull() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("ls");

        // Act
        ToolFailureRecoveryDecision decision = service.evaluate(toolCall, null, recoveryCounts);

        // Assert
        assertInstanceOf(ToolFailureRecoveryDecision.Continue.class, decision);
        assertNull(decision.fingerprint());
    }

    @Test
    void shouldReturnContinueWhenToolResultIsNull() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("ls");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", null, "some content", false, null);

        // Act
        ToolFailureRecoveryDecision decision = service.evaluate(toolCall, outcome, recoveryCounts);

        // Assert
        assertInstanceOf(ToolFailureRecoveryDecision.Continue.class, decision);
    }

    @Test
    void shouldReturnContinueWhenToolResultIsSuccess() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("ls");
        ToolResult successResult = ToolResult.success("file1.txt\nfile2.txt");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", successResult, "file1.txt\nfile2.txt",
                false, null);

        // Act
        ToolFailureRecoveryDecision decision = service.evaluate(toolCall, outcome, recoveryCounts);

        // Assert
        assertInstanceOf(ToolFailureRecoveryDecision.Continue.class, decision);
    }

    // -----------------------------------------------------------------------
    // 2. evaluate() with non-shell tool -> Stop
    // -----------------------------------------------------------------------

    @Test
    void shouldReturnStopWhenNonShellToolFails() {
        // Arrange
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc-1")
                .name("filesystem")
                .arguments(Map.of("path", "/some/path"))
                .build();
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "file not found");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "filesystem", failureResult, "file not found",
                false, null);

        // Act
        ToolFailureRecoveryDecision decision = service.evaluate(toolCall, outcome, recoveryCounts);

        // Assert
        assertInstanceOf(ToolFailureRecoveryDecision.Stop.class, decision);
        ToolFailureRecoveryDecision.Stop stop = (ToolFailureRecoveryDecision.Stop) decision;
        assertEquals(ToolFailureRecoverability.FATAL, stop.recoverability());
    }

    // -----------------------------------------------------------------------
    // 3. evaluate() with shell tool + POLICY_DENIED -> Stop (FATAL)
    // -----------------------------------------------------------------------

    @Test
    void shouldReturnStopWithFatalWhenShellToolPolicyDenied() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("rm -rf /");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.POLICY_DENIED, "command blocked by policy");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "command blocked by policy", false, null);

        // Act
        ToolFailureRecoveryDecision decision = service.evaluate(toolCall, outcome, recoveryCounts);

        // Assert
        assertInstanceOf(ToolFailureRecoveryDecision.Stop.class, decision);
        ToolFailureRecoveryDecision.Stop stop = (ToolFailureRecoveryDecision.Stop) decision;
        assertEquals(ToolFailureRecoverability.FATAL, stop.recoverability());
    }

    // -----------------------------------------------------------------------
    // 4. evaluate() with shell tool + CONFIRMATION_DENIED -> Stop
    // (USER_ACTION_REQUIRED)
    // -----------------------------------------------------------------------

    @Test
    void shouldReturnStopWithUserActionRequiredWhenConfirmationDenied() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("apt install something");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.CONFIRMATION_DENIED, "user denied confirmation");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "user denied confirmation", false, null);

        // Act
        ToolFailureRecoveryDecision decision = service.evaluate(toolCall, outcome, recoveryCounts);

        // Assert
        assertInstanceOf(ToolFailureRecoveryDecision.Stop.class, decision);
        ToolFailureRecoveryDecision.Stop stop = (ToolFailureRecoveryDecision.Stop) decision;
        assertEquals(ToolFailureRecoverability.USER_ACTION_REQUIRED, stop.recoverability());
    }

    // -----------------------------------------------------------------------
    // 5. evaluate() with shell tool + "no such file or directory" -> InjectHint
    // first, Stop when budget exhausted
    // -----------------------------------------------------------------------

    @Test
    void shouldReturnInjectHintOnFirstAttemptForPathNotFound() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("cat /missing/file.txt");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "No such file or directory", false, null);

        // Act
        ToolFailureRecoveryDecision decision = service.evaluate(toolCall, outcome, recoveryCounts);

        // Assert
        assertInstanceOf(ToolFailureRecoveryDecision.InjectHint.class, decision);
        ToolFailureRecoveryDecision.InjectHint hint = (ToolFailureRecoveryDecision.InjectHint) decision;
        assertEquals(ToolFailureRecoverability.SELF_CORRECTABLE, hint.recoverability());
        assertNotNull(hint.hint());
        assertTrue(hint.hint().contains("Shell recovery note"));
    }

    @Test
    void shouldReturnStopWhenRecoveryBudgetExhaustedForPathNotFound() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("cat /missing/file.txt");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "No such file or directory", false, null);

        // Act - exhaust the budget (6 attempts)
        for (int attempt = 0; attempt < 6; attempt++) {
            service.evaluate(toolCall, outcome, recoveryCounts);
        }
        ToolFailureRecoveryDecision decision = service.evaluate(toolCall, outcome, recoveryCounts);

        // Assert
        assertInstanceOf(ToolFailureRecoveryDecision.Stop.class, decision);
        ToolFailureRecoveryDecision.Stop stop = (ToolFailureRecoveryDecision.Stop) decision;
        assertEquals(ToolFailureRecoverability.SELF_CORRECTABLE, stop.recoverability());
    }

    // -----------------------------------------------------------------------
    // 6. evaluate() with shell tool + "timed out" -> InjectHint (RETRYABLE)
    // -----------------------------------------------------------------------

    @Test
    void shouldReturnInjectHintWithRetryableForTimeout() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("curl http://slow-server.com");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "Command timed out after 30s");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "Command timed out after 30s", false, null);

        // Act
        ToolFailureRecoveryDecision decision = service.evaluate(toolCall, outcome, recoveryCounts);

        // Assert
        assertInstanceOf(ToolFailureRecoveryDecision.InjectHint.class, decision);
        ToolFailureRecoveryDecision.InjectHint hint = (ToolFailureRecoveryDecision.InjectHint) decision;
        assertEquals(ToolFailureRecoverability.RETRYABLE, hint.recoverability());
        assertTrue(hint.hint().contains("Shell recovery note"));
    }

    // -----------------------------------------------------------------------
    // 7. evaluate() with shell tool + "command injection detected" -> Stop (FATAL)
    // -----------------------------------------------------------------------

    @Test
    void shouldReturnStopWithFatalForCommandInjectionDetected() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("echo $(cat /etc/passwd)");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "command injection detected");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "command injection detected", false, null);

        // Act
        ToolFailureRecoveryDecision decision = service.evaluate(toolCall, outcome, recoveryCounts);

        // Assert
        assertInstanceOf(ToolFailureRecoveryDecision.Stop.class, decision);
        ToolFailureRecoveryDecision.Stop stop = (ToolFailureRecoveryDecision.Stop) decision;
        assertEquals(ToolFailureRecoverability.FATAL, stop.recoverability());
    }

    @Test
    void shouldReturnStopWithFatalForCommandBlockedForSecurityReasons() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("dangerous-command");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED,
                "command blocked for security reasons");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "command blocked for security reasons", false, null);

        // Act
        ToolFailureRecoveryDecision decision = service.evaluate(toolCall, outcome, recoveryCounts);

        // Assert
        assertInstanceOf(ToolFailureRecoveryDecision.Stop.class, decision);
        ToolFailureRecoveryDecision.Stop stop = (ToolFailureRecoveryDecision.Stop) decision;
        assertEquals(ToolFailureRecoverability.FATAL, stop.recoverability());
    }

    // -----------------------------------------------------------------------
    // 8. buildFingerprint() for shell tool includes command and bucket
    // -----------------------------------------------------------------------

    @Test
    void shouldBuildFingerprintWithCommandAndBucketForShellTool() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("cat /missing/file.txt");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "No such file or directory", false, null);

        // Act
        String fingerprint = service.buildFingerprint(toolCall, outcome);

        // Assert
        assertTrue(fingerprint.contains("shell"));
        assertTrue(fingerprint.contains("EXECUTION_FAILED"));
        assertTrue(fingerprint.contains("cmd="));
        assertTrue(fingerprint.contains("cat /missing/file.txt"));
        assertTrue(fingerprint.contains("bucket=path_not_found"));
    }

    @Test
    void shouldNormalizeCommandInFingerprint() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("  LS   -la  ");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "exit code 1");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult, "exit code 1", false,
                null);

        // Act
        String fingerprint = service.buildFingerprint(toolCall, outcome);

        // Assert
        assertTrue(fingerprint.contains("cmd=ls -la"));
    }

    // -----------------------------------------------------------------------
    // 9. buildFingerprint() for non-shell tool includes error text
    // -----------------------------------------------------------------------

    @Test
    void shouldBuildFingerprintWithErrorTextForNonShellTool() {
        // Arrange
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc-1")
                .name("browser")
                .arguments(Map.of("url", "http://example.com"))
                .build();
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "Connection refused");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "browser", failureResult, "Connection refused",
                false, null);

        // Act
        String fingerprint = service.buildFingerprint(toolCall, outcome);

        // Assert
        assertTrue(fingerprint.contains("browser"));
        assertTrue(fingerprint.contains("EXECUTION_FAILED"));
        assertTrue(fingerprint.contains("connection refused"));
        // Non-shell fingerprints should NOT contain cmd= or bucket=
        assertTrue(!fingerprint.contains("cmd="));
        assertTrue(!fingerprint.contains("bucket="));
    }

    // -----------------------------------------------------------------------
    // 10. classifyShellErrorBucket() for each known bucket
    // -----------------------------------------------------------------------

    @Test
    void shouldClassifyWorkdirMissing() {
        // Arrange
        ToolExecutionOutcome outcome = shellOutcome("working directory does not exist: /foo/bar");

        // Act & Assert
        assertEquals("workdir_missing", service.classifyShellErrorBucket(outcome));
    }

    @Test
    void shouldClassifyPathNotFound() {
        // Arrange
        ToolExecutionOutcome outcome = shellOutcome("No such file or directory: /tmp/missing");

        // Act & Assert
        assertEquals("path_not_found", service.classifyShellErrorBucket(outcome));
    }

    @Test
    void shouldClassifyCommandNotFound() {
        // Arrange
        ToolExecutionOutcome outcome = shellOutcome("bash: foobar: command not found");

        // Act & Assert
        assertEquals("command_not_found", service.classifyShellErrorBucket(outcome));
    }

    @Test
    void shouldClassifyTimeout() {
        // Arrange
        ToolExecutionOutcome outcome = shellOutcome("Process timed out after 30 seconds");

        // Act & Assert
        assertEquals("timeout", service.classifyShellErrorBucket(outcome));
    }

    @Test
    void shouldClassifySecurityBlockedForInjection() {
        // Arrange
        ToolExecutionOutcome outcome = shellOutcome("command injection detected in input");

        // Act & Assert
        assertEquals("security_blocked", service.classifyShellErrorBucket(outcome));
    }

    @Test
    void shouldClassifySecurityBlockedForBlockedCommand() {
        // Arrange
        ToolExecutionOutcome outcome = shellOutcome("command blocked for security reasons");

        // Act & Assert
        assertEquals("security_blocked", service.classifyShellErrorBucket(outcome));
    }

    @Test
    void shouldClassifySecurityBlockedForWorkspaceViolation() {
        // Arrange
        ToolExecutionOutcome outcome = shellOutcome("working directory must be within workspace");

        // Act & Assert
        assertEquals("security_blocked", service.classifyShellErrorBucket(outcome));
    }

    @Test
    void shouldClassifyExitCode1() {
        // Arrange
        ToolExecutionOutcome outcome = shellOutcome("Process finished with exit code 1");

        // Act & Assert
        assertEquals("exit_code_1", service.classifyShellErrorBucket(outcome));
    }

    @Test
    void shouldClassifyExitCode2() {
        // Arrange
        ToolExecutionOutcome outcome = shellOutcome("Process finished with exit code 2");

        // Act & Assert
        assertEquals("exit_code_2", service.classifyShellErrorBucket(outcome));
    }

    @Test
    void shouldClassifyExecutionFailedForUnknownError() {
        // Arrange
        ToolExecutionOutcome outcome = shellOutcome("something completely unexpected happened");

        // Act & Assert
        assertEquals("execution_failed", service.classifyShellErrorBucket(outcome));
    }

    @Test
    void shouldClassifyExecutionFailedWhenOutcomeIsNull() {
        // Act & Assert
        assertEquals("execution_failed", service.classifyShellErrorBucket(null));
    }

    @Test
    void shouldClassifyFromMessageContentWhenErrorIsNull() {
        // Arrange
        ToolResult failureResult = ToolResult.builder()
                .success(false)
                .error(null)
                .failureKind(ToolFailureKind.EXECUTION_FAILED)
                .build();
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "No such file or directory", false, null);

        // Act & Assert
        assertEquals("path_not_found", service.classifyShellErrorBucket(outcome));
    }

    // -----------------------------------------------------------------------
    // 11. Recovery budget tracking: first six attempts get hints, seventh stops
    // -----------------------------------------------------------------------

    @Test
    void shouldAllowSixRecoveryAttemptsBeforeStopping() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("cat /missing/file.txt");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "No such file or directory", false, null);

        // Act & Assert - first six attempts: InjectHint
        for (int attempt = 0; attempt < 6; attempt++) {
            ToolFailureRecoveryDecision decision = service.evaluate(toolCall, outcome, recoveryCounts);
            assertInstanceOf(ToolFailureRecoveryDecision.InjectHint.class, decision);
        }

        // Act & Assert - seventh attempt: Stop (budget exhausted)
        ToolFailureRecoveryDecision seventh = service.evaluate(toolCall, outcome, recoveryCounts);
        assertInstanceOf(ToolFailureRecoveryDecision.Stop.class, seventh);
    }

    @Test
    void shouldIncludeAttemptCountInHint() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("cat /missing/file.txt");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "No such file or directory", false, null);

        // Act
        ToolFailureRecoveryDecision.InjectHint first = (ToolFailureRecoveryDecision.InjectHint) service
                .evaluate(toolCall, outcome, recoveryCounts);
        ToolFailureRecoveryDecision.InjectHint second = (ToolFailureRecoveryDecision.InjectHint) service
                .evaluate(toolCall, outcome, recoveryCounts);

        // Assert
        assertTrue(first.hint().contains("Recovery attempt 1 of 6"));
        assertTrue(second.hint().contains("Recovery attempt 2 of 6"));
    }

    // -----------------------------------------------------------------------
    // 12. Different commands reset the counter (different fingerprints)
    // -----------------------------------------------------------------------

    @Test
    void shouldResetCounterForDifferentCommands() {
        // Arrange
        Message.ToolCall toolCallA = shellToolCall("cat /missing/a.txt");
        Message.ToolCall toolCallB = shellToolCall("cat /missing/b.txt");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory");
        ToolExecutionOutcome outcomeA = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "No such file or directory", false, null);
        ToolExecutionOutcome outcomeB = new ToolExecutionOutcome("tc-2", "shell", failureResult,
                "No such file or directory", false, null);

        // Act - exhaust budget for command A
        for (int attempt = 0; attempt < 6; attempt++) {
            service.evaluate(toolCallA, outcomeA, recoveryCounts);
        }
        ToolFailureRecoveryDecision exhaustedA = service.evaluate(toolCallA, outcomeA, recoveryCounts);

        // Command B should still have its own budget
        ToolFailureRecoveryDecision firstB = service.evaluate(toolCallB, outcomeB, recoveryCounts);

        // Assert
        assertInstanceOf(ToolFailureRecoveryDecision.Stop.class, exhaustedA);
        assertInstanceOf(ToolFailureRecoveryDecision.InjectHint.class, firstB);
    }

    @Test
    void shouldProduceDifferentFingerprintsForDifferentCommands() {
        // Arrange
        Message.ToolCall toolCallA = shellToolCall("cat /a.txt");
        Message.ToolCall toolCallB = shellToolCall("cat /b.txt");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory");
        ToolExecutionOutcome outcomeA = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "No such file or directory", false, null);
        ToolExecutionOutcome outcomeB = new ToolExecutionOutcome("tc-2", "shell", failureResult,
                "No such file or directory", false, null);

        // Act
        String fingerprintA = service.buildFingerprint(toolCallA, outcomeA);
        String fingerprintB = service.buildFingerprint(toolCallB, outcomeB);

        // Assert
        assertTrue(!fingerprintA.equals(fingerprintB), "Fingerprints for different commands should differ");
    }

    // -----------------------------------------------------------------------
    // Hint content assertions
    // -----------------------------------------------------------------------

    @Test
    void shouldIncludePathDiagnosticsHintForPathNotFound() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("cat /missing/file.txt");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "No such file or directory", false, null);

        // Act
        ToolFailureRecoveryDecision.InjectHint hint = (ToolFailureRecoveryDecision.InjectHint) service
                .evaluate(toolCall, outcome, recoveryCounts);

        // Assert
        assertTrue(hint.hint().contains("verify the current directory"));
        assertTrue(hint.hint().contains("pwd"));
    }

    @Test
    void shouldIncludeCommandAvailabilityHintForCommandNotFound() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("nonexistent-cmd --help");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED,
                "bash: nonexistent-cmd: command not found");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "bash: nonexistent-cmd: command not found", false, null);

        // Act
        ToolFailureRecoveryDecision.InjectHint hint = (ToolFailureRecoveryDecision.InjectHint) service
                .evaluate(toolCall, outcome, recoveryCounts);

        // Assert
        assertTrue(hint.hint().contains("verify which commands are available"));
    }

    @Test
    void shouldIncludeRetryHintForTimeout() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("slow-command");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "Process timed out");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult, "Process timed out",
                false, null);

        // Act
        ToolFailureRecoveryDecision.InjectHint hint = (ToolFailureRecoveryDecision.InjectHint) service
                .evaluate(toolCall, outcome, recoveryCounts);

        // Assert
        assertTrue(hint.hint().contains("simplify the command before retrying"));
    }

    @Test
    void shouldIncludeLastCommandInHint() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("cat /missing/file.txt");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "No such file or directory", false, null);

        // Act
        ToolFailureRecoveryDecision.InjectHint hint = (ToolFailureRecoveryDecision.InjectHint) service
                .evaluate(toolCall, outcome, recoveryCounts);

        // Assert
        assertTrue(hint.hint().contains("Last command: `cat /missing/file.txt`"));
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    void shouldUseFallbackToolNameFromOutcomeWhenToolCallNameIsNull() {
        // Arrange
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc-1")
                .name(null)
                .arguments(Map.of("command", "ls"))
                .build();
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "No such file or directory", false, null);

        // Act
        ToolFailureRecoveryDecision decision = service.evaluate(toolCall, outcome, recoveryCounts);

        // Assert - resolves to "shell" from outcome, so should be recoverable
        assertInstanceOf(ToolFailureRecoveryDecision.InjectHint.class, decision);
    }

    @Test
    void shouldUseDefaultFailureKindInFingerprintWhenFailureKindIsNull() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("ls");
        ToolResult failureResult = ToolResult.builder()
                .success(false)
                .error("some error")
                .failureKind(null)
                .build();
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult, "some error", false,
                null);

        // Act
        String fingerprint = service.buildFingerprint(toolCall, outcome);

        // Assert
        assertTrue(fingerprint.contains("EXECUTION_FAILED"));
    }

    @Test
    void shouldHandleShellToolCallWithNullArguments() {
        // Arrange
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc-1")
                .name("shell")
                .arguments(null)
                .build();
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "some error");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult, "some error", false,
                null);

        // Act
        String fingerprint = service.buildFingerprint(toolCall, outcome);

        // Assert
        assertTrue(fingerprint.contains("cmd=unknown"));
    }

    @Test
    void shouldIncludeWorkdirMissingBucketInHintForWorkdirMissing() {
        // Arrange
        Message.ToolCall toolCall = shellToolCall("ls");
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED,
                "working directory does not exist: /gone");
        ToolExecutionOutcome outcome = new ToolExecutionOutcome("tc-1", "shell", failureResult,
                "working directory does not exist: /gone", false, null);

        // Act
        ToolFailureRecoveryDecision.InjectHint hint = (ToolFailureRecoveryDecision.InjectHint) service
                .evaluate(toolCall, outcome, recoveryCounts);

        // Assert
        assertTrue(hint.hint().contains("verify the current directory"));
        assertEquals(ToolFailureRecoverability.SELF_CORRECTABLE, hint.recoverability());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Message.ToolCall shellToolCall(String command) {
        return Message.ToolCall.builder()
                .id("tc-1")
                .name("shell")
                .arguments(Map.of("command", command))
                .build();
    }

    private ToolExecutionOutcome shellOutcome(String errorMessage) {
        ToolResult failureResult = ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, errorMessage);
        return new ToolExecutionOutcome("tc-1", "shell", failureResult, errorMessage, false, null);
    }
}
