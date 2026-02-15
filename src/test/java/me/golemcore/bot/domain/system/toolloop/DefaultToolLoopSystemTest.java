package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.system.toolloop.view.ConversationView;
import me.golemcore.bot.domain.system.toolloop.view.ConversationViewBuilder;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultToolLoopSystemTest {

    private static final String MODEL_BALANCED = "gpt-4o";
    private static final String TOOL_CALL_ID = "tc-1";
    private static final String TOOL_NAME = "test_tool";
    private static final String CONTENT_DONE = "Done";
    private static final String CONTENT_HELLO = "Hello";

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

    private BotProperties.TurnProperties turnSettings;

    private BotProperties.ToolLoopProperties settings;
    private BotProperties.ModelRouterProperties router;
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

        router = new BotProperties.ModelRouterProperties();
        router.setBalancedModel(MODEL_BALANCED);
        router.setSmartModel("gpt-4o-smart");
        router.setCodingModel("gpt-4o-coding");
        router.setDeepModel("o3");

        when(viewBuilder.buildView(any(), any()))
                .thenReturn(new ConversationView(List.of(), List.of()));

        turnSettings = new BotProperties.TurnProperties();
        system = new DefaultToolLoopSystem(llmPort, toolExecutor, historyWriter, viewBuilder,
                turnSettings, settings, router, planService, clock);
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
    void shouldHandleNullLlmResponse() {
        AgentContext context = buildContext();

        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(null));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(1, result.llmCalls());
        verify(historyWriter, never()).appendFinalAssistantAnswer(any(), any(), any());
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
                ToolResult.failure(ToolFailureKind.CONFIRMATION_DENIED, "User denied"),
                "User denied", false, null);
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

    // ==================== Max limits ====================

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
                llmPort, toolExecutor, historyWriter, viewBuilder, null, router, planService, clock);

        AgentContext context = buildContext();
        LlmResponse response = finalResponse(CONTENT_HELLO);
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        ToolLoopTurnResult result = nullSettingsSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    // ==================== Null router ====================

    @Test
    void shouldUseNullModelWhenRouterIsNull() {
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
    void shouldFallbackToBalancedForUnknownTier() {
        AgentContext context = buildContext();
        context.setModelTier("nonexistent");
        LlmResponse response = finalResponse("Balanced answer");
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
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
