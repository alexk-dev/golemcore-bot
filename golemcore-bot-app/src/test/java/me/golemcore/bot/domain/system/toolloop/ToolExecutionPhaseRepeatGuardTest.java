package me.golemcore.bot.domain.system.toolloop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ModelSelectionService;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolNames;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.events.RuntimeEventService;
import me.golemcore.bot.domain.planning.PlanService;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolRepeatGuard;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolRepeatGuardSettings;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseFingerprintService;
import me.golemcore.bot.domain.system.toolloop.view.ConversationViewBuilder;
import me.golemcore.bot.domain.tools.PlanModeToolRestrictionService;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolExecutionPhaseRepeatGuardTest {

    private Clock clock;
    private ToolExecutorPort toolExecutor;
    private ToolFailurePolicy failurePolicy;
    private HistoryWriter historyWriter;
    private LlmCallPhase llmCallPhase;
    private ToolRepeatGuard repeatGuard;
    private PlanService planService;
    private PlanModeToolRestrictionService planModeToolRestrictionService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-28T12:00:00Z"), ZoneOffset.UTC);
        toolExecutor = mock(ToolExecutorPort.class);
        failurePolicy = mock(ToolFailurePolicy.class);
        historyWriter = mock(HistoryWriter.class);
        repeatGuard = new ToolRepeatGuard(new ToolUseFingerprintService(), ToolRepeatGuardSettings.defaults(), clock);
        planService = new PlanService(clock, null);
        planModeToolRestrictionService = new PlanModeToolRestrictionService(planService);
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(2_000_000_000);
        llmCallPhase = new LlmCallPhase(
                mock(LlmPort.class),
                mock(ConversationViewBuilder.class),
                modelSelectionService,
                null,
                mock(LlmRequestPreflightPhase.class),
                mock(ContextCompactionCoordinator.class),
                null,
                null,
                null,
                null,
                clock);
    }

    @Test
    void blocksRepeatedToolBeforeExecutorIsCalled() {
        ToolExecutionPhase phase = phase();
        TurnState turnState = buildTurnState();
        Message.ToolCall toolCall = readCall("tc-repeat");
        recordTwoSuccessfulRepeats(turnState, toolCall);
        when(failurePolicy.evaluate(any(), any(), any())).thenReturn(new ToolFailurePolicy.Verdict.Ok());

        ToolExecutionPhase.ToolBatchOutcome outcome = phase.execute(turnState, response(toolCall), historyWriter,
                llmCallPhase);

        assertInstanceOf(ToolExecutionPhase.ToolBatchOutcome.Continue.class, outcome);
        verify(toolExecutor, never()).execute(any(), any());
        ToolResult result = turnState.getContext().getToolResults().get("tc-repeat");
        assertFalse(result.isSuccess());
        assertEquals(ToolFailureKind.REPEATED_TOOL_USE_BLOCKED, result.getFailureKind());
        assertTrue(result.getError().contains("Repeated tool call blocked"));
        assertTrue(result.getData() instanceof Map<?, ?>);
        assertTrue(((Map<?, ?>) result.getData()).containsKey("repeatFingerprint"));
        verify(historyWriter).appendToolResult(eq(turnState.getContext()), any());
    }

    @Test
    void blockedRepeatIsCountedAsToolExecutionSlot() {
        ToolExecutionPhase phase = phase();
        TurnState turnState = buildTurnState();
        Message.ToolCall toolCall = readCall("tc-repeat");
        recordTwoSuccessfulRepeats(turnState, toolCall);
        when(failurePolicy.evaluate(any(), any(), any())).thenReturn(new ToolFailurePolicy.Verdict.Ok());

        phase.execute(turnState, response(toolCall), historyWriter, llmCallPhase);

        assertEquals(1, turnState.getToolExecutions());
    }

    @Test
    void blockedRepeatTriggersRecoveryHintAndWritesSyntheticResultsForRemainingBatch() {
        ToolExecutionPhase phase = phase();
        TurnState turnState = buildTurnState();
        Message.ToolCall repeated = readCall("tc-repeat");
        Message.ToolCall remaining = readCall("tc-remaining");
        recordTwoSuccessfulRepeats(turnState, repeated);
        when(failurePolicy.evaluate(any(), any(), any()))
                .thenReturn(new ToolFailurePolicy.Verdict.RecoveryHint("use prior result", "fp", "REPEAT_GUARD"));

        ToolExecutionPhase.ToolBatchOutcome outcome = phase.execute(turnState,
                LlmResponse.builder().toolCalls(List.of(repeated, remaining)).build(), historyWriter, llmCallPhase);

        assertInstanceOf(ToolExecutionPhase.ToolBatchOutcome.RecoveryHintInjected.class, outcome);
        assertEquals(ToolFailureKind.EXECUTION_FAILED,
                turnState.getContext().getToolResults().get("tc-remaining").getFailureKind());
        verify(historyWriter).appendInternalRecoveryHint(turnState.getContext(), "use prior result");
    }

    @Test
    void warnAndAllowStillExecutesTool() {
        ToolExecutionPhase phase = phase();
        TurnState turnState = buildTurnState();
        Message.ToolCall toolCall = readCall("tc-repeat");
        repeatGuard.afterOutcome(turnState, toolCall, success(toolCall, "first"));
        when(toolExecutor.execute(turnState.getContext(), toolCall)).thenReturn(success(toolCall, "second"));

        ToolExecutionPhase.ToolBatchOutcome outcome = phase.execute(turnState, response(toolCall), historyWriter,
                llmCallPhase);

        assertInstanceOf(ToolExecutionPhase.ToolBatchOutcome.Continue.class, outcome);
        verify(toolExecutor).execute(turnState.getContext(), toolCall);
        assertTrue(turnState.getContext().getToolResults().get("tc-repeat").isSuccess());
    }

    @Test
    void warnAndAllowAppendsInternalToolLoopHintAfterExecution() {
        ToolExecutionPhase phase = phase();
        TurnState turnState = buildTurnState();
        Message.ToolCall toolCall = readCall("tc-repeat");
        repeatGuard.afterOutcome(turnState, toolCall, success(toolCall, "first"));
        when(toolExecutor.execute(turnState.getContext(), toolCall)).thenReturn(success(toolCall, "second"));

        phase.execute(turnState, response(toolCall), historyWriter, llmCallPhase);

        verify(historyWriter).appendInternalRecoveryHint(eq(turnState.getContext()), contains("Repeated tool call"));
    }

    @Test
    void warningHintTextDoesNotClaimAllowedCallWasBlocked() {
        ToolExecutionPhase phase = phase();
        TurnState turnState = buildTurnState();
        Message.ToolCall toolCall = readCall("tc-repeat");
        repeatGuard.afterOutcome(turnState, toolCall, success(toolCall, "first"));
        when(toolExecutor.execute(turnState.getContext(), toolCall)).thenReturn(success(toolCall, "second"));

        phase.execute(turnState, response(toolCall), historyWriter, llmCallPhase);

        verify(historyWriter).appendInternalRecoveryHint(eq(turnState.getContext()), contains("allowed this time"));
        verify(historyWriter, never()).appendInternalRecoveryHint(eq(turnState.getContext()),
                contains("blocked by repeat guard"));
    }

    @Test
    void warnHintIsAppendedOnlyAfterAllToolResultsInBatch() {
        ToolExecutionPhase phase = phase();
        TurnState turnState = buildTurnState();
        HistoryWriter realHistoryWriter = new DefaultHistoryWriter(clock);
        Message.ToolCall repeated = readCall("tc-repeat");
        Message.ToolCall second = readCall("tc-second", "pom.xml");
        repeatGuard.afterOutcome(turnState, repeated, success(repeated, "first"));
        when(toolExecutor.execute(turnState.getContext(), repeated)).thenReturn(success(repeated, "second"));
        when(toolExecutor.execute(turnState.getContext(), second)).thenReturn(success(second, "pom"));

        phase.execute(turnState, LlmResponse.builder().toolCalls(List.of(repeated, second)).build(),
                realHistoryWriter, llmCallPhase);

        assertMessageOrder(turnState.getContext().getMessages(),
                "assistant", "tool:tc-repeat", "tool:tc-second", "user:tool_recovery");
    }

    @Test
    void warnHintIsNotInterleavedWhenWarnedExecutionFailsAndRecoveryHintIsInjected() {
        ToolExecutionPhase phase = phase();
        TurnState turnState = buildTurnState();
        HistoryWriter realHistoryWriter = new DefaultHistoryWriter(clock);
        Message.ToolCall repeated = readCall("tc-repeat");
        Message.ToolCall second = readCall("tc-second", "pom.xml");
        repeatGuard.afterOutcome(turnState, repeated, success(repeated, "first"));
        when(toolExecutor.execute(turnState.getContext(), repeated)).thenReturn(failure(repeated, "boom"));
        when(failurePolicy.evaluate(any(), any(), any()))
                .thenReturn(new ToolFailurePolicy.Verdict.RecoveryHint("recover safely", "fp", "REPEAT_GUARD"));

        phase.execute(turnState, LlmResponse.builder().toolCalls(List.of(repeated, second)).build(),
                realHistoryWriter, llmCallPhase);

        assertMessageOrder(turnState.getContext().getMessages(),
                "assistant", "tool:tc-repeat", "tool:tc-second", "user:tool_recovery");
        assertEquals("recover safely", turnState.getContext().getMessages().get(3).getContent());
    }

    @Test
    void guardDoesNotBypassPlanModePolicyDenial() {
        planService.activatePlanMode(new SessionIdentity("web", "chat-1"), "chat-1", null);
        ToolExecutionPhase phase = phaseWithPlanMode();
        TurnState turnState = buildTurnState();
        Message.ToolCall shell = Message.ToolCall.builder()
                .id("tc-shell")
                .name(ToolNames.SHELL)
                .arguments(Map.of("command", "pwd"))
                .build();
        recordTwoSuccessfulRepeats(turnState, shell);
        when(failurePolicy.evaluate(any(), any(), any())).thenReturn(new ToolFailurePolicy.Verdict.Ok());

        phase.execute(turnState, response(shell), historyWriter, llmCallPhase);

        verify(toolExecutor, never()).execute(any(), any());
        ToolResult result = turnState.getContext().getToolResults().get("tc-shell");
        assertEquals(ToolFailureKind.POLICY_DENIED, result.getFailureKind());
        assertTrue(result.getError().contains("Plan mode is active"));
    }

    @Test
    void repeatGuardFingerprintFailureDoesNotBreakTurn() {
        ToolExecutionPhase phase = phase();
        TurnState turnState = buildTurnState();
        Message.ToolCall invalidPath = Message.ToolCall.builder()
                .id("tc-invalid")
                .name(ToolNames.FILESYSTEM)
                .arguments(Map.of("operation", "read_file", "path", "bad\u0000path"))
                .build();
        when(toolExecutor.execute(turnState.getContext(), invalidPath))
                .thenReturn(failure(invalidPath, "Invalid path"));

        assertDoesNotThrow(() -> phase.execute(turnState, response(invalidPath), historyWriter, llmCallPhase));

        verify(toolExecutor).execute(turnState.getContext(), invalidPath);
        assertEquals(ToolFailureKind.EXECUTION_FAILED,
                turnState.getContext().getToolResults().get("tc-invalid").getFailureKind());
    }

    @Test
    void planModePolicyDenialsDoNotPoisonRepeatGuardAfterPlanModeEnds() {
        SessionIdentity sessionIdentity = new SessionIdentity("web", "chat-1");
        planService.activatePlanMode(sessionIdentity, "chat-1", null);
        ToolExecutionPhase phase = phaseWithPlanMode();
        TurnState turnState = buildTurnState();
        Message.ToolCall shell = Message.ToolCall.builder()
                .id("tc-shell")
                .name(ToolNames.SHELL)
                .arguments(Map.of("command", "pwd"))
                .build();
        when(failurePolicy.evaluate(any(), any(), any())).thenReturn(new ToolFailurePolicy.Verdict.Ok());

        phase.execute(turnState, response(shell), historyWriter, llmCallPhase);
        phase.execute(turnState, response(shell), historyWriter, llmCallPhase);
        planService.deactivatePlanMode(sessionIdentity);
        when(toolExecutor.execute(turnState.getContext(), shell)).thenReturn(success(shell, "ok"));

        phase.execute(turnState, response(shell), historyWriter, llmCallPhase);

        verify(toolExecutor).execute(turnState.getContext(), shell);
        assertTrue(turnState.getContext().getToolResults().get("tc-shell").isSuccess());
    }

    @Test
    void repeatGuardStopEmitsRepeatGuardStopReason() {
        RuntimeEventService runtimeEventService = new RuntimeEventService(clock);
        ToolExecutionPhase phase = new ToolExecutionPhase(toolExecutor, failurePolicy, runtimeEventService, null,
                null, null, clock, null, repeatGuard);
        TurnState turnState = buildTurnState();
        Message.ToolCall toolCall = readCall("tc-repeat");
        recordTwoSuccessfulRepeats(turnState, toolCall);
        for (int index = 0; index < ToolRepeatGuardSettings.defaults().maxBlockedRepeatsPerTurn(); index++) {
            turnState.getToolUseLedger().incrementBlockedRepeatCount();
        }
        when(failurePolicy.evaluate(any(), any(), any()))
                .thenReturn(new ToolFailurePolicy.Verdict.StopTurn(ToolRepeatGuard.STOP_TURN_REASON));
        LlmCallPhase eventLlmCallPhase = buildLlmCallPhaseWithEvents(runtimeEventService);

        ToolExecutionPhase.ToolBatchOutcome outcome = phase.execute(turnState, response(toolCall), historyWriter,
                eventLlmCallPhase);

        assertInstanceOf(ToolExecutionPhase.ToolBatchOutcome.StopTurn.class, outcome);
        @SuppressWarnings("unchecked")
        List<RuntimeEvent> events = (List<RuntimeEvent>) turnState.getContext()
                .getAttribute(ContextAttributes.RUNTIME_EVENTS);
        RuntimeEvent turnFinished = events.stream()
                .filter(event -> event.type() == RuntimeEventType.TURN_FINISHED)
                .findFirst()
                .orElseThrow();
        assertEquals("repeat_guard_stop", turnFinished.payload().get("reason"));
    }

    @Test
    void shadowWouldBlockEmitsTelemetryWithoutBlockingExecutor() {
        RuntimeEventService runtimeEventService = new RuntimeEventService(clock);
        ToolRepeatGuard shadowGuard = new ToolRepeatGuard(
                new ToolUseFingerprintService(),
                new ToolRepeatGuardSettings(true, true, 1, 1, 4,
                        java.time.Duration.ofSeconds(60), java.time.Duration.ofMinutes(120)),
                clock);
        ToolExecutionPhase phase = new ToolExecutionPhase(toolExecutor, failurePolicy, runtimeEventService, null,
                null, null, clock, null, shadowGuard);
        TurnState turnState = buildTurnState();
        Message.ToolCall toolCall = readCall("tc-repeat");
        shadowGuard.afterOutcome(turnState, toolCall, success(toolCall, "same output"));
        when(toolExecutor.execute(turnState.getContext(), toolCall)).thenReturn(success(toolCall, "same output"));

        phase.execute(turnState, response(toolCall), historyWriter, buildLlmCallPhaseWithEvents(runtimeEventService));

        verify(toolExecutor).execute(turnState.getContext(), toolCall);
        @SuppressWarnings("unchecked")
        List<RuntimeEvent> events = (List<RuntimeEvent>) turnState.getContext()
                .getAttribute(ContextAttributes.RUNTIME_EVENTS);
        RuntimeEvent toolFinished = events.stream()
                .filter(event -> event.type() == RuntimeEventType.TOOL_FINISHED)
                .findFirst()
                .orElseThrow();
        assertEquals("WOULD_BLOCK_SHADOW", toolFinished.payload().get("repeatGuardDecision"));
        assertEquals("filesystem", toolFinished.payload().get("tool"));
        assertTrue(String.valueOf(toolFinished.payload().get("repeatFingerprint")).contains("filesystem:OBSERVE"));
    }

    @Test
    void pendingWarningHintSuppressedAfterSameDomainMutation() {
        ToolExecutionPhase phase = phase();
        TurnState turnState = buildTurnState();
        HistoryWriter realHistoryWriter = new DefaultHistoryWriter(clock);
        Message.ToolCall repeated = readCall("tc-repeat");
        Message.ToolCall write = Message.ToolCall.builder()
                .id("tc-write")
                .name(ToolNames.FILESYSTEM)
                .arguments(Map.of("operation", "write_file", "path", "README.md", "content", "updated"))
                .build();
        repeatGuard.afterOutcome(turnState, repeated, success(repeated, "first"));
        when(toolExecutor.execute(turnState.getContext(), repeated)).thenReturn(success(repeated, "second"));
        when(toolExecutor.execute(turnState.getContext(), write)).thenReturn(success(write, "updated"));

        phase.execute(turnState, LlmResponse.builder().toolCalls(List.of(repeated, write)).build(),
                realHistoryWriter, llmCallPhase);

        assertMessageOrder(turnState.getContext().getMessages(),
                "assistant", "tool:tc-repeat", "tool:tc-write");
    }

    private LlmCallPhase buildLlmCallPhaseWithEvents(RuntimeEventService runtimeEventService) {
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(2_000_000_000);
        return new LlmCallPhase(
                mock(LlmPort.class),
                mock(ConversationViewBuilder.class),
                modelSelectionService,
                null,
                mock(LlmRequestPreflightPhase.class),
                mock(ContextCompactionCoordinator.class),
                runtimeEventService,
                null,
                null,
                null,
                clock);
    }

    private ToolExecutionPhase phase() {
        return new ToolExecutionPhase(toolExecutor, failurePolicy, null, null, null, null, clock, null, repeatGuard);
    }

    private ToolExecutionPhase phaseWithPlanMode() {
        return new ToolExecutionPhase(toolExecutor, failurePolicy, null, null, null, null, clock,
                planModeToolRestrictionService, repeatGuard);
    }

    private TurnState buildTurnState() {
        AgentSession session = AgentSession.builder()
                .id("sess-1")
                .channelType("web")
                .chatId("chat-1")
                .messages(new ArrayList<>())
                .build();
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .maxIterations(1)
                .currentIteration(0)
                .build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE, LlmResponse.builder().build());
        return new TurnState(context, null, 4, 4, clock.instant().plusSeconds(60), false, true, false, 1, 10L,
                true);
    }

    private LlmResponse response(Message.ToolCall toolCall) {
        return LlmResponse.builder().toolCalls(List.of(toolCall)).build();
    }

    private Message.ToolCall readCall(String id) {
        return readCall(id, "README.md");
    }

    private Message.ToolCall readCall(String id, String path) {
        return Message.ToolCall.builder()
                .id(id)
                .name("filesystem")
                .arguments(Map.of("operation", "read_file", "path", path))
                .build();
    }

    private ToolExecutionOutcome success(Message.ToolCall toolCall, String content) {
        return new ToolExecutionOutcome(toolCall.getId(), toolCall.getName(), ToolResult.success(content), content,
                false, null);
    }

    private ToolExecutionOutcome failure(Message.ToolCall toolCall, String error) {
        return new ToolExecutionOutcome(toolCall.getId(), toolCall.getName(),
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, error), error, false, null);
    }

    private void assertMessageOrder(List<Message> messages, String... expected) {
        assertEquals(expected.length, messages.size());
        for (int index = 0; index < expected.length; index++) {
            Message message = messages.get(index);
            String actual = message.getRole();
            if ("tool".equals(message.getRole())) {
                actual += ":" + message.getToolCallId();
            }
            if ("user".equals(message.getRole())) {
                actual += ":" + message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL_KIND);
            }
            assertEquals(expected[index], actual);
        }
    }

    private void recordTwoSuccessfulRepeats(TurnState turnState, Message.ToolCall toolCall) {
        repeatGuard.afterOutcome(turnState, toolCall, success(toolCall, "same output"));
        repeatGuard.afterOutcome(turnState, toolCall, success(toolCall, "same output"));
    }
}
