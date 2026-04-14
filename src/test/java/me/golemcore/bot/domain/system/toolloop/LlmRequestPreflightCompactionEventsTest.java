package me.golemcore.bot.domain.system.toolloop;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class LlmRequestPreflightCompactionEventsTest extends LlmRequestPreflightPhaseFixture {

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
                "every COMPACTION_STARTED must have a matching COMPACTION_FINISHED");
        assertTrue(startedCount > 0, "compaction should have been attempted");
        RuntimeEvent finished = firstFinishedEvent(events);
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
                "COMPACTION_STARTED must stay balanced with COMPACTION_FINISHED on exceptions");
        assertTrue(startedCount > 0, "compaction should have been attempted");
        RuntimeEvent finished = firstFinishedEvent(events);
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
        RuntimeEvent finished = firstFinishedEvent(events);
        assertEquals("compacted", finished.payload().get("outcome"),
                "success-path COMPACTION_FINISHED must expose outcome=compacted");
    }

    @Test
    void shouldEmitErrorFinishedWhenPostCompactMutationThrows() {
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
                "STARTED/FINISHED balance must hold even when a post-compact mutation throws");
        RuntimeEvent finished = firstFinishedEvent(events);
        assertEquals("error", finished.payload().get("outcome"));
        assertEquals("java.lang.IllegalStateException", finished.payload().get("errorType"));
    }

    @Test
    void shouldReportActualSessionSizeInErrorPayloadNotRequestedKeepLast() {
        AgentContext context = buildContext(5);
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
        RuntimeEvent finished = firstFinishedEvent(events);
        assertEquals(0, finished.payload().get("kept"),
                "error payload must report kept=0; session size belongs in sessionSize");
        assertEquals(observedSizeAtFailure, finished.payload().get("sessionSize"),
                "error payload must expose observed session size at time of catch");
    }

    private static RuntimeEvent firstFinishedEvent(List<RuntimeEvent> events) {
        return events.stream()
                .filter(event -> RuntimeEventType.COMPACTION_FINISHED.equals(event.type()))
                .findFirst()
                .orElseThrow();
    }
}
