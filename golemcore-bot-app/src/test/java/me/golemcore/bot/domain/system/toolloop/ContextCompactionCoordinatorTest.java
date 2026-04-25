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
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.service.CompactionOrchestrationService;
import me.golemcore.bot.domain.service.ContextCompactionPolicy;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextCompactionCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-04-14T00:00:00Z");

    private RuntimeConfigService runtimeConfigService;
    private CompactionOrchestrationService compactionService;
    private TurnProgressService turnProgressService;
    private ContextCompactionCoordinator coordinator;
    private final List<AttachedAppender> attachedAppenders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        compactionService = mock(CompactionOrchestrationService.class);
        turnProgressService = mock(TurnProgressService.class);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(true);
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(2);
        ContextCompactionPolicy contextCompactionPolicy = new ContextCompactionPolicy(
                runtimeConfigService, mock(ModelSelectionService.class));

        coordinator = new ContextCompactionCoordinator(
                contextCompactionPolicy,
                compactionService,
                new RuntimeEventService(Clock.fixed(NOW, ZoneOffset.UTC)),
                turnProgressService);
    }

    @Test
    void shouldNotRecoverFromContextOverflowWhenRetryAttemptAboveZero() {
        AgentContext context = buildContext(5);

        boolean recovered = coordinator.recoverFromContextOverflow(context, 1, 1);

        assertFalse(recovered,
                "overflow recovery must fire at most once per LLM call - retryAttempt>0 means "
                        + "we already tried; retrying again would loop on an unrecoverable error");
        verify(compactionService, never()).compact(any(), any(), anyInt());
    }

    @Test
    void shouldLogOnceAndSkipOverflowRecoveryWhenCompactionDisabled() {
        AgentContext context = buildContext(5);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(false);
        ListAppender<ILoggingEvent> appender = attachCoordinatorLogAppender();

        boolean first = coordinator.recoverFromContextOverflow(context, 1, 0);
        boolean second = coordinator.recoverFromContextOverflow(context, 2, 0);

        assertFalse(first);
        assertFalse(second);
        verify(compactionService, never()).compact(any(), any(), anyInt());
        long warnings = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains("Context overflow detected"))
                .count();
        assertEquals(1, warnings,
                "disabled-compaction overflow must warn exactly once across repeated failures - "
                        + "otherwise a stuck config floods logs on every oversized turn");
    }

    @Test
    void shouldSkipOverflowRecoveryWhenSessionHasTooFewMessages() {
        AgentContext context = buildContext(2);
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(5);

        boolean recovered = coordinator.recoverFromContextOverflow(context, 1, 0);

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
    void shouldPreserveSuccessfulRecoveryDiagnosticWhenRetryBlocked() {
        AgentContext context = buildContext(6);
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

        assertTrue(coordinator.recoverFromContextOverflow(context, 9, 0),
                "first call must successfully compact");
        assertFalse(coordinator.recoverFromContextOverflow(context, 10, 1),
                "second call is retry-blocked and must return false");

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_CONTEXT_OVERFLOW_RECOVERY);
        assertNotNull(diagnostics);
        assertEquals("compacted", diagnostics.get("recoveryOutcome"),
                "retry-blocked call must NOT overwrite the prior successful recovery diagnostic - "
                        + "operators need to see the successful recovery record survive a downstream retry");
        assertEquals(4, diagnostics.get("recoveryRemoved"),
                "prior recoveryRemoved count must survive the retry-blocked call");
        assertEquals(9, diagnostics.get("llmCall"),
                "prior llmCall identifier must survive the retry-blocked call");
    }

    @Test
    void shouldRecoverFromContextOverflowByRunningCompaction() {
        AgentContext context = buildContext(6);
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

        boolean recovered = coordinator.recoverFromContextOverflow(context, 9, 0);

        assertTrue(recovered);
        verify(compactionService).compact("session-1", CompactionReason.CONTEXT_OVERFLOW_RECOVERY, 2);
        assertEquals(2, context.getMessages().size(),
                "context.messages must be resynced with the now-shorter session");
        verify(turnProgressService).flushBufferedTools(context, "context_overflow_recovery");
        verify(turnProgressService).publishSummary(context,
                "The model rejected the request as too large, so I shortened the conversation context and retried.",
                Map.of("kind", "context_compaction_fallback",
                        "reason", CompactionReason.CONTEXT_OVERFLOW_RECOVERY.name(),
                        "removed", 4,
                        "usedSummary", true));
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
        when(compactionService.compact(any(), any(), anyInt()))
                .thenReturn(CompactionResult.builder().removed(0).usedSummary(false).build());

        coordinator.recoverFromContextOverflow(context, 3, 0);

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_CONTEXT_OVERFLOW_RECOVERY);
        assertNotNull(diagnostics);
        assertEquals(true, diagnostics.get("recoveryAttempted"));
        assertEquals(0, diagnostics.get("recoveryRemoved"));
        assertEquals("attempted_no_change", diagnostics.get("recoveryOutcome"));
    }

    @Test
    void shouldRecordOverflowRecoveryErrorAndReturnFalseWhenCompactionThrows() {
        AgentContext context = buildContext(6);
        when(compactionService.compact("session-1", CompactionReason.CONTEXT_OVERFLOW_RECOVERY, 2))
                .thenThrow(new IllegalStateException("summary store unavailable"));

        boolean recovered = assertDoesNotThrow(
                () -> coordinator.recoverFromContextOverflow(context, 4, 0),
                "overflow recovery is a best-effort fallback; compaction failure must not escape and "
                        + "hide the original provider context-overflow error");

        assertFalse(recovered,
                "failed recovery must return false so LlmCallPhase surfaces the original context overflow");
        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_CONTEXT_OVERFLOW_RECOVERY);
        assertNotNull(diagnostics,
                "failed overflow recovery must overwrite stale/null diagnostics with a terminal error snapshot");
        assertEquals(true, diagnostics.get("recoveryAttempted"));
        assertEquals(0, diagnostics.get("recoveryRemoved"));
        assertEquals(false, diagnostics.get("recoveryUsedSummary"));
        assertEquals("error", diagnostics.get("recoveryOutcome"));
        assertEquals(4, diagnostics.get("llmCall"));

        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        assertTrue(events.stream()
                .filter(event -> RuntimeEventType.COMPACTION_FINISHED.equals(event.type()))
                .anyMatch(event -> "error".equals(event.payload().get("outcome"))),
                "runCompaction must still emit the canonical COMPACTION_FINISHED error event");
    }

    @Test
    void shouldRecordOverflowRecoveryOutcomeWhenCompactionDisabled() {
        AgentContext context = buildContext(6);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(false);

        coordinator.recoverFromContextOverflow(context, 1, 0);

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.LLM_CONTEXT_OVERFLOW_RECOVERY);
        assertNotNull(diagnostics,
                "overflow recovery diagnostic must record the skipped-disabled terminal state too - "
                        + "operators need to see 'recovery didn't fire because compaction is off'");
        assertEquals(false, diagnostics.get("recoveryAttempted"));
        assertEquals("skipped_disabled", diagnostics.get("recoveryOutcome"));
    }

    @Test
    void shouldReturnFalseFromOverflowRecoveryWhenCompactionRemovesNothing() {
        AgentContext context = buildContext(6);
        when(compactionService.compact(any(), any(), anyInt()))
                .thenReturn(CompactionResult.builder().removed(0).usedSummary(false).build());

        boolean recovered = coordinator.recoverFromContextOverflow(context, 1, 0);

        assertFalse(recovered,
                "a no-op compaction means the state is unrecoverable - caller must surface the "
                        + "original overflow error instead of silently retrying");
    }

    @Test
    void runCompaction_shouldReturnNullWhenOrchestrationServiceIsNull() {
        // Degenerate wiring: the domain factory may instantiate the coordinator
        // without an orchestrator (e.g. compaction feature fully disabled).
        // runCompaction must bail at the gate instead of NPEing on compact().
        ContextCompactionCoordinator degenerate = new ContextCompactionCoordinator(
                new ContextCompactionPolicy(runtimeConfigService, mock(ModelSelectionService.class)),
                null,
                new RuntimeEventService(Clock.fixed(NOW, ZoneOffset.UTC)),
                turnProgressService);
        AgentContext context = buildContext(4);

        assertEquals(null, degenerate.runCompaction(context, CompactionReason.MANUAL_COMMAND, 2, 1, null));
    }

    @Test
    void runCompaction_shouldReturnNullWhenSessionMessagesAreNull() {
        // AgentSession.builder().messages(null) explicitly overrides Lombok's
        // @Builder.Default so the field stays null. The compound null-check
        // in runCompaction's guard must treat that identically to empty and
        // bail without touching the orchestrator.
        AgentSession session = AgentSession.builder()
                .id("session-null-msgs")
                .chatId("chat-null-msgs")
                .messages(null)
                .build();
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();

        assertEquals(null, coordinator.runCompaction(context, CompactionReason.MANUAL_COMMAND, 2, 1, null));
        verify(compactionService, never()).compact(any(), any(), anyInt());
    }

    @Test
    void runCompaction_shouldReturnNullForNullContext() {
        // Every null guard inside runCompaction matters: the coordinator is
        // wired into the pipeline before session validation and must never
        // throw at the gate even if the caller forgets to pass a context.
        assertEquals(null, coordinator.runCompaction(null, CompactionReason.MANUAL_COMMAND, 2, 1, null));
        verify(compactionService, never()).compact(any(), any(), anyInt());
    }

    @Test
    void runCompaction_shouldReturnNullForSessionWithoutMessages() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("s").chatId("c").build())
                .messages(new ArrayList<>())
                .build();

        assertEquals(null, coordinator.runCompaction(context, CompactionReason.MANUAL_COMMAND, 2, 1, null));
        verify(compactionService, never()).compact(any(), any(), anyInt());
    }

    @Test
    void runCompaction_shouldReturnNullForSingleMessageSession() {
        // The keepLast floor is 1; compacting a session of 1 message would
        // remove zero and waste a summarize call. Short-circuit guards that.
        AgentContext context = buildContext(1);

        assertEquals(null, coordinator.runCompaction(context, CompactionReason.MANUAL_COMMAND, 2, 1, null));
        verify(compactionService, never()).compact(any(), any(), anyInt());
    }

    @Test
    void runCompaction_shouldMergeExtraStartedPayloadIntoEvent() {
        AgentContext context = buildContext(4);
        when(compactionService.compact("session-1", CompactionReason.MANUAL_COMMAND, 2))
                .thenReturn(CompactionResult.builder().removed(0).usedSummary(false).build());

        coordinator.runCompaction(context, CompactionReason.MANUAL_COMMAND, 2, 3,
                Map.of("probe", "diag", "rawCutIndex", 99));

        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        RuntimeEvent started = events.stream()
                .filter(event -> RuntimeEventType.COMPACTION_STARTED.equals(event.type()))
                .findFirst().orElseThrow();
        assertEquals("diag", started.payload().get("probe"),
                "extraStartedPayload entries must be merged into the STARTED event");
        assertEquals(99, started.payload().get("rawCutIndex"),
                "extraStartedPayload must be allowed to override base keys like rawCutIndex");
        // progressReasonFor's default arm - MANUAL_COMMAND is neither
        // REQUEST_PREFLIGHT nor CONTEXT_OVERFLOW_RECOVERY.
        verify(turnProgressService).flushBufferedTools(context, "compaction");
    }

    @Test
    void runCompaction_shouldTolerateNullRuntimeEventAndTurnProgressServices() {
        // The coordinator is built from a domain factory that may assemble a
        // degenerate wiring (e.g. disabled telemetry). runCompaction must keep
        // working when either service is null - the feature may not emit
        // events but the compaction itself must still run.
        ContextCompactionCoordinator degenerate = new ContextCompactionCoordinator(
                new ContextCompactionPolicy(runtimeConfigService, mock(ModelSelectionService.class)),
                compactionService,
                null,
                null);
        AgentContext context = buildContext(4);
        when(compactionService.compact(any(), any(), anyInt()))
                .thenReturn(CompactionResult.builder().removed(0).usedSummary(false).build());

        assertDoesNotThrow(() -> degenerate.runCompaction(context, CompactionReason.MANUAL_COMMAND, 2, 1, null));
    }

    @Test
    void recoverFromContextOverflow_shouldReturnFalseForNullContext() {
        assertFalse(coordinator.recoverFromContextOverflow(null, 1, 0));
    }

    @Test
    void recoverFromContextOverflow_shouldReturnFalseForNullSession() {
        AgentContext context = AgentContext.builder().messages(new ArrayList<>()).build();
        assertFalse(coordinator.recoverFromContextOverflow(context, 1, 0));
    }

    @Test
    void recoverFromContextOverflow_shouldReturnFalseWhenSessionMessagesNull() {
        // Pass messages(null) explicitly so Lombok @Builder.Default does not
        // mask the null field with an empty ArrayList. This closes the
        // getMessages()==null true arm of the compound guard; without the
        // explicit override the test would fall through to the size <=
        // keepLast branch instead.
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("s").chatId("c").messages(null).build())
                .messages(new ArrayList<>())
                .build();

        assertFalse(coordinator.recoverFromContextOverflow(context, 1, 0));
    }

    @AfterEach
    void detachAppenders() {
        for (AttachedAppender attached : attachedAppenders) {
            attached.logger().detachAppender(attached.appender());
            attached.appender().stop();
        }
        attachedAppenders.clear();
    }

    private ListAppender<ILoggingEvent> attachCoordinatorLogAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(ContextCompactionCoordinator.class);
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
