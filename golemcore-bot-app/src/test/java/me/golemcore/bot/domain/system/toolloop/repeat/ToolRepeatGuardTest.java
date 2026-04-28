package me.golemcore.bot.domain.system.toolloop.repeat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.system.toolloop.ToolExecutionOutcome;
import me.golemcore.bot.domain.system.toolloop.TurnState;
import org.junit.jupiter.api.Test;

class ToolRepeatGuardTest {

    private static final Instant NOW = Instant.parse("2026-04-28T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ToolRepeatGuard guard = new ToolRepeatGuard(new ToolUseFingerprintService(),
            ToolRepeatGuardSettings.defaults(), clock);

    @Test
    void allowsFirstObservation() {
        ToolRepeatDecision decision = guard.beforeExecute(turnState(), readCall("README.md"));

        assertInstanceOf(ToolRepeatDecision.Allow.class, decision);
    }

    @Test
    void warnsOnSecondIdenticalObservationInSameState() {
        TurnState turnState = turnState();
        Message.ToolCall call = readCall("README.md");
        guard.afterOutcome(turnState, call, success(call, "first"));

        ToolRepeatDecision decision = guard.beforeExecute(turnState, call);

        assertInstanceOf(ToolRepeatDecision.WarnAndAllow.class, decision);
    }

    @Test
    void blocksThirdIdenticalObservationInSameState() {
        TurnState turnState = turnState();
        Message.ToolCall call = readCall("README.md");
        guard.afterOutcome(turnState, call, success(call, "first"));
        guard.afterOutcome(turnState, call, success(call, "second"));

        ToolRepeatDecision decision = guard.beforeExecute(turnState, call);

        assertInstanceOf(ToolRepeatDecision.BlockAndHint.class, decision);
    }

    @Test
    void allowsSameObservationAfterEnvironmentVersionChanged() {
        TurnState turnState = turnState();
        Message.ToolCall read = readCall("README.md");
        Message.ToolCall write = writeCall("README.md");
        guard.afterOutcome(turnState, read, success(read, "first"));
        guard.afterOutcome(turnState, read, success(read, "second"));
        guard.afterOutcome(turnState, write, success(write, "updated"));

        ToolRepeatDecision decision = guard.beforeExecute(turnState, read);

        assertInstanceOf(ToolRepeatDecision.Allow.class, decision);
    }

    @Test
    void readOnlyShellDoesNotResetObservationRepeatWindow() {
        TurnState turnState = turnState();
        Message.ToolCall read = readCall("README.md");
        Message.ToolCall shell = toolCall("shell", Map.of("command", "pwd"));
        guard.afterOutcome(turnState, read, success(read, "before-1"));
        guard.afterOutcome(turnState, read, success(read, "before-2"));
        guard.afterOutcome(turnState, shell, success(shell, "ok"));

        ToolRepeatDecision decision = guard.beforeExecute(turnState, read);

        assertInstanceOf(ToolRepeatDecision.BlockAndHint.class, decision);
    }

    @Test
    void pollBackoffUsesLastPollAcrossAllEnvironmentVersions() {
        TurnState turnState = turnState();
        Message.ToolCall poll = toolCall("job.status", Map.of("jobId", "42"));
        Message.ToolCall write = writeCall("README.md");
        guard.afterOutcome(turnState, poll, success(poll, "pending"));
        guard.afterOutcome(turnState, write, success(write, "updated"));

        ToolRepeatDecision decision = guard.beforeExecute(turnState, poll);

        assertInstanceOf(ToolRepeatDecision.BlockAndHint.class, decision);
    }

