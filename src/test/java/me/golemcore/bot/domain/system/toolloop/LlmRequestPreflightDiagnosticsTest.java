package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.CompactionDetails;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

class LlmRequestPreflightDiagnosticsTest extends LlmRequestPreflightPhaseFixture {

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
                "a new preflight() call must start with compactionRemoved=0");
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
                "compactionRemoved must be the cumulative total across preflight attempts");
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
    void shouldMarkTerminalOnWithinBudgetEarlyExit() {
        AgentContext context = buildContext(2);
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("short")
                .messages(List.of(Message.builder().role("user").content("hi").build()))
                .build();

        phase.preflight(context, () -> request, 1);

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        assertEquals(true, diagnostics.get("terminal"),
                "terminal must mean this was the last publication for this preflight() call");
        assertEquals(false, diagnostics.get("overThreshold"));
    }

    @Test
    void shouldMarkTerminalWhenCompactionDisabledAndOverBudget() {
        AgentContext context = buildContext(3);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(false);
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("x".repeat(4_000))
                .messages(new ArrayList<>(context.getSession().getMessages()))
                .build();

        phase.preflight(context, () -> request, 1);

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        assertEquals(true, diagnostics.get("terminal"),
                "terminal path via disabled compaction must set terminal=true");
        assertEquals(true, diagnostics.get("overThreshold"));
    }

    @Test
    void shouldPublishIdenticalDiagnosticKeySetAcrossEveryExitPath() {
        Set<String> expectedKeys = Set.of(
                "estimatedTokens",
                "threshold",
                "attempt",
                "maxAttempts",
                "overThreshold",
                "terminal",
                "compactionAttempted",
                "compactionRemoved",
                "compactionUsedSummary",
                "compactionOutcome");

        AgentContext withinBudget = buildContext(2);
        phase.preflight(withinBudget, () -> LlmRequest.builder()
                .systemPrompt("short")
                .messages(List.of(Message.builder().role("user").content("hi").build()))
                .build(), 1);
        assertEquals(expectedKeys, diagnosticKeys(withinBudget),
                "within-budget early exit must publish the full schema");

        AgentContext disabled = buildContext(3);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(false);
        phase.preflight(disabled, () -> LlmRequest.builder()
                .systemPrompt("x".repeat(4_000))
                .messages(new ArrayList<>(disabled.getSession().getMessages()))
                .build(), 1);
        assertEquals(expectedKeys, diagnosticKeys(disabled),
                "compaction-disabled terminal path must publish the full schema");
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(true);

        AgentContext noChange = buildContext(4);
        when(compactionService.compact(any(), any(), anyInt()))
                .thenReturn(CompactionResult.builder().removed(0).usedSummary(false).build());
        phase.preflight(noChange, () -> LlmRequest.builder()
                .systemPrompt("x".repeat(4_000))
                .messages(new ArrayList<>(noChange.getSession().getMessages()))
                .build(), 1);
        assertEquals(expectedKeys, diagnosticKeys(noChange),
                "attempted-no-change terminal path must publish the full schema");

        AgentContext success = buildContext(4);
        when(compactionService.compact(any(), any(), anyInt()))
                .thenAnswer(invocation -> {
                    success.getSession().getMessages().clear();
                    success.getSession().addMessage(
                            Message.builder().role("user").content("kept").build());
                    return CompactionResult.builder()
                            .removed(3)
                            .usedSummary(false)
                            .details(CompactionDetails.builder()
                                    .reason(CompactionReason.REQUEST_PREFLIGHT)
                                    .summaryLength(0)
                                    .fileChanges(List.of())
                                    .build())
                            .build();
                });
        AtomicInteger call = new AtomicInteger();
        phase.preflight(success, () -> LlmRequest.builder()
                .systemPrompt(call.incrementAndGet() == 1 ? "x".repeat(4_000) : "small")
                .messages(new ArrayList<>(success.getSession().getMessages()))
                .build(), 1);
        assertEquals(expectedKeys, diagnosticKeys(success),
                "successful-compaction terminal path must publish the full schema");
    }

    private static Set<String> diagnosticKeys(AgentContext context) {
        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT);
        assertNotNull(diagnostics, "preflight must publish diagnostics");
        return diagnostics.keySet();
    }
}
