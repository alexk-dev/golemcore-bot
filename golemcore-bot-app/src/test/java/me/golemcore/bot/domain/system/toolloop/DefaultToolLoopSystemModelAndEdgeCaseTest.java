package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.system.toolloop.view.ConversationView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultToolLoopSystemModelAndEdgeCaseTest extends DefaultToolLoopSystemFixture {

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
}