    @Test
    void policyDeniedOutcomeDoesNotCountAsSuccessfulRepeat() {
        TurnState turnState = turnState();
        Message.ToolCall shell = toolCall("shell", Map.of("command", "pwd"));
        guard.afterOutcome(turnState, shell, new ToolExecutionOutcome(
                shell.getId(),
                shell.getName(),
                ToolResult.failure(ToolFailureKind.POLICY_DENIED, "denied"),
                "denied",
                false,
                null));
        guard.afterOutcome(turnState, shell, new ToolExecutionOutcome(
                shell.getId(),
                shell.getName(),
                ToolResult.failure(ToolFailureKind.POLICY_DENIED, "denied"),
                "denied",
                false,
                null));

        ToolRepeatDecision decision = guard.beforeExecute(turnState, shell);

        assertInstanceOf(ToolRepeatDecision.Allow.class, decision);
    }

    @Test
    void failedObservationDoesNotTriggerUsePreviousResultRepeatBlock() {
        TurnState turnState = turnState();
        Message.ToolCall read = readCall("README.md");
        guard.afterOutcome(turnState, read, new ToolExecutionOutcome(
                read.getId(),
                read.getName(),
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "temporary"),
                "temporary",
                false,
                null));
        guard.afterOutcome(turnState, read, new ToolExecutionOutcome(
                read.getId(),
                read.getName(),
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "temporary"),
                "temporary",
                false,
                null));

        ToolRepeatDecision decision = guard.beforeExecute(turnState, read);

        assertInstanceOf(ToolRepeatDecision.Allow.class, decision);
    }

    @Test
    void allowsDifferentArguments() {
        TurnState turnState = turnState();
        Message.ToolCall first = readCall("README.md");
        guard.afterOutcome(turnState, first, success(first, "first"));
        guard.afterOutcome(turnState, first, success(first, "second"));

        ToolRepeatDecision decision = guard.beforeExecute(turnState, readCall("docs/AUTO_MODE.md"));

        assertInstanceOf(ToolRepeatDecision.Allow.class, decision);
    }

    @Test
    void blocksPollingBeforeMinPollInterval() {
        TurnState turnState = turnState();
        Message.ToolCall poll = toolCall("job.status", Map.of("jobId", "42"));
        guard.afterOutcome(turnState, poll, success(poll, "pending"));

        ToolRepeatDecision decision = guard.beforeExecute(turnState, poll);

        assertInstanceOf(ToolRepeatDecision.BlockAndHint.class, decision);
    }

    @Test
    void allowsPollingAfterMinPollInterval() {
        TurnState turnState = turnState();
        Message.ToolCall poll = toolCall("job.status", Map.of("jobId", "42"));
        ToolRepeatGuard laterGuard = new ToolRepeatGuard(new ToolUseFingerprintService(),
                ToolRepeatGuardSettings.defaults(),
                Clock.fixed(NOW.plus(Duration.ofSeconds(61)), ZoneOffset.UTC));
        guard.afterOutcome(turnState, poll, success(poll, "pending"));

        ToolRepeatDecision decision = laterGuard.beforeExecute(turnState, poll);

        assertInstanceOf(ToolRepeatDecision.Allow.class, decision);
    }

    @Test
    void blocksExactUnknownExecutionAfterConfiguredThreshold() {
        TurnState turnState = turnState();
        Message.ToolCall shell = toolCall("shell", Map.of("command", "ls -la", "cwd", "."));
        guard.afterOutcome(turnState, shell, success(shell, "first"));
        guard.afterOutcome(turnState, shell, success(shell, "second"));

        ToolRepeatDecision decision = guard.beforeExecute(turnState, shell);

        assertInstanceOf(ToolRepeatDecision.BlockAndHint.class, decision);
    }

    @Test
    void allowsExactUnknownExecutionAfterVerifiedFilesystemMutationChangedEnvironment() {
        TurnState turnState = turnState();
        Message.ToolCall shell = toolCall("shell", Map.of("command", "mvn test", "cwd", "."));
        Message.ToolCall write = writeCall("src/main/java/App.java");
        guard.afterOutcome(turnState, shell, success(shell, "first test run"));
        guard.afterOutcome(turnState, shell, success(shell, "second test run"));
        guard.afterOutcome(turnState, write, success(write, "updated source"));

        ToolRepeatDecision decision = guard.beforeExecute(turnState, shell);

        assertInstanceOf(ToolRepeatDecision.Allow.class, decision);
    }

    @Test
    void doesNotBlockControlToolPlanExit() {
        TurnState turnState = turnState();
        Message.ToolCall planExit = toolCall("plan_exit", Map.of());
        guard.afterOutcome(turnState, planExit, success(planExit, "done"));
        guard.afterOutcome(turnState, planExit, success(planExit, "done"));

        ToolRepeatDecision decision = guard.beforeExecute(turnState, planExit);

        assertInstanceOf(ToolRepeatDecision.Allow.class, decision);
    }

    @Test
    void stopsTurnAfterMaxBlockedRepeatsPerTurn() {
        TurnState turnState = turnState();
        for (int index = 0; index < ToolRepeatGuardSettings.defaults().maxBlockedRepeatsPerTurn(); index++) {
            turnState.getToolUseLedger().incrementBlockedRepeatCount();
        }

        ToolRepeatDecision decision = guard.beforeExecute(turnState, readCall("README.md"));

        assertInstanceOf(ToolRepeatDecision.StopTurn.class, decision);
    }

    @Test
    void disabledGuardAlwaysAllowsEvenRepeatedObservation() {
        TurnState turnState = turnState();
        Message.ToolCall call = readCall("README.md");
        ToolRepeatGuard disabledGuard = ToolRepeatGuard.noop();
        guard.afterOutcome(turnState, call, success(call, "first"));
        guard.afterOutcome(turnState, call, success(call, "second"));

        ToolRepeatDecision decision = disabledGuard.beforeExecute(turnState, call);

        assertInstanceOf(ToolRepeatDecision.Allow.class, decision);
    }

    @Test
    void disabledGuardDoesNotAccumulateFutureBlockingLedgerState() {
        TurnState turnState = turnState();
        Message.ToolCall call = readCall("README.md");
        AtomicReference<ToolRepeatGuardSettings> settings = new AtomicReference<>(ToolRepeatGuardSettings.disabled());
        ToolRepeatGuard switchableGuard = new ToolRepeatGuard(new ToolUseFingerprintService(), settings::get, clock);
        switchableGuard.afterOutcome(turnState, call, success(call, "first"));
        switchableGuard.afterOutcome(turnState, call, success(call, "second"));

        settings.set(ToolRepeatGuardSettings.defaults());
        ToolRepeatDecision decision = switchableGuard.beforeExecute(turnState, call);

        assertInstanceOf(ToolRepeatDecision.Allow.class, decision);
        assertEquals(0, turnState.getToolUseLedger().successfulRepeatCountInCurrentEnvironment(
                new ToolUseFingerprintService().fingerprint(call)));
    }

    @Test
    void shadowModeWarnsInsteadOfBlockingAndCountsWarning() {
        TurnState turnState = turnState();
        Message.ToolCall call = readCall("README.md");
        ToolRepeatGuard shadowGuard = new ToolRepeatGuard(
                new ToolUseFingerprintService(),
                new ToolRepeatGuardSettings(true, true, 1, 1, 4, Duration.ofSeconds(60), Duration.ofMinutes(120)),
                clock);
        guard.afterOutcome(turnState, call, success(call, "first"));

        ToolRepeatDecision decision = shadowGuard.beforeExecute(turnState, call);

        assertInstanceOf(ToolRepeatDecision.WarnAndAllow.class, decision);
        assertEquals(0, turnState.getToolUseLedger().getBlockedRepeatCount());
        assertEquals(1, turnState.getToolUseLedger().getWarnedRepeatCount());
    }

    @Test
    void shadowModeDoesNotStopAfterBlockedThreshold() {
        TurnState turnState = turnState();
        for (int index = 0; index < ToolRepeatGuardSettings.defaults().maxBlockedRepeatsPerTurn(); index++) {
            turnState.getToolUseLedger().incrementBlockedRepeatCount();
        }
        Message.ToolCall call = readCall("README.md");
        ToolRepeatGuard shadowGuard = new ToolRepeatGuard(
                new ToolUseFingerprintService(),
                new ToolRepeatGuardSettings(true, true, 1, 1, 4, Duration.ofSeconds(60), Duration.ofMinutes(120)),
                clock);
        guard.afterOutcome(turnState, call, success(call, "first"));

        ToolRepeatDecision decision = shadowGuard.beforeExecute(turnState, call);

        assertInstanceOf(ToolRepeatDecision.WarnAndAllow.class, decision);
    }

    @Test
    void recordsGuardBlockedFailureKindForBlockedMutation() {
        TurnState turnState = turnState();
        Message.ToolCall write = writeCall("README.md");
        ToolUseFingerprint fingerprint = new ToolUseFingerprintService().fingerprint(write);
        turnState.getToolUseLedger().restore(new ToolUseRecord(
                fingerprint,
                NOW,
                NOW,
                true,
                null,
                "sha256:previous",
                turnState.getToolUseLedger().getEnvironmentVersion(),
                false,
                "updated"));

        ToolRepeatDecision decision = guard.beforeExecute(turnState, write);
        guard.afterOutcome(turnState, write, new ToolExecutionOutcome(
                write.getId(),
                write.getName(),
                ToolResult.failure(ToolFailureKind.REPEATED_TOOL_USE_BLOCKED, "blocked"),
                "blocked",
                false,
                null));

        assertInstanceOf(ToolRepeatDecision.BlockAndHint.class, decision);
        assertEquals(2, turnState.getToolUseLedger().recordsFor(fingerprint).size());
    }

    @Test
    void ignoresNullOutcomeInputsAndUsesDefaultSettingsWhenSupplierReturnsNull() {
        TurnState turnState = turnState();
        ToolRepeatGuard nullableSettingsGuard = new ToolRepeatGuard(new ToolUseFingerprintService(), () -> null, clock);

        nullableSettingsGuard.afterOutcome(null, null, null);
        ToolRepeatDecision decision = nullableSettingsGuard.beforeExecute(turnState, readCall("README.md"));

        assertInstanceOf(ToolRepeatDecision.Allow.class, decision);
    }

    @Test
    void settingsClampInvalidDurationsAndThresholds() {
        ToolRepeatGuardSettings settings = new ToolRepeatGuardSettings(
                true, false, 0, -1, 0, Duration.ofSeconds(-1), Duration.ofMinutes(-1));

        assertEquals(1, settings.maxSameObservePerTurn());
        assertEquals(1, settings.maxSameUnknownPerTurn());
        assertEquals(1, settings.maxBlockedRepeatsPerTurn());
        assertEquals(Duration.ofSeconds(60), settings.minPollInterval());
        assertEquals(Duration.ofMinutes(120), settings.autoLedgerTtl());
    }

    private TurnState turnState() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-1").build())
                .build();
        return new TurnState(context, null, 4, 10, NOW.plusSeconds(60), false, true, false, 0, 1L, false);
    }

    private Message.ToolCall readCall(String path) {
        return toolCall("filesystem", Map.of("operation", "read_file", "path", path));
    }

    private Message.ToolCall writeCall(String path) {
        return toolCall("filesystem", Map.of("operation", "write_file", "path", path, "content", "updated"));
    }

    private Message.ToolCall toolCall(String name, Map<String, Object> arguments) {
        return Message.ToolCall.builder().id("call-" + name).name(name).arguments(arguments).build();
    }

    private ToolExecutionOutcome success(Message.ToolCall toolCall, String content) {
        return new ToolExecutionOutcome(toolCall.getId(), toolCall.getName(), ToolResult.success(content),
                content, false, null);
    }
}
