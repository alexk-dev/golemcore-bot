package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.CompactionDetails;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.service.CompactionOrchestrationService;
import me.golemcore.bot.domain.service.ContextTokenEstimator;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.RuntimeEventService;
import me.golemcore.bot.domain.service.TurnProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmRequestPreflightPhaseTest {

    private static final Instant NOW = Instant.parse("2026-04-14T00:00:00Z");

    private ModelSelectionService modelSelectionService;
    private RuntimeConfigService runtimeConfigService;
    private CompactionOrchestrationService compactionService;
    private RuntimeEventService runtimeEventService;
    private TurnProgressService turnProgressService;
    private LlmRequestPreflightPhase phase;

    @BeforeEach
    void setUp() {
        modelSelectionService = mock(ModelSelectionService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        compactionService = mock(CompactionOrchestrationService.class);
        runtimeEventService = new RuntimeEventService(Clock.fixed(NOW, ZoneOffset.UTC));
        turnProgressService = mock(TurnProgressService.class);
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("model_ratio");
        when(runtimeConfigService.getCompactionModelThresholdRatio()).thenReturn(0.8d);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(10_000);
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(2);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(true);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(1_000);
        phase = new LlmRequestPreflightPhase(
                modelSelectionService,
                runtimeConfigService,
                compactionService,
                new ContextTokenEstimator(),
                runtimeEventService,
                turnProgressService);
    }

    @Test
    void shouldReturnRequestWhenWithinBudget() {
        AgentContext context = buildContext(2);
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("short")
                .messages(List.of(Message.builder().role("user").content("hi").build()))
                .build();

        LlmRequest result = phase.preflight(context, () -> request, 1);

        assertSame(request, result);
        verify(compactionService, never()).compact(any(), any(), org.mockito.ArgumentMatchers.anyInt());
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
        verify(compactionService, never()).compact(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        assertEquals(false, diagnostics.get("compactionAttempted"));
        assertTrue(Boolean.TRUE.equals(diagnostics.get("overThreshold")));
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

    private AgentContext buildContext(int messages) {
        AgentSession session = AgentSession.builder()
                .id("session-1")
                .chatId("chat-1")
                .messages(new ArrayList<>())
                .build();
        for (int index = 0; index < messages; index++) {
            session.addMessage(Message.builder()
                    .role(index % 2 == 0 ? "user" : "assistant")
                    .content("message-" + index)
                    .timestamp(NOW)
                    .build());
        }
        return AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();
    }
}
