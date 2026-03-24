package me.golemcore.bot.domain.system.toolloop;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmProviderMetadataKeys;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.RuntimeEventService;
import me.golemcore.bot.domain.service.TraceBudgetService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.TraceSnapshotCompressionService;
import me.golemcore.bot.domain.service.TurnProgressService;
import me.golemcore.bot.domain.system.LlmErrorClassifier;
import me.golemcore.bot.domain.system.toolloop.view.ConversationView;
import me.golemcore.bot.domain.system.toolloop.view.ConversationViewBuilder;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultToolLoopSystemTest {

    private static final String MODEL_BALANCED = "gpt-4o";
    private static final String TOOL_CALL_ID = "tc-1";
    private static final String TOOL_NAME = "test_tool";
    private static final String CONTENT_DONE = "Done";
    private static final String CONTENT_HELLO = "Hello";
    private static final String USER_DENIED = "User denied";

    @Mock
    private LlmPort llmPort;

    @Mock
    private ToolExecutorPort toolExecutor;

    @Mock
    private HistoryWriter historyWriter;

    @Mock
    private ConversationViewBuilder viewBuilder;

    @Mock
    private PlanService planService;

    @Mock
    private ModelSelectionService modelSelectionService;

    @Mock
    private RuntimeConfigService runtimeConfigService;

    @Mock
    private TurnProgressService turnProgressService;

    private BotProperties.TurnProperties turnSettings;

    private BotProperties.ToolLoopProperties settings;
    private Clock clock;
    private DefaultToolLoopSystem system;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        clock = Clock.fixed(Instant.parse("2026-02-14T00:00:00Z"), ZoneId.of("UTC"));

        settings = new BotProperties.ToolLoopProperties();
        settings.setStopOnToolFailure(false);
        settings.setStopOnConfirmationDenied(true);
        settings.setStopOnToolPolicyDenied(false);

        when(modelSelectionService.resolveForTier(any())).thenReturn(
                new ModelSelectionService.ModelSelection(MODEL_BALANCED, null));

        when(viewBuilder.buildView(any(), any()))
                .thenReturn(new ConversationView(List.of(), List.of()));

        turnSettings = new BotProperties.TurnProperties();
        system = new DefaultToolLoopSystem(llmPort, toolExecutor, historyWriter, viewBuilder,
                turnSettings, settings, modelSelectionService, planService, null, null, null, null, null, clock);
    }

    private AgentContext buildContext() {
        AgentSession session = AgentSession.builder()
                .id("sess-1")
                .chatId("chat-1")
                .build();

        return AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .maxIterations(1)
                .currentIteration(0)
                .build();
    }

    private LlmResponse finalResponse(String content) {
        return LlmResponse.builder()
                .content(content)
                .toolCalls(null)
                .build();
    }

    private LlmResponse toolCallResponse(List<Message.ToolCall> toolCalls) {
        return LlmResponse.builder()
                .content(null)
                .toolCalls(toolCalls)
                .build();
    }

    private Message.ToolCall toolCall(String id, String name) {
        return Message.ToolCall.builder()
                .id(id)
                .name(name)
                .arguments(Map.of("key", "value"))
                .build();
    }

    private DefaultToolLoopSystem buildSystemWithRuntimeEvents() {
        RuntimeEventService runtimeEventService = new RuntimeEventService(clock);
        return new DefaultToolLoopSystem(llmPort, toolExecutor, historyWriter, viewBuilder,
                turnSettings, settings, modelSelectionService, planService,
                null, null, runtimeEventService, null, null, clock);
    }

    private DefaultToolLoopSystem buildSystemWithRuntimeConfig() {
        return new DefaultToolLoopSystem(llmPort, toolExecutor, historyWriter, viewBuilder,
                turnSettings, settings, modelSelectionService, planService,
                runtimeConfigService, null, null, null, null, clock);
    }

    private DefaultToolLoopSystem buildSystemWithTurnProgress() {
        return new DefaultToolLoopSystem(llmPort, toolExecutor, historyWriter, viewBuilder,
                turnSettings, settings, modelSelectionService, planService,
                runtimeConfigService, null, null, turnProgressService, null, clock);
    }

    private void stubRuntimeConfigDefaults() {
        when(runtimeConfigService.getTurnMaxLlmCalls()).thenReturn(200);
        when(runtimeConfigService.getTurnMaxToolExecutions()).thenReturn(500);
        when(runtimeConfigService.getTurnDeadline()).thenReturn(Duration.ofHours(1));
    }

    private ListAppender<ILoggingEvent> attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultToolLoopSystem.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    // ==================== Final answer (no tool calls) ====================

    @Test
    void shouldReturnFinalAnswerWhenLlmReturnsNoToolCalls() {
        AgentContext context = buildContext();
        LlmResponse response = finalResponse("Hello!");

        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(1, result.llmCalls());
        assertEquals(0, result.toolExecutions());
        verify(historyWriter).appendFinalAssistantAnswer(any(), any(), any());
    }

    @Test
    void shouldCopyTraceContextIntoLlmRequest() {
        AgentContext context = buildContext();
        context.setTraceContext(TraceContext.builder()
                .traceId("trace-1")
                .spanId("span-1")
                .rootKind("INGRESS")
                .build());
        LlmResponse response = finalResponse("Hello!");

        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        system.processTurn(context);

        org.mockito.ArgumentCaptor<me.golemcore.bot.domain.model.LlmRequest> captor = org.mockito.ArgumentCaptor
                .forClass(me.golemcore.bot.domain.model.LlmRequest.class);
        verify(llmPort).chat(captor.capture());
        assertEquals("trace-1", captor.getValue().getTraceId());
        assertEquals("span-1", captor.getValue().getTraceSpanId());
        assertEquals("INGRESS", captor.getValue().getTraceRootKind());
    }

    @Test
    void shouldRecordLlmAndToolChildSpansWithSnapshots() {
        AgentContext context = buildContext();
        TraceService traceService = new TraceService(new TraceSnapshotCompressionService(), new TraceBudgetService());
        TraceContext rootTrace = traceService.startRootTrace(
                context.getSession(),
                TraceContext.builder()
                        .traceId("trace-1")
                        .spanId("root-span")
                        .rootKind(TraceSpanKind.INGRESS.name())
                        .build(),
                "telegram.message",
                TraceSpanKind.INGRESS,
                Instant.now(clock),
                Map.of("session.id", "sess-1"));
        context.setTraceContext(rootTrace);

        DefaultToolLoopSystem tracedSystem = new DefaultToolLoopSystem(
                llmPort, toolExecutor, historyWriter, viewBuilder,
                turnSettings, settings, modelSelectionService, planService,
                runtimeConfigService, null, null, turnProgressService, traceService, null, clock);

        stubRuntimeConfigDefaults();
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);
        when(runtimeConfigService.isPayloadSnapshotsEnabled()).thenReturn(true);
        when(runtimeConfigService.isTraceLlmPayloadCaptureEnabled()).thenReturn(true);
        when(runtimeConfigService.isTraceToolPayloadCaptureEnabled()).thenReturn(true);
        when(runtimeConfigService.getSessionTraceBudgetMb()).thenReturn(8);
        when(runtimeConfigService.getTraceMaxSnapshotSizeKb()).thenReturn(64);
        when(runtimeConfigService.getTraceMaxSnapshotsPerSpan()).thenReturn(4);

        Message.ToolCall toolCall = toolCall(TOOL_CALL_ID, TOOL_NAME);
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(toolCall))))
                .thenReturn(CompletableFuture.completedFuture(finalResponse(CONTENT_DONE)));
        when(toolExecutor.execute(any(), any())).thenReturn(new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null));

        ToolLoopTurnResult result = tracedSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        TraceRecord trace = context.getSession().getTraces().get(0);
        long llmSpanCount = trace.getSpans().stream()
                .filter(span -> "llm.chat".equals(span.getName()))
                .count();
        assertEquals(2L, llmSpanCount);
        assertTrue(trace.getSpans().stream()
                .anyMatch(span -> ("tool." + TOOL_NAME).equals(span.getName())));
        assertTrue(trace.getSpans().stream()
                .filter(span -> "llm.chat".equals(span.getName()))
                .allMatch(span -> !span.getSnapshots().isEmpty()));
        assertTrue(trace.getSpans().stream()
                .filter(span -> ("tool." + TOOL_NAME).equals(span.getName()))
                .allMatch(span -> !span.getSnapshots().isEmpty()));
    }

    @Test
    void shouldRecordRequestContextEventAndAttributesOnLlmSpan() {
        AgentContext context = buildContext();
        context.setActiveSkill(Skill.builder().name("planner").build());
        context.setModelTier("smart");
        context.setAttribute("model.tier.source", "skill");

        TraceService traceService = new TraceService(new TraceSnapshotCompressionService(), new TraceBudgetService());
        TraceContext rootTrace = traceService.startRootTrace(
                context.getSession(),
                TraceContext.builder()
                        .traceId("trace-1")
                        .spanId("root-span")
                        .rootKind(TraceSpanKind.INGRESS.name())
                        .build(),
                "telegram.message",
                TraceSpanKind.INGRESS,
                Instant.now(clock),
                Map.of("session.id", "sess-1"));
        context.setTraceContext(rootTrace);

        DefaultToolLoopSystem tracedSystem = new DefaultToolLoopSystem(
                llmPort, toolExecutor, historyWriter, viewBuilder,
                turnSettings, settings, modelSelectionService, planService,
                runtimeConfigService, null, null, turnProgressService, traceService, null, clock);

        stubRuntimeConfigDefaults();
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(finalResponse(CONTENT_DONE)));
        when(modelSelectionService.resolveForTier("smart"))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5-smart", "high"));

        ToolLoopTurnResult result = tracedSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        TraceRecord trace = context.getSession().getTraces().get(0);
        TraceSpanRecord llmSpan = trace.getSpans().stream()
                .filter(span -> "llm.chat".equals(span.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals("planner", llmSpan.getAttributes().get("context.skill.name"));
        assertEquals("smart", llmSpan.getAttributes().get("context.model.tier"));
        assertEquals("gpt-5-smart", llmSpan.getAttributes().get("context.model.id"));
        assertEquals("high", llmSpan.getAttributes().get("context.model.reasoning"));
        assertEquals("skill", llmSpan.getAttributes().get("context.model.source"));
        assertTrue(llmSpan.getEvents().stream()
                .anyMatch(event -> "request.context".equals(event.getName())
                        && "planner".equals(event.getAttributes().get("skill"))
                        && "smart".equals(event.getAttributes().get("tier"))
                        && "gpt-5-smart".equals(event.getAttributes().get("model_id"))));
    }

    @Test
    void shouldRetryTwiceAndFailWhenLlmResponseIsAlwaysNull() {
        AgentContext context = buildContext();

        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(null));

        ToolLoopTurnResult result = system.processTurn(context);

        assertFalse(result.finalAnswerReady());
        assertEquals(3, result.llmCalls());
        assertNotNull(context.getAttribute(ContextAttributes.LLM_ERROR));
        assertEquals(LlmErrorClassifier.NO_ASSISTANT_MESSAGE,
                context.getAttribute(ContextAttributes.LLM_ERROR_CODE));
        verify(historyWriter, never()).appendFinalAssistantAnswer(any(), any(), any());
        verify(llmPort, times(3)).chat(any());
    }

    @Test
    void shouldRecoverFromEmptyFinalResponsesWithinRetryBudget() {
        AgentContext context = buildContext();

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(finalResponse("")))
                .thenReturn(CompletableFuture.completedFuture(finalResponse("   ")))
                .thenReturn(CompletableFuture.completedFuture(finalResponse("Recovered answer")));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(3, result.llmCalls());
        assertNull(context.getAttribute(ContextAttributes.LLM_ERROR));
        verify(historyWriter).appendFinalAssistantAnswer(any(), any(), eq("Recovered answer"));
    }

    @Test
    void shouldSetLangchainErrorCodeWhenLlmCallThrows() {
        AgentContext context = buildContext();
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("request timed out")));

        ToolLoopTurnResult result = system.processTurn(context);

        assertFalse(result.finalAnswerReady());
        assertEquals(1, result.llmCalls());
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_TIMEOUT,
                context.getAttribute(ContextAttributes.LLM_ERROR_CODE));
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        assertNotNull(llmError);
        assertTrue(llmError.startsWith("[" + LlmErrorClassifier.LANGCHAIN4J_TIMEOUT + "]"));
        assertFalse(context.getFailures().isEmpty());
        assertEquals(me.golemcore.bot.domain.model.FailureKind.EXCEPTION, context.getFailures().get(0).kind());
    }

    @Test
    void shouldLogTurnLevelRetriesForTransientLlmFailures() {
        AgentContext context = buildContext();
        DefaultToolLoopSystem retrySystem = buildSystemWithRuntimeConfig();
        stubRuntimeConfigDefaults();
        when(runtimeConfigService.isTurnAutoRetryEnabled()).thenReturn(true);
        when(runtimeConfigService.getTurnAutoRetryMaxAttempts()).thenReturn(2);
        when(runtimeConfigService.getTurnAutoRetryBaseDelayMs()).thenReturn(1L);
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("request timed out")))
                .thenReturn(CompletableFuture.completedFuture(finalResponse("Recovered")));

        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            ToolLoopTurnResult result = retrySystem.processTurn(context);

            assertTrue(result.finalAnswerReady());
            assertEquals(2, result.llmCalls());
            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertTrue(messages.stream().anyMatch(message -> message.contains(
                    "Transient LLM failure, scheduling retry (code=llm.langchain4j.timeout, retry=1/2")));
            assertTrue(messages.stream().anyMatch(message -> message.contains(
                    "LLM retry succeeded (code=llm.langchain4j.timeout, retry=1/2, llmCall=2, model=gpt-4o)")));
        } finally {
            ((Logger) LoggerFactory.getLogger(DefaultToolLoopSystem.class)).detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void shouldClassifyNestedRateLimitCauseWhenLlmCallThrows() {
        AgentContext context = buildContext();
        Throwable nested = new CompletionException(new RuntimeException("wrapper",
                new RateLimitException("too many requests")));
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.failedFuture(nested));

        ToolLoopTurnResult result = system.processTurn(context);

        assertFalse(result.finalAnswerReady());
        assertEquals(1, result.llmCalls());
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_RATE_LIMIT,
                context.getAttribute(ContextAttributes.LLM_ERROR_CODE));
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        assertNotNull(llmError);
        assertTrue(llmError.contains("RateLimitException"));
    }

    @Test
    void shouldPreferEmbeddedErrorCodeFromThrowableMessage() {
        AgentContext context = buildContext();
        String explicitCode = "llm.synthetic.explicit";
        Throwable throwable = new RuntimeException("[" + explicitCode + "] synthetic failure",
                new TimeoutException("request timed out"));
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.failedFuture(throwable));

        ToolLoopTurnResult result = system.processTurn(context);

        assertFalse(result.finalAnswerReady());
        assertEquals(explicitCode, context.getAttribute(ContextAttributes.LLM_ERROR_CODE));
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        assertNotNull(llmError);
        assertTrue(llmError.startsWith("[" + explicitCode + "]"));
    }

    @Test
    void shouldUsePlaceholderWhenRootCauseMessageIsMissing() {
        AgentContext context = buildContext();
        Throwable throwable = new RuntimeException(new RuntimeException((String) null));
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.failedFuture(throwable));

        ToolLoopTurnResult result = system.processTurn(context);

        assertFalse(result.finalAnswerReady());
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        assertNotNull(llmError);
        assertTrue(llmError.contains("message=n/a"));
    }

    @Test
    void shouldNotRetryWhenVoiceOnlyResponseIsPresent() {
        AgentContext context = buildContext();
        context.setVoiceText("voice response");

        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(finalResponse(null)));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(1, result.llmCalls());
        verify(llmPort, times(1)).chat(any());
    }

    // ==================== Tool execution ====================

    @Test
    void shouldExecuteToolCallsAndContinue() {
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        LlmResponse finalResp = finalResponse(CONTENT_DONE);

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(withTools))
                .thenReturn(CompletableFuture.completedFuture(finalResp));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(2, result.llmCalls());
        assertEquals(1, result.toolExecutions());
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

    // ==================== Stop conditions ====================

    @Test
    void shouldStopOnConfirmationDenied() {
        settings.setStopOnConfirmationDenied(true);
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME,
                ToolResult.failure(ToolFailureKind.CONFIRMATION_DENIED, USER_DENIED),
                USER_DENIED, false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(1, result.llmCalls());
    }

    @Test
    void shouldStopOnToolPolicyDenied() {
        settings.setStopOnToolPolicyDenied(true);
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME,
                ToolResult.failure(ToolFailureKind.POLICY_DENIED, "Policy denied"),
                "Policy denied", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    @Test
    void shouldStopOnToolFailureWhenEnabled() {
        settings.setStopOnToolFailure(true);
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME,
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "Failed"),
                "Failed", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    @Test
    void shouldStopOnToolFailureWhenOutcomeToolNameIsMissing() {
        settings.setStopOnToolFailure(true);
        DefaultToolLoopSystem runtimeEventSystem = buildSystemWithRuntimeEvents();
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, null,
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "Failed"),
                "Failed", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = assertDoesNotThrow(() -> runtimeEventSystem.processTurn(context));

        assertTrue(result.finalAnswerReady());
        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        assertNotNull(events);

        RuntimeEvent turnFinished = events.stream()
                .filter(event -> event.type() == RuntimeEventType.TURN_FINISHED)
                .filter(event -> "tool_failure".equals(event.payload().get("reason")))
                .findFirst()
                .orElseThrow();
        assertNull(turnFinished.payload().get("tool"));
    }

    @Test
    void shouldNotStopOnConfirmationDeniedWhenDisabled() {
        settings.setStopOnConfirmationDenied(false);
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        LlmResponse finalResp = finalResponse("Continued anyway");

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(withTools))
                .thenReturn(CompletableFuture.completedFuture(finalResp));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME,
                ToolResult.failure(ToolFailureKind.CONFIRMATION_DENIED, "Denied"),
                "Denied", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertEquals(2, result.llmCalls());
    }

    @Test
    void shouldStopOnRepeatedToolFailureWithNormalizedFingerprint() {
        AgentContext context = buildContext();
        Message.ToolCall firstCall = toolCall(TOOL_CALL_ID, TOOL_NAME);
        Message.ToolCall secondCall = toolCall("tc-2", TOOL_NAME);

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(firstCall))))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(secondCall))));

        ToolExecutionOutcome firstOutcome = new ToolExecutionOutcome(
                TOOL_CALL_ID,
                TOOL_NAME,
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, " Timeout\n"),
                " Timeout\n",
                false,
                null);
        ToolExecutionOutcome secondOutcome = new ToolExecutionOutcome(
                "tc-2",
                TOOL_NAME,
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "timeout"),
                "timeout",
                false,
                null);
        when(toolExecutor.execute(any(), any()))
                .thenReturn(firstOutcome)
                .thenReturn(secondOutcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(2, result.llmCalls());
        assertEquals(2, result.toolExecutions());
        assertFalse(Boolean.TRUE.equals(context.getAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REACHED)));
        assertTrue(context.getFailures().stream()
                .anyMatch(failure -> failure.message().contains("Repeated tool failure: " + TOOL_NAME)));

        LlmResponse llmResponse = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        assertNotNull(llmResponse);
        assertTrue(llmResponse.getContent().contains("repeated tool failure (" + TOOL_NAME + ")"));
    }

    @Test
    void shouldInjectRecoveryHintForRepeatedRecoverableShellFailure() {
        AgentContext context = buildContext();
        Message.ToolCall firstCall = toolCall(TOOL_CALL_ID, "shell");
        firstCall.setArguments(Map.of("command", "cat missing.txt"));
        Message.ToolCall secondCall = toolCall("tc-2", "shell");
        secondCall.setArguments(Map.of("command", "cat missing.txt"));

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(firstCall))))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(secondCall))))
                .thenReturn(CompletableFuture.completedFuture(finalResponse("Recovered after hint")));

        ToolExecutionOutcome firstOutcome = new ToolExecutionOutcome(
                TOOL_CALL_ID,
                "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory"),
                "No such file or directory",
                false,
                null);
        ToolExecutionOutcome secondOutcome = new ToolExecutionOutcome(
                "tc-2",
                "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory"),
                "No such file or directory",
                false,
                null);
        when(toolExecutor.execute(any(), any()))
                .thenReturn(firstOutcome)
                .thenReturn(secondOutcome);

        ToolFailureRecoveryService recoveryService = new ToolFailureRecoveryService();
        DefaultToolLoopSystem recoverySystem = new DefaultToolLoopSystem(
                llmPort, toolExecutor, historyWriter, viewBuilder,
                turnSettings, settings, modelSelectionService, planService,
                null, null, null, null, null, recoveryService, clock);

        ToolLoopTurnResult result = recoverySystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(3, result.llmCalls());
        verify(historyWriter).appendInternalRecoveryHint(eq(context), any());
    }

    @Test
    void shouldStopWhenRecoverableShellFailureExhaustsRecoveryBudget() {
        AgentContext context = buildContext();
        Message.ToolCall firstCall = toolCall(TOOL_CALL_ID, "shell");
        firstCall.setArguments(Map.of("command", "cat missing.txt"));
        Message.ToolCall secondCall = toolCall("tc-2", "shell");
        secondCall.setArguments(Map.of("command", "cat missing.txt"));
        Message.ToolCall thirdCall = toolCall("tc-3", "shell");
        thirdCall.setArguments(Map.of("command", "cat missing.txt"));
        Message.ToolCall fourthCall = toolCall("tc-4", "shell");
        fourthCall.setArguments(Map.of("command", "cat missing.txt"));

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(firstCall))))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(secondCall))))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(thirdCall))))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(fourthCall))));

        ToolExecutionOutcome failureOne = new ToolExecutionOutcome(
                TOOL_CALL_ID,
                "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory"),
                "No such file or directory",
                false,
                null);
        ToolExecutionOutcome failureTwo = new ToolExecutionOutcome(
                "tc-2",
                "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory"),
                "No such file or directory",
                false,
                null);
        ToolExecutionOutcome failureThree = new ToolExecutionOutcome(
                "tc-3",
                "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory"),
                "No such file or directory",
                false,
                null);
        ToolExecutionOutcome failureFour = new ToolExecutionOutcome(
                "tc-4",
                "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory"),
                "No such file or directory",
                false,
                null);
        when(toolExecutor.execute(any(), any()))
                .thenReturn(failureOne)
                .thenReturn(failureTwo)
                .thenReturn(failureThree)
                .thenReturn(failureFour);

        ToolFailureRecoveryService recoveryService = new ToolFailureRecoveryService();
        DefaultToolLoopSystem recoverySystem = new DefaultToolLoopSystem(
                llmPort, toolExecutor, historyWriter, viewBuilder,
                turnSettings, settings, modelSelectionService, planService,
                null, null, null, null, null, recoveryService, clock);

        ToolLoopTurnResult result = recoverySystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(4, result.llmCalls());
        verify(historyWriter, times(2)).appendInternalRecoveryHint(eq(context), any());
        LlmResponse llmResponse = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        assertNotNull(llmResponse);
        assertTrue(llmResponse.getContent().contains("repeated tool failure (shell)"));
    }

    @Test
    void shouldNotTreatChangedShellCommandAsRepeatedFailure() {
        AgentContext context = buildContext();
        Message.ToolCall firstCall = toolCall(TOOL_CALL_ID, "shell");
        firstCall.setArguments(Map.of("command", "cat missing.txt"));
        Message.ToolCall secondCall = toolCall("tc-2", "shell");
        secondCall.setArguments(Map.of("command", "find . -name missing.txt"));

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(firstCall))))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(secondCall))))
                .thenReturn(CompletableFuture.completedFuture(finalResponse("Recovered")));

        ToolExecutionOutcome firstOutcome = new ToolExecutionOutcome(
                TOOL_CALL_ID,
                "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory"),
                "No such file or directory",
                false,
                null);
        ToolExecutionOutcome secondOutcome = new ToolExecutionOutcome(
                "tc-2",
                "shell",
                ToolResult.success("./missing.txt"),
                "./missing.txt",
                false,
                null);
        when(toolExecutor.execute(any(), any()))
                .thenReturn(firstOutcome)
                .thenReturn(secondOutcome);

        ToolFailureRecoveryService recoveryService = new ToolFailureRecoveryService();
        DefaultToolLoopSystem recoverySystem = new DefaultToolLoopSystem(
                llmPort, toolExecutor, historyWriter, viewBuilder,
                turnSettings, settings, modelSelectionService, planService,
                null, null, null, null, null, recoveryService, clock);

        ToolLoopTurnResult result = recoverySystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(3, result.llmCalls());
        verify(historyWriter, never()).appendInternalRecoveryHint(eq(context), any());
    }

    @Test
    void shouldStopImmediatelyForFatalShellFailure() {
        AgentContext context = buildContext();
        Message.ToolCall firstCall = toolCall(TOOL_CALL_ID, "shell");
        firstCall.setArguments(Map.of("command", "dangerous"));
        Message.ToolCall secondCall = toolCall("tc-2", "shell");
        secondCall.setArguments(Map.of("command", "dangerous"));

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(firstCall))))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(secondCall))));

        ToolExecutionOutcome firstOutcome = new ToolExecutionOutcome(
                TOOL_CALL_ID,
                "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "Command injection detected"),
                "Command injection detected",
                false,
                null);
        ToolExecutionOutcome secondOutcome = new ToolExecutionOutcome(
                "tc-2",
                "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "Command injection detected"),
                "Command injection detected",
                false,
                null);
        when(toolExecutor.execute(any(), any()))
                .thenReturn(firstOutcome)
                .thenReturn(secondOutcome);

        ToolFailureRecoveryService recoveryService = new ToolFailureRecoveryService();
        DefaultToolLoopSystem recoverySystem = new DefaultToolLoopSystem(
                llmPort, toolExecutor, historyWriter, viewBuilder,
                turnSettings, settings, modelSelectionService, planService,
                null, null, null, null, null, recoveryService, clock);

        ToolLoopTurnResult result = recoverySystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(2, result.llmCalls());
        verify(historyWriter, never()).appendInternalRecoveryHint(eq(context), any());
        LlmResponse llmResponse = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        assertNotNull(llmResponse);
        assertTrue(llmResponse.getContent().contains("repeated tool failure (shell)"));
    }

    @Test
    void shouldStopWhenMaxLlmCallsReached() {
        turnSettings.setMaxLlmCalls(2);
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(2, result.llmCalls());
    }

    @Test
    void shouldStopWhenMaxToolExecutionsReached() {
        turnSettings.setMaxToolExecutions(1);
        AgentContext context = buildContext();
        Message.ToolCall tc1 = toolCall(TOOL_CALL_ID, TOOL_NAME);
        Message.ToolCall tc2 = toolCall("tc-2", TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc1, tc2));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    // ==================== Null settings ====================

    @Test
    void shouldUseDefaultsWhenSettingsAreNull() {
        DefaultToolLoopSystem nullSettingsSystem = new DefaultToolLoopSystem(
                llmPort, toolExecutor, historyWriter, viewBuilder, null, modelSelectionService, planService, clock);

        AgentContext context = buildContext();
        LlmResponse response = finalResponse(CONTENT_HELLO);
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        ToolLoopTurnResult result = nullSettingsSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    // ==================== Null modelSelectionService ====================

    @Test
    void shouldUseNullModelWhenModelSelectionServiceIsNull() {
        DefaultToolLoopSystem nullRouterSystem = new DefaultToolLoopSystem(
                llmPort, toolExecutor, historyWriter, viewBuilder, settings, null, planService, clock);

        AgentContext context = buildContext();
        LlmResponse response = finalResponse(CONTENT_HELLO);
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        ToolLoopTurnResult result = nullRouterSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    // ==================== Model tier selection ====================

    @Test
    void shouldSelectSmartModel() {
        AgentContext context = buildContext();
        context.setModelTier("smart");
        LlmResponse response = finalResponse("Smart answer");
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    @Test
    void shouldSelectCodingModel() {
        AgentContext context = buildContext();
        context.setModelTier("coding");
        LlmResponse response = finalResponse("Code answer");
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    @Test
    void shouldSelectDeepModel() {
        AgentContext context = buildContext();
        context.setModelTier("deep");
        LlmResponse response = finalResponse("Deep answer");
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    @Test
    void shouldFailWhenExplicitTierIsUnknown() {
        AgentContext context = buildContext();
        context.setModelTier("nonexistent");
        when(modelSelectionService.resolveForTier("nonexistent"))
                .thenThrow(new IllegalArgumentException("Unknown model tier: nonexistent"));

        ToolLoopTurnResult result = system.processTurn(context);

        assertFalse(result.finalAnswerReady());
        assertNotNull(context.getAttribute(ContextAttributes.LLM_ERROR));
        verify(llmPort, never()).chat(any());
    }

    // ==================== Null outcome from tool executor ====================

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

    // ==================== ensureMessageLists edge cases ====================

    @Test
    void shouldInitializeNullMessagesOnContext() {
        AgentSession session = AgentSession.builder()
                .id("sess-1")
                .chatId("chat-1")
                .messages(null)
                .metadata(new HashMap<>())
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(null)
                .maxIterations(1)
                .currentIteration(0)
                .build();

        LlmResponse response = finalResponse(CONTENT_HELLO);
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertNotNull(context.getMessages());
    }

    // ==================== storeSelectedModel edge cases ====================

    @Test
    void shouldHandleNullSessionInStoreSelectedModel() {
        AgentContext context = AgentContext.builder()
                .session(null)
                .messages(new ArrayList<>())
                .maxIterations(1)
                .currentIteration(0)
                .build();

        LlmResponse response = finalResponse("No session");
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    @Test
    void shouldHandleNullMetadataInStoreSelectedModel() {
        AgentSession session = AgentSession.builder()
                .id("sess-1")
                .chatId("chat-1")
                .metadata(null)
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .maxIterations(1)
                .currentIteration(0)
                .build();

        LlmResponse response = finalResponse("Null metadata");
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    // ==================== applyAttachments with existing response
    // ====================

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

    // ==================== stopTurn with already-recorded tool results
    // ====================

    @Test
    void shouldSkipDuplicateSyntheticResultsInStopTurn() {
        turnSettings.setMaxLlmCalls(1);
        AgentContext context = buildContext();
        Message.ToolCall tc1 = toolCall(TOOL_CALL_ID, TOOL_NAME);
        Message.ToolCall tc2 = toolCall("tc-2", TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc1, tc2));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome1 = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null);
        ToolExecutionOutcome outcome2 = new ToolExecutionOutcome(
                "tc-2", TOOL_NAME, ToolResult.success("ok2"), "ok2", false, null);
        when(toolExecutor.execute(any(), any()))
                .thenReturn(outcome1)
                .thenReturn(outcome2);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    // ==================== LLM_RESPONSE replacement on stop ====================

    @Test
    void shouldReplaceLlmResponseWithCleanResponseOnStop() {
        turnSettings.setMaxLlmCalls(1);
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());

        LlmResponse replacedResponse = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        assertNotNull(replacedResponse);
        assertFalse(replacedResponse.hasToolCalls(), "LLM_RESPONSE should have no tool calls after stop");
        assertTrue(replacedResponse.getContent().contains("reached max internal LLM calls"),
                "LLM_RESPONSE content should contain the stop reason");
    }

    @Test
    void shouldSetToolLoopLimitReachedWhenMaxLlmCallsExhausted() {
        turnSettings.setMaxLlmCalls(1);
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        system.processTurn(context);

        Boolean limitReached = context.getAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REACHED);
        assertTrue(Boolean.TRUE.equals(limitReached),
                "TOOL_LOOP_LIMIT_REACHED should be set when LLM call limit exhausted");
    }

    @Test
    void shouldSetLimitReasonWhenMaxLlmCallsExhausted() {
        turnSettings.setMaxLlmCalls(1);
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        system.processTurn(context);

        assertEquals(me.golemcore.bot.domain.model.TurnLimitReason.MAX_LLM_CALLS,
                context.getAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REASON));
    }

    @Test
    void shouldSetLimitReasonWhenMaxToolExecutionsExhausted() {
        turnSettings.setMaxToolExecutions(1);
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        system.processTurn(context);

        assertEquals(me.golemcore.bot.domain.model.TurnLimitReason.MAX_TOOL_EXECUTIONS,
                context.getAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REASON));
    }

    @Test
    void shouldNotSetToolLoopLimitReachedOnConfirmationDenied() {
        settings.setStopOnConfirmationDenied(true);
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME,
                ToolResult.failure(ToolFailureKind.CONFIRMATION_DENIED, USER_DENIED),
                USER_DENIED, false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        system.processTurn(context);

        Boolean limitReached = context.getAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REACHED);
        assertFalse(Boolean.TRUE.equals(limitReached),
                "TOOL_LOOP_LIMIT_REACHED should NOT be set for confirmation denied stop");
    }

    // ==================== Conversation view diagnostics ====================

    @Test
    void shouldLogDiagnosticsFromConversationView() {
        when(viewBuilder.buildView(any(), any()))
                .thenReturn(new ConversationView(List.of(), List.of("Truncated 3 messages")));

        AgentContext context = buildContext();
        LlmResponse response = finalResponse(CONTENT_HELLO);
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    // ==================== Null tool result in outcome ====================

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
}
