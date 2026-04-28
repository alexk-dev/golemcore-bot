package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmProviderMetadataKeys;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.context.compaction.ContextCompactionPolicy;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolRepeatGuard;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolRepeatGuardSettings;
import me.golemcore.bot.domain.system.toolloop.repeat.AutonomyWorkKey;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseFingerprint;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseFingerprintService;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseLedger;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseLedgerStore;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseRecord;
import me.golemcore.bot.domain.system.toolloop.view.ConversationView;
import me.golemcore.bot.infrastructure.toolloop.repeat.JsonToolUseLedgerStore;
import me.golemcore.bot.port.outbound.StoragePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultToolLoopSystemToolExecutionTest extends DefaultToolLoopSystemFixture {

    @Test
    void repeatedSuccessfulReadIsBlockedAndModelGetsRecoveryHint() {
        AgentContext context = buildContext();
        Message.ToolCall readCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name("filesystem")
                .arguments(Map.of("operation", "read_file", "path", "README.md"))
                .build();
        DefaultToolLoopSystem guardedSystem = DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(viewBuilder)
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnSettings))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .contextCompactionPolicy(new ContextCompactionPolicy(runtimeConfigService, modelSelectionService))
                .repeatGuard(new ToolRepeatGuard(new ToolUseFingerprintService(), ToolRepeatGuardSettings.defaults(),
                        clock))
                .clock(clock)
                .build();

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(readCall))))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(readCall))))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(readCall))))
                .thenReturn(CompletableFuture.completedFuture(finalResponse(CONTENT_DONE)));
        when(toolExecutor.execute(any(), any())).thenReturn(new ToolExecutionOutcome(
                TOOL_CALL_ID, "filesystem", ToolResult.success("read result"), "read result", false, null));

        ToolLoopTurnResult result = guardedSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(3, result.toolExecutions());
        verify(toolExecutor, times(2)).execute(any(), any());
        ToolResult blockedResult = context.getToolResults().get(TOOL_CALL_ID);
        assertEquals(ToolFailureKind.REPEATED_TOOL_USE_BLOCKED, blockedResult.getFailureKind());
        assertTrue(blockedResult.getError().contains("Repeated tool call blocked"));
    }

    @Test
    void autoRunLoadsPriorTaskLedgerAndBlocksSameObservationAcrossRuns() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.AUTO_MODE, true);
        context.setAttribute(ContextAttributes.CONVERSATION_KEY, "web:chat-1");
        context.setAttribute(ContextAttributes.AUTO_GOAL_ID, "goal-1");
        context.setAttribute(ContextAttributes.AUTO_TASK_ID, "task-1");
        Message.ToolCall readCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name("filesystem")
                .arguments(Map.of("operation", "read_file", "path", "README.md"))
                .build();
        ToolUseFingerprintService fingerprintService = new ToolUseFingerprintService();
        ToolRepeatGuard repeatGuard = new ToolRepeatGuard(fingerprintService, ToolRepeatGuardSettings.defaults(),
                clock);
        ToolUseLedgerStore ledgerStore = new JsonToolUseLedgerStore(
                storagePort(), new ObjectMapper().findAndRegisterModules(), clock);
        ToolUseLedger priorLedger = new ToolUseLedger();
        ToolUseFingerprint fingerprint = fingerprintService.fingerprint(readCall);
        priorLedger.recordUse(priorObservation(fingerprint, "sha256:first"));
        priorLedger.recordUse(priorObservation(fingerprint, "sha256:second"));
        ledgerStore.save(new AutonomyWorkKey("web:chat-1", "goal-1", "task-1", null), priorLedger);

        DefaultToolLoopSystem guardedSystem = DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(viewBuilder)
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnSettings))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .contextCompactionPolicy(new ContextCompactionPolicy(runtimeConfigService, modelSelectionService))
                .repeatGuard(repeatGuard)
                .toolUseLedgerStore(ledgerStore)
                .clock(clock)
                .build();

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(readCall))))
                .thenReturn(CompletableFuture.completedFuture(finalResponse(CONTENT_DONE)));

        ToolLoopTurnResult result = guardedSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(1, result.toolExecutions());
        verify(toolExecutor, never()).execute(any(), any());
        assertEquals(3, ledgerStore.load(new AutonomyWorkKey("web:chat-1", "goal-1", "task-1", null),
                Duration.ofMinutes(120)).orElseThrow().recordsFor(fingerprint).size());
    }

    @Test
    void autoRunDoesNotStopImmediatelyBecausePreviousRunAccumulatedBlockedRepeats() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.AUTO_MODE, true);
        context.setAttribute(ContextAttributes.CONVERSATION_KEY, "web:chat-1");
        context.setAttribute(ContextAttributes.AUTO_GOAL_ID, "goal-1");
        context.setAttribute(ContextAttributes.AUTO_TASK_ID, "task-1");
        Message.ToolCall readCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name("filesystem")
                .arguments(Map.of("operation", "read_file", "path", "README.md"))
                .build();
        ToolUseFingerprintService fingerprintService = new ToolUseFingerprintService();
        ToolRepeatGuard repeatGuard = new ToolRepeatGuard(fingerprintService, ToolRepeatGuardSettings.defaults(),
                clock);
        ToolUseLedgerStore ledgerStore = new JsonToolUseLedgerStore(
                storagePort(), new ObjectMapper().findAndRegisterModules(), clock);
        ToolUseLedger priorLedger = new ToolUseLedger();
        ToolUseFingerprint fingerprint = fingerprintService.fingerprint(readCall);
        priorLedger.recordUse(priorObservation(fingerprint, "sha256:first"));
        priorLedger.recordUse(priorObservation(fingerprint, "sha256:second"));
        for (int index = 0; index < ToolRepeatGuardSettings.defaults().maxBlockedRepeatsPerTurn(); index++) {
            priorLedger.incrementBlockedRepeatCount();
        }
        ledgerStore.save(new AutonomyWorkKey("web:chat-1", "goal-1", "task-1", null), priorLedger);

        DefaultToolLoopSystem guardedSystem = DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(viewBuilder)
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnSettings))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .contextCompactionPolicy(new ContextCompactionPolicy(runtimeConfigService, modelSelectionService))
                .repeatGuard(repeatGuard)
                .toolUseLedgerStore(ledgerStore)
                .clock(clock)
                .build();

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(readCall))))
                .thenReturn(CompletableFuture.completedFuture(finalResponse(CONTENT_DONE)));

        ToolLoopTurnResult result = guardedSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(1, result.toolExecutions());
        verify(toolExecutor, never()).execute(any(), any());
        ToolUseLedger loaded = ledgerStore.load(
                new AutonomyWorkKey("web:chat-1", "goal-1", "task-1", null),
                Duration.ofMinutes(120)).orElseThrow();
        assertEquals(0, loaded.getBlockedRepeatCount());
    }

    @Test
    void autoRunAllowsSameShellAfterLedgerTtlExpired() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.AUTO_MODE, true);
        context.setAttribute(ContextAttributes.CONVERSATION_KEY, "web:chat-1");
        context.setAttribute(ContextAttributes.AUTO_GOAL_ID, "goal-1");
        context.setAttribute(ContextAttributes.AUTO_TASK_ID, "task-1");
        Message.ToolCall shellCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name("shell")
                .arguments(Map.of("command", "git status"))
                .build();
        ToolUseFingerprintService fingerprintService = new ToolUseFingerprintService();
        ToolRepeatGuard repeatGuard = new ToolRepeatGuard(fingerprintService, ToolRepeatGuardSettings.defaults(),
                clock);
        ToolUseLedgerStore ledgerStore = new JsonToolUseLedgerStore(
                storagePort(), new ObjectMapper().findAndRegisterModules(), clock);
        ToolUseLedger priorLedger = new ToolUseLedger();
        ToolUseFingerprint fingerprint = fingerprintService.fingerprint(shellCall);
        Instant expired = clock.instant().minus(Duration.ofMinutes(121));
        priorLedger.recordUse(priorShell(fingerprint, expired, "sha256:first"));
        priorLedger.recordUse(priorShell(fingerprint, expired, "sha256:second"));
        ledgerStore.save(new AutonomyWorkKey("web:chat-1", "goal-1", "task-1", null), priorLedger);

        DefaultToolLoopSystem guardedSystem = DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(viewBuilder)
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnSettings))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .contextCompactionPolicy(new ContextCompactionPolicy(runtimeConfigService, modelSelectionService))
                .repeatGuard(repeatGuard)
                .toolUseLedgerStore(ledgerStore)
                .clock(clock)
                .build();

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(shellCall))))
                .thenReturn(CompletableFuture.completedFuture(finalResponse(CONTENT_DONE)));
        when(toolExecutor.execute(context, shellCall)).thenReturn(new ToolExecutionOutcome(
                TOOL_CALL_ID, "shell", ToolResult.success("clean"), "clean", false, null));

        ToolLoopTurnResult result = guardedSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        verify(toolExecutor).execute(context, shellCall);
    }

    @Test
    void autonomousCodingLoopMayRepeatSameShellCommandAfterFileEdit() {
        AgentContext context = buildContext();
        Message.ToolCall firstWrite = Message.ToolCall.builder()
                .id("tc-write-1")
                .name("filesystem")
                .arguments(Map.of("operation", "write_file", "path", "src/main/java/App.java",
                        "content", "class App {}"))
                .build();
        Message.ToolCall firstShell = Message.ToolCall.builder()
                .id("tc-shell-1")
                .name("shell")
                .arguments(Map.of("command", "mvn test", "cwd", "."))
                .build();
        Message.ToolCall secondWrite = Message.ToolCall.builder()
                .id("tc-write-2")
                .name("filesystem")
                .arguments(Map.of("operation", "write_file", "path", "src/main/java/App.java",
                        "content", "class App { String value; }"))
                .build();
        Message.ToolCall secondShell = Message.ToolCall.builder()
                .id("tc-shell-2")
                .name("shell")
                .arguments(Map.of("command", "mvn test", "cwd", "."))
                .build();
        DefaultToolLoopSystem guardedSystem = DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(viewBuilder)
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnSettings))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .contextCompactionPolicy(new ContextCompactionPolicy(runtimeConfigService, modelSelectionService))
                .repeatGuard(new ToolRepeatGuard(new ToolUseFingerprintService(), ToolRepeatGuardSettings.defaults(),
                        clock))
                .clock(clock)
                .build();

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(firstWrite))))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(firstShell))))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(secondWrite))))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(secondShell))))
                .thenReturn(CompletableFuture.completedFuture(finalResponse(CONTENT_DONE)));
        when(toolExecutor.execute(eq(context), any())).thenAnswer(invocation -> {
            Message.ToolCall call = invocation.getArgument(1, Message.ToolCall.class);
            return new ToolExecutionOutcome(
                    call.getId(), call.getName(), ToolResult.success("ok"), "ok", false, null);
        });

        ToolLoopTurnResult result = guardedSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        verify(toolExecutor, times(2)).execute(eq(context),
                argThat(call -> call != null && "shell".equals(call.getName())));
        assertTrue(context.getToolResults().get("tc-shell-1").isSuccess());
        assertTrue(context.getToolResults().get("tc-shell-2").isSuccess());
    }

    @Test
    void shouldPublishIntentAndFlushProgressThroughTurnProgressService() {
        AgentContext context = buildContext();
        DefaultToolLoopSystem progressSystem = buildSystemWithTurnProgress();
        stubRuntimeConfigDefaults();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(tc))))
                .thenReturn(CompletableFuture.completedFuture(finalResponse(CONTENT_DONE)));
        when(toolExecutor.execute(any(), any())).thenReturn(new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null));

        ToolLoopTurnResult result = progressSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        verify(turnProgressService).maybePublishIntent(eq(context), any(LlmResponse.class));
        verify(turnProgressService).recordToolExecution(eq(context), eq(tc), any(ToolExecutionOutcome.class), eq(0L));
        verify(turnProgressService).flushBufferedTools(context, "final_answer");
        verify(turnProgressService).clearProgress(context);
    }

    @Test
    void shouldPublishProgressNoticeWhenToolAttachmentFallbackWasApplied() {
        AgentContext context = buildContext();
        DefaultToolLoopSystem progressSystem = buildSystemWithTurnProgress();
        stubRuntimeConfigDefaults();

        LlmResponse response = LlmResponse.builder()
                .content("Recovered")
                .providerMetadata(Map.of(
                        LlmProviderMetadataKeys.TOOL_ATTACHMENT_FALLBACK_APPLIED, true,
                        LlmProviderMetadataKeys.TOOL_ATTACHMENT_FALLBACK_REASON,
                        LlmProviderMetadataKeys.TOOL_ATTACHMENT_FALLBACK_REASON_OVERSIZE_INVALID_JSON))
                .build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        ToolLoopTurnResult result = progressSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        verify(turnProgressService).publishSummary(
                eq(context),
                eq("Request was too large for inline tool images, so I retried without them."),
                eq(Map.of(
                        "kind", "tool_attachment_fallback",
                        "reason", LlmProviderMetadataKeys.TOOL_ATTACHMENT_FALLBACK_REASON_OVERSIZE_INVALID_JSON)));
        verify(turnProgressService).flushBufferedTools(context, "final_answer");
        verify(turnProgressService).clearProgress(context);
    }

    @Test
    void shouldHandleMissingToolMetadataInRuntimeEventPayloads() {
        DefaultToolLoopSystem runtimeEventSystem = buildSystemWithRuntimeEvents();
        AgentContext context = buildContext();
        Message.ToolCall tc = Message.ToolCall.builder()
                .id(null)
                .name(null)
                .arguments(Map.of("query", "test"))
                .build();

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(tc))))
                .thenReturn(CompletableFuture.completedFuture(finalResponse(CONTENT_DONE)));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                null, null, ToolResult.success("ok"), "ok", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = assertDoesNotThrow(() -> runtimeEventSystem.processTurn(context));

        assertTrue(result.finalAnswerReady());
        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        assertNotNull(events);

        RuntimeEvent toolStarted = events.stream()
                .filter(event -> event.type() == RuntimeEventType.TOOL_STARTED)
                .findFirst()
                .orElseThrow();
        assertTrue(toolStarted.payload().containsKey("toolCallId"));
        assertNull(toolStarted.payload().get("toolCallId"));
        assertNull(toolStarted.payload().get("tool"));

        RuntimeEvent toolFinished = events.stream()
                .filter(event -> event.type() == RuntimeEventType.TOOL_FINISHED)
                .findFirst()
                .orElseThrow();
        assertNull(toolFinished.payload().get("toolCallId"));
        assertNull(toolFinished.payload().get("tool"));
    }

    @Test
    void shouldEmitContextHygieneRuntimeEventForLlmCall() {
        DefaultToolLoopSystem runtimeEventSystem = buildSystemWithRuntimeEvents();
        AgentContext context = buildContext();
        Map<String, Object> hygieneReport = Map.of(
                "rawTokens", 1_000,
                "projectedTokens", 300,
                "systemPromptTokens", 100);
        when(viewBuilder.buildView(any(), any())).thenAnswer(invocation -> {
            AgentContext invocationContext = invocation.getArgument(0);
            invocationContext.setAttribute(ContextAttributes.CONTEXT_HYGIENE_REPORT, hygieneReport);
            return ConversationView.ofMessages(invocationContext.getMessages());
        });
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(finalResponse(CONTENT_DONE)));

        ToolLoopTurnResult result = runtimeEventSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        RuntimeEvent hygieneEvent = events.stream()
                .filter(event -> event.type() == RuntimeEventType.CONTEXT_HYGIENE)
                .findFirst()
                .orElseThrow();
        assertEquals(hygieneReport, hygieneEvent.payload().get("contextHygiene"));
        assertEquals(MODEL_BALANCED, hygieneEvent.payload().get("model"));
    }

    @Test
    void shouldHandleToolExecutionException() {
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        LlmResponse finalResp = finalResponse("Recovered");

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(withTools))
                .thenReturn(CompletableFuture.completedFuture(finalResp));

        when(toolExecutor.execute(any(), any())).thenThrow(new RuntimeException("Tool crashed"));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(1, result.toolExecutions());
    }

    @Test
    void shouldAccumulateAttachmentsFromToolResults() {
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        LlmResponse finalResp = finalResponse("Here is the image");

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(withTools))
                .thenReturn(CompletableFuture.completedFuture(finalResp));

        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1, 2, 3 })
                .filename("screenshot.png")
                .build();
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, attachment);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        OutgoingResponse outgoing = result.context().getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals(1, outgoing.getAttachments().size());
    }

    @Test
    void shouldHandleNullToolExecutionOutcome() {
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        LlmResponse finalResp = finalResponse(CONTENT_DONE);

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(withTools))
                .thenReturn(CompletableFuture.completedFuture(finalResp));

        when(toolExecutor.execute(any(), any())).thenReturn(null);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(2, result.llmCalls());
    }

    @Test
    void shouldMergeAttachmentsWithExistingOutgoingResponse() {
        AgentContext context = buildContext();

        OutgoingResponse existing = OutgoingResponse.builder()
                .text("Existing text")
                .voiceRequested(true)
                .voiceText("Voice text")
                .build();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, existing);

        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);
        LlmResponse withTools = toolCallResponse(List.of(tc));
        LlmResponse finalResp = finalResponse(CONTENT_DONE);

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(withTools))
                .thenReturn(CompletableFuture.completedFuture(finalResp));

        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1 })
                .build();
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, attachment);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        OutgoingResponse outgoing = result.context().getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing);
        assertEquals(1, outgoing.getAttachments().size());
    }

    @Test
    void shouldHandleNullToolResultInOutcome() {
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        LlmResponse finalResp = finalResponse(CONTENT_DONE);

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(withTools))
                .thenReturn(CompletableFuture.completedFuture(finalResp));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, null, "no result", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    private ToolUseRecord priorObservation(ToolUseFingerprint fingerprint, String outputDigest) {
        Instant finishedAt = clock.instant().minusSeconds(30);
        return new ToolUseRecord(
                fingerprint,
                finishedAt.minusMillis(10),
                finishedAt,
                true,
                null,
                outputDigest,
                0,
                false,
                null);
    }

    private ToolUseRecord priorShell(ToolUseFingerprint fingerprint, Instant finishedAt, String outputDigest) {
        return new ToolUseRecord(
                fingerprint,
                finishedAt.minusMillis(10),
                finishedAt,
                true,
                null,
                outputDigest,
                0,
                false,
                null);
    }

    private StoragePort storagePort() {
        Map<String, String> files = new ConcurrentHashMap<>();
        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.exists(anyString(), anyString()))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(files.containsKey(
                        invocation.getArgument(0, String.class) + "/" + invocation.getArgument(1, String.class))));
        when(storagePort.getText(anyString(), anyString()))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(files.get(
                        invocation.getArgument(0, String.class) + "/" + invocation.getArgument(1, String.class))));
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    files.put(invocation.getArgument(0, String.class) + "/"
                            + invocation.getArgument(1, String.class), invocation.getArgument(2, String.class));
                    return CompletableFuture.completedFuture(null);
                });
        return storagePort;
    }
}
