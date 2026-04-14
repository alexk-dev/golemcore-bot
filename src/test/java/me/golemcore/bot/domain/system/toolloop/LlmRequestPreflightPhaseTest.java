package me.golemcore.bot.domain.system.toolloop;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import me.golemcore.bot.domain.service.ContextBudgetPolicy;
import me.golemcore.bot.domain.service.ContextTokenEstimator;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.RuntimeEventService;
import me.golemcore.bot.domain.service.TurnProgressService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmRequestPreflightPhaseTest {

    private static final Instant NOW = Instant.parse("2026-04-14T00:00:00Z");

    private ModelSelectionService modelSelectionService;
    private RuntimeConfigService runtimeConfigService;
    private CompactionOrchestrationService compactionService;
    private RuntimeEventService runtimeEventService;
    private TurnProgressService turnProgressService;
    private ContextBudgetPolicy contextBudgetPolicy;
    private LlmRequestPreflightPhase phase;
    private final List<AttachedAppender> attachedAppenders = new ArrayList<>();

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
        contextBudgetPolicy = new ContextBudgetPolicy(runtimeConfigService, modelSelectionService);
        phase = new LlmRequestPreflightPhase(
                runtimeConfigService,
                compactionService,
                new ContextTokenEstimator(),
                runtimeEventService,
                turnProgressService,
                contextBudgetPolicy);
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
                "compaction was never attempted — must not lie");
        assertEquals("skipped_disabled", diagnostics.get("compactionOutcome"));
    }

    @Test
    void shouldResetCumulativeRemovedBetweenPreflightCalls() {
        AgentContext context = buildContext(10);
        when(compactionService.compact(any(), any(), anyInt()))
                .thenReturn(CompactionResult.builder()
                        .removed(3)
                        .usedSummary(false)
                        .details(CompactionDetails.builder()
                                .reason(CompactionReason.REQUEST_PREFLIGHT)
                                .summaryLength(0)
                                .fileChanges(List.of())
                                .build())
                        .build());

        phase.preflight(context, () -> LlmRequest.builder()
                .systemPrompt("x".repeat(4_000))
                .messages(new ArrayList<>(context.getSession().getMessages()))
                .build(), 1);
        Map<String, Object> firstDiagnostics = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        int firstRemoved = (Integer) firstDiagnostics.get("compactionRemoved");
        assertTrue(firstRemoved >= 3, "first preflight should have accumulated at least 3 removed");

        phase.preflight(context, () -> LlmRequest.builder()
                .systemPrompt("small")
                .messages(List.of(Message.builder().role("user").content("hi").build()))
                .build(), 2);

        Map<String, Object> secondDiagnostics = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        assertEquals(0, secondDiagnostics.get("compactionRemoved"),
                "a new preflight() call must start with compactionRemoved=0 — otherwise the cumulative "
                        + "counter would silently carry over the previous LLM call's removals");
    }

    @Test
    void shouldAccumulateCompactionRemovedAcrossAttempts() {
        AgentContext context = buildContext(10);
        AtomicInteger call = new AtomicInteger();
        when(compactionService.compact(any(), any(), anyInt()))
                .thenAnswer(invocation -> {
                    int n = call.incrementAndGet();
                    int removed = n == 1 ? 5 : 0;
                    return CompactionResult.builder()
                            .removed(removed)
                            .usedSummary(false)
                            .details(CompactionDetails.builder()
                                    .reason(CompactionReason.REQUEST_PREFLIGHT)
                                    .summaryLength(0)
                                    .fileChanges(List.of())
                                    .build())
                            .build();
                });

        phase.preflight(context, () -> LlmRequest.builder()
                .systemPrompt("x".repeat(4_000))
                .messages(new ArrayList<>(context.getSession().getMessages()))
                .build(), 1);

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        assertEquals(5, diagnostics.get("compactionRemoved"),
                "compactionRemoved must be the cumulative total across preflight attempts — "
                        + "a later no-op compaction must not hide earlier successful removals");
        assertEquals("attempted_no_change", diagnostics.get("compactionOutcome"),
                "the outcome still reflects the terminal attempt");
    }

    @Test
    void shouldReportCompactionOutcomeAttemptedAndNoChangeWhenServiceReturnsZeroRemoved() {
        AgentContext context = buildContext(4);
        when(compactionService.compact(any(), any(), anyInt()))
                .thenReturn(CompactionResult.builder()
                        .removed(0)
                        .usedSummary(false)
                        .build());
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("x".repeat(4_000))
                .messages(new ArrayList<>(context.getSession().getMessages()))
                .build();

        phase.preflight(context, () -> request, 1);

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        assertEquals(true, diagnostics.get("compactionAttempted"));
        assertEquals("attempted_no_change", diagnostics.get("compactionOutcome"));
    }

    @Test
    void shouldEmitMatchingCompactionFinishedEventEvenWhenServiceRemovesNothing() {
        AgentContext context = buildContext(4);
        when(compactionService.compact(any(), any(), anyInt()))
                .thenReturn(CompactionResult.builder()
                        .removed(0)
                        .usedSummary(false)
                        .build());
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("x".repeat(4_000))
                .messages(new ArrayList<>(context.getSession().getMessages()))
                .build();

        phase.preflight(context, () -> request, 1);

        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        long startedCount = events.stream()
                .filter(event -> RuntimeEventType.COMPACTION_STARTED.equals(event.type()))
                .count();
        long finishedCount = events.stream()
                .filter(event -> RuntimeEventType.COMPACTION_FINISHED.equals(event.type()))
                .count();
        assertEquals(startedCount, finishedCount,
                "every COMPACTION_STARTED must have a matching COMPACTION_FINISHED — "
                        + "otherwise dashboards show hanging compaction progress");
        assertTrue(startedCount > 0, "compaction should have been attempted");
        RuntimeEvent finished = events.stream()
                .filter(event -> RuntimeEventType.COMPACTION_FINISHED.equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(0, finished.payload().get("removed"));
        assertEquals("attempted_no_change", finished.payload().get("outcome"));
    }

    @Test
    void shouldEmitFinishedWithOutcomeErrorWhenCompactionServiceThrows() {
        AgentContext context = buildContext(4);
        IllegalStateException boom = new IllegalStateException("persistence offline");
        when(compactionService.compact(any(), any(), anyInt()))
                .thenThrow(boom);
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("x".repeat(4_000))
                .messages(new ArrayList<>(context.getSession().getMessages()))
                .build();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> phase.preflight(context, () -> request, 1));
        assertSame(boom, thrown, "preflight must re-throw the original exception, not wrap it");

        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        long startedCount = events.stream()
                .filter(event -> RuntimeEventType.COMPACTION_STARTED.equals(event.type()))
                .count();
        long finishedCount = events.stream()
                .filter(event -> RuntimeEventType.COMPACTION_FINISHED.equals(event.type()))
                .count();
        assertEquals(startedCount, finishedCount,
                "COMPACTION_STARTED must stay balanced with COMPACTION_FINISHED even when the "
                        + "compaction service throws — otherwise dashboards show hanging compaction forever");
        assertTrue(startedCount > 0, "compaction should have been attempted");
        RuntimeEvent finished = events.stream()
                .filter(event -> RuntimeEventType.COMPACTION_FINISHED.equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("error", finished.payload().get("outcome"));
        assertEquals("java.lang.IllegalStateException", finished.payload().get("errorType"));
        assertEquals("persistence offline", finished.payload().get("errorMessage"));
    }

    @Test
    void shouldIncludeOutcomeCompactedInFinishedPayloadOnSuccess() {
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

        phase.preflight(context, () -> {
            int call = requests.incrementAndGet();
            return LlmRequest.builder()
                    .systemPrompt(call == 1 ? "x".repeat(4_000) : "small")
                    .messages(new ArrayList<>(context.getSession().getMessages()))
                    .build();
        }, 7);

        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        RuntimeEvent finished = events.stream()
                .filter(event -> RuntimeEventType.COMPACTION_FINISHED.equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("compacted", finished.payload().get("outcome"),
                "success-path COMPACTION_FINISHED must expose outcome=compacted to mirror "
                        + "attempted_no_change — dashboards must not special-case null for the happy path");
    }

    @Test
    void shouldMarkFinalAttemptOnWithinBudgetEarlyExit() {
        AgentContext context = buildContext(2);
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("short")
                .messages(List.of(Message.builder().role("user").content("hi").build()))
                .build();

        phase.preflight(context, () -> request, 1);

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        assertEquals(true, diagnostics.get("finalAttempt"),
                "finalAttempt must mean 'this was the terminal attempt', not 'we ran out of retries'");
        assertEquals(false, diagnostics.get("overThreshold"));
    }

    @Test
    void shouldMarkFinalAttemptWhenCompactionDisabledAndOverBudget() {
        AgentContext context = buildContext(3);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(false);
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("x".repeat(4_000))
                .messages(new ArrayList<>(context.getSession().getMessages()))
                .build();

        phase.preflight(context, () -> request, 1);

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        assertEquals(true, diagnostics.get("finalAttempt"),
                "terminal path via disabled compaction must set finalAttempt=true");
        assertEquals(true, diagnostics.get("overThreshold"));
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
    void shouldEmitErrorFinishedWhenPostCompactMutationThrows() {
        // Round-4 wrapped compactionOrchestrationService.compact() in try/catch so
        // every COMPACTION_STARTED still gets a matching COMPACTION_FINISHED under
        // exception. But the post-compact mutation block (setMessages /
        // setAttribute ×4) was left outside the try. A future guard on
        // AgentContext.setAttribute — or any downstream consumer that the payload
        // touches — throwing would leak a hanging STARTED event. This test
        // simulates exactly that by spying on AgentContext and throwing on the
        // COMPACTION_LAST_DETAILS setAttribute call.
        AgentContext context = spy(buildContext(5));
        doThrow(new IllegalStateException("downstream attribute guard tripped"))
                .when(context)
                .setAttribute(eq(ContextAttributes.COMPACTION_LAST_DETAILS), any());

        when(compactionService.compact(any(), any(), anyInt()))
                .thenReturn(CompactionResult.builder()
                        .removed(3)
                        .usedSummary(false)
                        .details(CompactionDetails.builder()
                                .reason(CompactionReason.REQUEST_PREFLIGHT)
                                .summaryLength(0)
                                .fileChanges(List.of())
                                .build())
                        .build());

        LlmRequest request = LlmRequest.builder()
                .systemPrompt("x".repeat(4_000))
                .messages(new ArrayList<>(context.getSession().getMessages()))
                .build();

        assertThrows(IllegalStateException.class,
                () -> phase.preflight(context, () -> request, 1));

        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        long startedCount = events.stream()
                .filter(event -> RuntimeEventType.COMPACTION_STARTED.equals(event.type()))
                .count();
        long finishedCount = events.stream()
                .filter(event -> RuntimeEventType.COMPACTION_FINISHED.equals(event.type()))
                .count();
        assertEquals(startedCount, finishedCount,
                "STARTED/FINISHED balance must hold even when a post-compact mutation throws — "
                        + "the invariant must cover the entire critical section, not just compact()");
        RuntimeEvent finished = events.stream()
                .filter(event -> RuntimeEventType.COMPACTION_FINISHED.equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("error", finished.payload().get("outcome"));
        assertEquals("java.lang.IllegalStateException", finished.payload().get("errorType"));
    }

    @Test
    void shouldReportActualSessionSizeInErrorPayloadNotRequestedKeepLast() {
        AgentContext context = buildContext(5);
        // On an exception we don't know how many messages were retained — the
        // error payload must surface observed session size via a dedicated
        // sessionSize field, and "kept" must stay at 0 to match the
        // removed=0/kept=0 convention shared with the success no-op path.
        // Collapsing both into "kept" is a lie: a dashboard showing "kept=5" on
        // a crashed compaction can't distinguish "nothing happened" from
        // "everything was retained".
        int observedSizeAtFailure = context.getSession().getMessages().size();
        when(compactionService.compact(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("boom"));
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("x".repeat(4_000))
                .messages(new ArrayList<>(context.getSession().getMessages()))
                .build();

        assertThrows(IllegalStateException.class,
                () -> phase.preflight(context, () -> request, 1));

        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        RuntimeEvent finished = events.stream()
                .filter(event -> RuntimeEventType.COMPACTION_FINISHED.equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(0, finished.payload().get("kept"),
                "error payload must report kept=0 (matches removed=0 invariant); "
                        + "session size belongs in a dedicated sessionSize field");
        assertEquals(observedSizeAtFailure, finished.payload().get("sessionSize"),
                "error payload must expose observed session size at time of catch "
                        + "via a dedicated sessionSize field, not overload the kept counter");
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
                "silent MAX_VALUE bypass must emit a warn exactly once across multiple preflight calls — "
                        + "otherwise a misconfigured model registry turns preflight into a no-op with no diagnostics");
    }

    @Test
    void shouldNotRecoverFromContextOverflowWhenRetryAttemptAboveZero() {
        AgentContext context = buildContext(5);

        boolean recovered = phase.recoverFromContextOverflow(context, 1, 1);

        assertFalse(recovered,
                "overflow recovery must fire at most once per LLM call — retryAttempt>0 means "
                        + "we already tried; retrying again would loop on an unrecoverable error");
        verify(compactionService, never()).compact(any(), any(), anyInt());
    }

    @Test
    void shouldLogOnceAndSkipOverflowRecoveryWhenCompactionDisabled() {
        AgentContext context = buildContext(5);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(false);
        ListAppender<ILoggingEvent> appender = attachPhaseLogAppender();

        boolean first = phase.recoverFromContextOverflow(context, 1, 0);
        boolean second = phase.recoverFromContextOverflow(context, 2, 0);

        assertFalse(first);
        assertFalse(second);
        verify(compactionService, never()).compact(any(), any(), anyInt());
        long warnings = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains("Context overflow detected"))
                .count();
        assertEquals(1, warnings,
                "disabled-compaction overflow must warn exactly once across repeated failures — "
                        + "otherwise a stuck config floods logs on every oversized turn");
    }

    @Test
    void shouldSkipOverflowRecoveryWhenSessionHasTooFewMessages() {
        AgentContext context = buildContext(2);
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(5);

        boolean recovered = phase.recoverFromContextOverflow(context, 1, 0);

        assertFalse(recovered,
                "if the session is already smaller than keepLast, there's nothing to compact");
        verify(compactionService, never()).compact(any(), any(), anyInt());

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_CONTEXT_OVERFLOW_RECOVERY);
        assertNotNull(diagnostics,
                "overflow recovery diagnostic must record skipped-too-small so operators can distinguish "
                        + "'recovery didn't fire because session too small' from 'recovery never ran'");
        assertEquals(false, diagnostics.get("recoveryAttempted"));
        assertEquals("skipped_too_small", diagnostics.get("recoveryOutcome"));
        assertEquals(0, diagnostics.get("recoveryRemoved"));
        assertEquals(1, diagnostics.get("llmCall"));
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
                "bypass warn must re-fire after config recovers and breaks again — "
                        + "otherwise intermittent misconfig goes silent after the first incident");
    }

    @Test
    void shouldPreserveSuccessfulRecoveryDiagnosticWhenRetryBlocked() {
        AgentContext context = buildContext(6);
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(2);
        when(compactionService.compact("session-1", CompactionReason.CONTEXT_OVERFLOW_RECOVERY, 2))
                .thenAnswer(invocation -> {
                    context.getSession().getMessages().clear();
                    context.getSession().addMessage(Message.builder().role("user").content("kept").build());
                    context.getSession().addMessage(Message.builder().role("assistant").content("kept2").build());
                    return CompactionResult.builder()
                            .removed(4)
                            .usedSummary(true)
                            .details(CompactionDetails.builder()
                                    .reason(CompactionReason.CONTEXT_OVERFLOW_RECOVERY)
                                    .summaryLength(10)
                                    .fileChanges(List.of())
                                    .build())
                            .build();
                });

        assertTrue(phase.recoverFromContextOverflow(context, 9, 0),
                "first call must successfully compact");
        assertFalse(phase.recoverFromContextOverflow(context, 10, 1),
                "second call is retry-blocked and must return false");

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_CONTEXT_OVERFLOW_RECOVERY);
        assertNotNull(diagnostics);
        assertEquals("compacted", diagnostics.get("recoveryOutcome"),
                "retry-blocked call must NOT overwrite the prior successful recovery diagnostic — "
                        + "operators need to see the successful recovery record survive a downstream retry");
        assertEquals(4, diagnostics.get("recoveryRemoved"),
                "prior recoveryRemoved count must survive the retry-blocked call");
        assertEquals(9, diagnostics.get("llmCall"),
                "prior llmCall identifier must survive the retry-blocked call");
    }

    @Test
    void shouldRecoverFromContextOverflowByRunningCompaction() {
        AgentContext context = buildContext(6);
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(2);
        when(compactionService.compact("session-1", CompactionReason.CONTEXT_OVERFLOW_RECOVERY, 2))
                .thenAnswer(invocation -> {
                    context.getSession().getMessages().clear();
                    context.getSession().addMessage(Message.builder().role("user").content("kept").build());
                    context.getSession().addMessage(Message.builder().role("assistant").content("kept2").build());
                    return CompactionResult.builder()
                            .removed(4)
                            .usedSummary(true)
                            .details(CompactionDetails.builder()
                                    .reason(CompactionReason.CONTEXT_OVERFLOW_RECOVERY)
                                    .summaryLength(10)
                                    .fileChanges(List.of())
                                    .build())
                            .build();
                });

        boolean recovered = phase.recoverFromContextOverflow(context, 9, 0);

        assertTrue(recovered);
        verify(compactionService).compact("session-1", CompactionReason.CONTEXT_OVERFLOW_RECOVERY, 2);
        assertEquals(2, context.getMessages().size(),
                "context.messages must be resynced with the now-shorter session");
        verify(turnProgressService).flushBufferedTools(context, "context_overflow_recovery");
        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        assertTrue(events.stream().anyMatch(event -> RuntimeEventType.COMPACTION_STARTED.equals(event.type())));
        assertTrue(events.stream().anyMatch(event -> RuntimeEventType.COMPACTION_FINISHED.equals(event.type())));

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_CONTEXT_OVERFLOW_RECOVERY);
        assertNotNull(diagnostics, "overflow recovery must publish a dedicated diagnostic attribute "
                + "so dashboards can see it without scanning runtime events");
        assertEquals(true, diagnostics.get("recoveryAttempted"));
        assertEquals(4, diagnostics.get("recoveryRemoved"));
        assertEquals(true, diagnostics.get("recoveryUsedSummary"));
        assertEquals("compacted", diagnostics.get("recoveryOutcome"));
        assertEquals(9, diagnostics.get("llmCall"));
    }

    @Test
    void shouldRecordOverflowRecoveryOutcomeWhenCompactionRemovesNothing() {
        AgentContext context = buildContext(6);
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(2);
        when(compactionService.compact(any(), any(), anyInt()))
                .thenReturn(CompactionResult.builder().removed(0).usedSummary(false).build());

        phase.recoverFromContextOverflow(context, 3, 0);

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_CONTEXT_OVERFLOW_RECOVERY);
        assertNotNull(diagnostics);
        assertEquals(true, diagnostics.get("recoveryAttempted"));
        assertEquals(0, diagnostics.get("recoveryRemoved"));
        assertEquals("attempted_no_change", diagnostics.get("recoveryOutcome"));
    }

    @Test
    void shouldRecordOverflowRecoveryOutcomeWhenCompactionDisabled() {
        AgentContext context = buildContext(6);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(false);

        phase.recoverFromContextOverflow(context, 1, 0);

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_CONTEXT_OVERFLOW_RECOVERY);
        assertNotNull(diagnostics,
                "overflow recovery diagnostic must record the skipped-disabled terminal state too — "
                        + "operators need to see 'recovery didn't fire because compaction is off'");
        assertEquals(false, diagnostics.get("recoveryAttempted"));
        assertEquals("skipped_disabled", diagnostics.get("recoveryOutcome"));
    }

    @Test
    void shouldReturnFalseFromOverflowRecoveryWhenCompactionRemovesNothing() {
        AgentContext context = buildContext(6);
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(2);
        when(compactionService.compact(any(), any(), anyInt()))
                .thenReturn(CompactionResult.builder().removed(0).usedSummary(false).build());

        boolean recovered = phase.recoverFromContextOverflow(context, 1, 0);

        assertFalse(recovered,
                "a no-op compaction means the state is unrecoverable — caller must surface the "
                        + "original overflow error instead of silently retrying");
    }

    @AfterEach
    void detachAppenders() {
        // Appenders attached to singleton loggers leak between tests and let log
        // events pile up across the suite — detach + stop is the only reliable
        // way to reset state between methods.
        for (AttachedAppender attached : attachedAppenders) {
            attached.logger().detachAppender(attached.appender());
            attached.appender().stop();
        }
        attachedAppenders.clear();
    }

    private ListAppender<ILoggingEvent> attachPreflightLogAppender() {
        return attachAppender((Logger) LoggerFactory.getLogger(ContextBudgetPolicy.class));
    }

    private ListAppender<ILoggingEvent> attachPhaseLogAppender() {
        return attachAppender((Logger) LoggerFactory.getLogger(LlmRequestPreflightPhase.class));
    }

    private ListAppender<ILoggingEvent> attachAppender(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        attachedAppenders.add(new AttachedAppender(logger, appender));
        return appender;
    }

    private record AttachedAppender(Logger logger, ListAppender<ILoggingEvent> appender) {
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
