package me.golemcore.bot.domain.system.toolloop;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.CompactionDetails;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmRequestPreflightPhaseTest extends LlmRequestPreflightPhaseFixture {

    @Test
    void shouldReturnRequestWhenWithinBudget() {
        AgentContext context = buildContext(2);
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("short")
                .messages(List.of(Message.builder().role("user").content("hi").build()))
                .build();

        LlmRequest result = phase.preflight(context, () -> request, 1);

        assertSame(request, result);
        verify(compactionService, never()).compact(any(), any(), anyInt());
        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        assertNotNull(diagnostics);
        assertEquals(false, diagnostics.get("overThreshold"));
    }

    @Test
    void shouldCompactAndRebuildRequestWhenOverBudget() {
        AgentContext context = buildContext(4);
        AtomicInteger requests = new AtomicInteger();
        when(compactionService.compact("session-1", CompactionReason.REQUEST_PREFLIGHT, 2))
                .thenAnswer(invocation -> {
                    context.getSession().getMessages().clear();
                    context.getSession().addMessage(Message.builder().role("user").content("kept").build());
                    return CompactionResult.builder()
                            .removed(2)
                            .usedSummary(false)
                            .details(CompactionDetails.builder()
                                    .reason(CompactionReason.REQUEST_PREFLIGHT)
                                    .summaryLength(0)
                                    .fileChanges(List.of())
                                    .build())
                            .build();
                });

        LlmRequest result = phase.preflight(context, () -> {
            int call = requests.incrementAndGet();
            return LlmRequest.builder()
                    .systemPrompt(call == 1 ? "x".repeat(4_000) : "small")
                    .messages(new ArrayList<>(context.getSession().getMessages()))
                    .build();
        }, 7);

        assertEquals("small", result.getSystemPrompt());
        assertEquals(2, requests.get());
        assertEquals(1, context.getMessages().size());
        verify(compactionService).compact("session-1", CompactionReason.REQUEST_PREFLIGHT, 2);
        verify(turnProgressService).flushBufferedTools(context, "request_preflight_compaction");
        verify(turnProgressService).publishSummary(context,
                "I shortened the conversation context so this request fits the model window.",
                Map.of("kind", "context_compaction_fallback",
                        "reason", CompactionReason.REQUEST_PREFLIGHT.name(),
                        "removed", 2,
                        "usedSummary", false));
        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        assertEquals(true, diagnostics.get("compactionAttempted"));
        assertEquals(2, diagnostics.get("compactionRemoved"));
        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        assertTrue(events.stream().anyMatch(event -> RuntimeEventType.COMPACTION_STARTED.equals(event.type())));
        assertTrue(events.stream().anyMatch(event -> RuntimeEventType.COMPACTION_FINISHED.equals(event.type())));
    }

    @Test
    void shouldSendOversizedRequestWhenCompactionDisabled() {
        AgentContext context = buildContext(3);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(false);
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("x".repeat(4_000))
                .messages(new ArrayList<>(context.getSession().getMessages()))
                .build();

        LlmRequest result = phase.preflight(context, () -> request, 1);

        assertSame(request, result);
        verify(compactionService, never()).compact(any(), any(), anyInt());
        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        assertEquals(false, diagnostics.get("compactionAttempted"));
        assertTrue(Boolean.TRUE.equals(diagnostics.get("overThreshold")));
    }

    @Test
    void shouldReportCompactionSkippedNotAttemptedWhenCompactionServiceDisabled() {
        AgentContext context = buildContext(4);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(false);
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("x".repeat(4_000))
                .messages(new ArrayList<>(context.getSession().getMessages()))
                .build();

        phase.preflight(context, () -> request, 1);

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        assertEquals(false, diagnostics.get("compactionAttempted"),
                "compaction was never attempted; diagnostics must not report that it was");
        assertEquals("skipped_disabled", diagnostics.get("compactionOutcome"));
    }

    @Test
    void shouldFallBackToTokenThresholdWhenModelLookupFails() {
        AgentContext context = buildContext(2);
        when(modelSelectionService.resolveMaxInputTokensForContext(any()))
                .thenThrow(new IllegalStateException("model"));
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("token_threshold");
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(20_000);
        LlmRequest request = LlmRequest.builder().systemPrompt("short").build();

        phase.preflight(context, () -> request, 1);

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        assertFalse(Boolean.TRUE.equals(diagnostics.get("overThreshold")));
    }

    @Test
    void shouldLogOnceWhenBothModelRegistryAndConfiguredThresholdMissing() {
        AgentContext context = buildContext(2);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(0);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(0);

        ListAppender<ILoggingEvent> appender = attachPreflightLogAppender();
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("short")
                .messages(List.of(Message.builder().role("user").content("hi").build()))
                .build();

        phase.preflight(context, () -> request, 1);
        phase.preflight(context, () -> request, 2);
        phase.preflight(context, () -> request, 3);

        long bypassWarnings = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains("Preflight threshold bypass"))
                .count();
        assertEquals(1, bypassWarnings,
                "silent MAX_VALUE bypass must emit a warn exactly once across multiple preflight calls");
    }

    @Test
    void shouldReWarnAboutPreflightBypassAfterConfigRecoversAndBreaksAgain() {
        AgentContext context = buildContext(2);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(0);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(0);
        ListAppender<ILoggingEvent> appender = attachPreflightLogAppender();
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("short")
                .messages(List.of(Message.builder().role("user").content("hi").build()))
                .build();

        phase.preflight(context, () -> request, 1);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(1_000);
        phase.preflight(context, () -> request, 2);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(0);
        phase.preflight(context, () -> request, 3);

        long bypassWarnings = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains("Preflight threshold bypass"))
                .count();
        assertEquals(2, bypassWarnings,
                "bypass warn must re-fire after config recovers and breaks again");
    }
}
