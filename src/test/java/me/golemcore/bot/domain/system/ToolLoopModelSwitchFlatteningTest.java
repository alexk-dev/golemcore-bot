package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.system.toolloop.DefaultHistoryWriter;
import me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem;
import me.golemcore.bot.domain.system.toolloop.ToolExecutorPort;
import me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolLoopModelSwitchFlatteningTest {

    @Test
    void shouldFlattenToolMessagesOnModelSwitchWithoutMutatingRawHistory() {
        // GIVEN: session metadata already has previous model
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("telegram")
                .chatId("chat1")
                .metadata(new HashMap<>(Map.of(me.golemcore.bot.domain.model.ContextAttributes.LLM_MODEL, "old")))
                .messages(new ArrayList<>())
                .build();

        // AND: raw history includes tool-call interaction
        Message assistantWithToolCall = Message.builder()
                .role("assistant")
                .content("Calling tool")
                .toolCalls(List.of(Message.ToolCall.builder().id("tc1").name("shell")
                        .arguments(Map.of("command", "echo hi")).build()))
                .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                .build();

        Message toolResult = Message.builder()
                .role("tool")
                .toolCallId("tc1")
                .toolName("shell")
                .content("hi")
                .timestamp(Instant.parse("2026-01-01T00:00:01Z"))
                .build();

        session.addMessage(assistantWithToolCall);
        session.addMessage(toolResult);

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .toolResults(Map.of("tc1", ToolResult.success("hi")))
                .modelTier("coding")
                .build();

        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveForTier("coding")).thenReturn(
                new ModelSelectionService.ModelSelection("new", null));

        AtomicReference<LlmRequest> captured = new AtomicReference<>();
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.chat(any(LlmRequest.class))).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return CompletableFuture.completedFuture(LlmResponse.builder().content("ok").build());
        });

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);
        DefaultHistoryWriter historyWriter = new DefaultHistoryWriter(
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        BotProperties.ToolLoopProperties settings = new BotProperties.ToolLoopProperties();

        DefaultToolLoopSystem toolLoop = new DefaultToolLoopSystem(
                llmPort,
                toolExecutor,
                historyWriter,
                new DefaultConversationViewBuilder(
                        new me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker()),
                settings,
                modelSelectionService,
                null,
                Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC));

        // WHEN
        toolLoop.processTurn(ctx);

        // THEN: request-time view is flattened (assistant message has no toolCalls)
        assertNotNull(captured.get());
        assertEquals("new", captured.get().getModel());

        List<Message> reqMessages = captured.get().getMessages();
        assertEquals(2, reqMessages.size());

        assertEquals("assistant", reqMessages.get(0).getRole());
        assertFalse(reqMessages.get(0).hasToolCalls(), "toolCalls must be flattened away in request view");
        assertTrue(reqMessages.get(0).getContent().contains("masked"));

        assertEquals("assistant", reqMessages.get(1).getRole());
        assertFalse(reqMessages.get(1).hasToolCalls(), "toolCalls must be flattened away in request view");

        // AND: raw history in context/session not mutated
        assertTrue(ctx.getMessages().get(0).hasToolCalls(), "raw history must keep toolCalls");
        assertTrue(session.getMessages().get(0).hasToolCalls(), "session raw history must keep toolCalls");

        // AND: metadata now tracks new model
        assertEquals("new", session.getMetadata().get(me.golemcore.bot.domain.model.ContextAttributes.LLM_MODEL));
    }
}
