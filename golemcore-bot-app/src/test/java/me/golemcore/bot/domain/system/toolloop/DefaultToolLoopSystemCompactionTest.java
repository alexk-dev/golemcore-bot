package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.context.compaction.CompactionOrchestrationService;
import me.golemcore.bot.domain.context.compaction.ContextCompactionPolicy;
import me.golemcore.bot.domain.context.compaction.ContextTokenEstimator;
import me.golemcore.bot.domain.system.LlmErrorClassifier;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultToolLoopSystemCompactionTest extends DefaultToolLoopSystemFixture {

    @Test
    void shouldCompactBeforeCallingProviderWhenFullRequestExceedsContextBudget() {
        AgentSession session = AgentSession.builder()
                .id("sess-preflight")
                .chatId("chat-1")
                .messages(new ArrayList<>())
                .build();
        Message oldMessage = Message.builder()
                .role("user")
                .content("old " + "x".repeat(100))
                .timestamp(clock.instant())
                .build();
        Message recentMessage = Message.builder()
                .role("user")
                .content("recent")
                .timestamp(clock.instant())
                .build();
        session.addMessage(oldMessage);
        session.addMessage(recentMessage);
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();
        context.setSystemPrompt("p".repeat(2000));

        when(runtimeConfigService.getTurnMaxLlmCalls()).thenReturn(4);
        when(runtimeConfigService.getTurnMaxToolExecutions()).thenReturn(4);
        when(runtimeConfigService.getTurnDeadline()).thenReturn(Duration.ofMinutes(1));
        when(runtimeConfigService.isTurnAutoRetryEnabled()).thenReturn(false);
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("model_ratio");
        when(runtimeConfigService.getCompactionModelThresholdRatio()).thenReturn(0.8d);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(10_000);
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(1);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(true);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(1_000);

        CompactionOrchestrationService compactionService = mock(CompactionOrchestrationService.class);
        when(compactionService.compact("sess-preflight", CompactionReason.REQUEST_PREFLIGHT, 1))
                .thenAnswer(invocation -> {
                    session.getMessages().clear();
                    session.getMessages().add(recentMessage);
                    context.setSystemPrompt("compact prompt");
                    return CompactionResult.builder()
                            .removed(1)
                            .usedSummary(false)
                            .build();
                });
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(finalResponse(CONTENT_DONE)));

        DefaultToolLoopSystem preflightSystem = DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(new DefaultRequestViewBuilder())
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnSettings))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .runtimeConfigService(runtimeConfigService)
                .compactionOrchestrationService(compactionService)
                .contextTokenEstimator(new ContextTokenEstimator())
                .contextCompactionPolicy(new ContextCompactionPolicy(runtimeConfigService, modelSelectionService))
                .clock(clock)
                .build();

        ToolLoopTurnResult result = preflightSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        verify(compactionService).compact("sess-preflight", CompactionReason.REQUEST_PREFLIGHT, 1);
        verify(viewBuilder, never()).buildView(any(), any());
        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        assertNotNull(diagnostics);
        assertTrue(Boolean.TRUE.equals(diagnostics.get("compactionAttempted")));
        assertEquals(1, diagnostics.get("compactionRemoved"));
        org.mockito.ArgumentCaptor<me.golemcore.bot.domain.model.LlmRequest> requestCaptor = org.mockito.ArgumentCaptor
                .forClass(me.golemcore.bot.domain.model.LlmRequest.class);
        verify(llmPort).chat(requestCaptor.capture());
        assertEquals("compact prompt", requestCaptor.getValue().getSystemPrompt());
        assertEquals(1, requestCaptor.getValue().getMessages().size());
        assertEquals("recent", requestCaptor.getValue().getMessages().get(0).getContent());
    }

    @Test
    void shouldUseContextCompactionPolicyForPreflightEvenWhenToolLoopRuntimeConfigIsAbsent() {
        AgentSession session = AgentSession.builder()
                .id("sess-policy-only")
                .chatId("chat-1")
                .messages(new ArrayList<>())
                .build();
        Message oldMessage = Message.builder()
                .role("user")
                .content("old " + "x".repeat(100))
                .timestamp(clock.instant())
                .build();
        Message recentMessage = Message.builder()
                .role("assistant")
                .content("recent")
                .timestamp(clock.instant())
                .build();
        session.addMessage(oldMessage);
        session.addMessage(recentMessage);
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();
        context.setSystemPrompt("p".repeat(8_000));

        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("model_ratio");
        when(runtimeConfigService.getCompactionModelThresholdRatio()).thenReturn(0.8d);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(10_000);
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(1);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(true);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(1_000);

        CompactionOrchestrationService compactionService = mock(CompactionOrchestrationService.class);
        when(compactionService.compact("sess-policy-only", CompactionReason.REQUEST_PREFLIGHT, 1))
                .thenAnswer(invocation -> {
                    session.getMessages().clear();
                    session.getMessages().add(recentMessage);
                    context.setSystemPrompt("compact prompt");
                    return CompactionResult.builder()
                            .removed(1)
                            .usedSummary(false)
                            .build();
                });
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(finalResponse(CONTENT_DONE)));

        DefaultToolLoopSystem preflightSystem = DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(new DefaultRequestViewBuilder())
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnSettings))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .compactionOrchestrationService(compactionService)
                .contextTokenEstimator(new ContextTokenEstimator())
                .contextCompactionPolicy(new ContextCompactionPolicy(runtimeConfigService, modelSelectionService))
                .clock(clock)
                .build();

        ToolLoopTurnResult result = preflightSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        verify(compactionService).compact("sess-policy-only", CompactionReason.REQUEST_PREFLIGHT, 1);
    }

    @Test
    void shouldUseContextCompactionPolicyForOverflowRecoveryEvenWhenToolLoopRuntimeConfigIsAbsent() {
        AgentSession session = AgentSession.builder()
                .id("sess-overflow-policy-only")
                .chatId("chat-1")
                .messages(new ArrayList<>())
                .build();
        for (int i = 0; i < 4; i++) {
            session.addMessage(Message.builder()
                    .role(i % 2 == 0 ? "user" : "assistant")
                    .content("message-" + i)
                    .timestamp(clock.instant())
                    .build());
        }
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("model_ratio");
        when(runtimeConfigService.getCompactionModelThresholdRatio()).thenReturn(0.8d);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(10_000);
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(2);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(true);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(2_000_000_000);

        CompactionOrchestrationService compactionService = mock(CompactionOrchestrationService.class);
        when(compactionService.compact("sess-overflow-policy-only", CompactionReason.CONTEXT_OVERFLOW_RECOVERY, 2))
                .thenAnswer(invocation -> {
                    List<Message> kept = new ArrayList<>(session.getMessages().subList(2, 4));
                    session.getMessages().clear();
                    session.getMessages().addAll(kept);
                    return CompactionResult.builder()
                            .removed(2)
                            .usedSummary(false)
                            .build();
                });
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("context length exceeded")))
                .thenReturn(CompletableFuture.completedFuture(finalResponse(CONTENT_DONE)));

        DefaultToolLoopSystem overflowSystem = DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(new DefaultRequestViewBuilder())
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnSettings))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .compactionOrchestrationService(compactionService)
                .contextTokenEstimator(new ContextTokenEstimator())
                .contextCompactionPolicy(new ContextCompactionPolicy(runtimeConfigService, modelSelectionService))
                .clock(clock)
                .build();

        ToolLoopTurnResult result = overflowSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        verify(compactionService).compact(
                "sess-overflow-policy-only", CompactionReason.CONTEXT_OVERFLOW_RECOVERY, 2);
        verify(llmPort, times(2)).chat(any());
    }

    @Test
    void shouldSurfaceOriginalOverflowWhenOverflowRecoveryCompactionThrows() {
        AgentSession session = AgentSession.builder()
                .id("sess-overflow-recovery-error")
                .chatId("chat-1")
                .messages(new ArrayList<>())
                .build();
        for (int i = 0; i < 4; i++) {
            session.addMessage(Message.builder()
                    .role(i % 2 == 0 ? "user" : "assistant")
                    .content("message-" + i)
                    .timestamp(clock.instant())
                    .build());
        }
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        when(runtimeConfigService.getTurnMaxLlmCalls()).thenReturn(4);
        when(runtimeConfigService.getTurnMaxToolExecutions()).thenReturn(4);
        when(runtimeConfigService.getTurnDeadline()).thenReturn(Duration.ofMinutes(1));
        when(runtimeConfigService.isTurnAutoRetryEnabled()).thenReturn(false);
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("model_ratio");
        when(runtimeConfigService.getCompactionModelThresholdRatio()).thenReturn(0.8d);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(10_000);
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(2);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(true);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(2_000_000_000);

        CompactionOrchestrationService compactionService = mock(CompactionOrchestrationService.class);
        when(compactionService.compact(
                "sess-overflow-recovery-error", CompactionReason.CONTEXT_OVERFLOW_RECOVERY, 2))
                .thenThrow(new IllegalStateException("summary store unavailable"));
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("context length exceeded")));

        DefaultToolLoopSystem overflowSystem = DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(new DefaultRequestViewBuilder())
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnSettings))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .runtimeConfigService(runtimeConfigService)
                .compactionOrchestrationService(compactionService)
                .contextTokenEstimator(new ContextTokenEstimator())
                .contextCompactionPolicy(new ContextCompactionPolicy(runtimeConfigService, modelSelectionService))
                .clock(clock)
                .build();

        ToolLoopTurnResult result = assertDoesNotThrow(() -> overflowSystem.processTurn(context),
                "overflow recovery failure must not escape DefaultToolLoopSystem.processTurn");

        assertFalse(result.finalAnswerReady());
        assertEquals(1, result.llmCalls());
        assertEquals(LlmErrorClassifier.CONTEXT_LENGTH_EXCEEDED,
                context.getAttribute(ContextAttributes.LLM_ERROR_CODE));
        verify(compactionService).compact(
                "sess-overflow-recovery-error", CompactionReason.CONTEXT_OVERFLOW_RECOVERY, 2);
        verify(llmPort).chat(any());

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_CONTEXT_OVERFLOW_RECOVERY);
        assertNotNull(diagnostics);
        assertEquals(true, diagnostics.get("recoveryAttempted"));
        assertEquals("error", diagnostics.get("recoveryOutcome"));
        assertEquals(0, diagnostics.get("recoveryRemoved"));
    }

    @Test
    void shouldRetryOverflowRecoveryAfterFallbackCompactionWithoutSummary() {
        AgentSession session = AgentSession.builder()
                .id("sess-overflow")
                .chatId("chat-1")
                .messages(new ArrayList<>())
                .build();
        for (int i = 0; i < 4; i++) {
            session.addMessage(Message.builder()
                    .role(i % 2 == 0 ? "user" : "assistant")
                    .content("message-" + i)
                    .timestamp(clock.instant())
                    .build());
        }
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        when(runtimeConfigService.getTurnMaxLlmCalls()).thenReturn(4);
        when(runtimeConfigService.getTurnMaxToolExecutions()).thenReturn(4);
        when(runtimeConfigService.getTurnDeadline()).thenReturn(Duration.ofMinutes(1));
        when(runtimeConfigService.isTurnAutoRetryEnabled()).thenReturn(false);
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("model_ratio");
        when(runtimeConfigService.getCompactionModelThresholdRatio()).thenReturn(0.8d);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(10_000);
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(2);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(true);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(2_000_000_000);

        CompactionOrchestrationService compactionService = mock(CompactionOrchestrationService.class);
        when(compactionService.compact("sess-overflow", CompactionReason.CONTEXT_OVERFLOW_RECOVERY, 2))
                .thenAnswer(invocation -> {
                    List<Message> kept = new ArrayList<>(session.getMessages().subList(2, 4));
                    session.getMessages().clear();
                    session.getMessages().addAll(kept);
                    return CompactionResult.builder()
                            .removed(2)
                            .usedSummary(false)
                            .build();
                });
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("context length exceeded")))
                .thenReturn(CompletableFuture.completedFuture(finalResponse(CONTENT_DONE)));

        DefaultToolLoopSystem overflowSystem = DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(new DefaultRequestViewBuilder())
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnSettings))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .runtimeConfigService(runtimeConfigService)
                .compactionOrchestrationService(compactionService)
                .contextTokenEstimator(new ContextTokenEstimator())
                .contextCompactionPolicy(new ContextCompactionPolicy(runtimeConfigService, modelSelectionService))
                .clock(clock)
                .build();

        ToolLoopTurnResult result = overflowSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        verify(compactionService).compact("sess-overflow", CompactionReason.CONTEXT_OVERFLOW_RECOVERY, 2);
        verify(llmPort, times(2)).chat(any());
    }
}
