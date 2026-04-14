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
import me.golemcore.bot.domain.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultToolLoopSystemToolExecutionTest extends DefaultToolLoopSystemFixture {

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
}
