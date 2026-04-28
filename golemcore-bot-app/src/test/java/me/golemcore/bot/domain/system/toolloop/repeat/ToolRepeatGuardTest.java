package me.golemcore.bot.domain.system.toolloop.repeat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
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
