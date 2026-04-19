package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.ContextCompactionPolicy;
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
import java.util.concurrent.atomic.AtomicInteger;
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
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(2_000_000_000);
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

        DefaultToolLoopSystem toolLoop = DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(new DefaultConversationViewBuilder(
                        new me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker()))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .contextCompactionPolicy(new ContextCompactionPolicy(
                        mock(me.golemcore.bot.domain.service.RuntimeConfigService.class), modelSelectionService))
                .clock(Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC))
                .build();

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

    @Test
    void shouldFlattenToolMessagesAcrossRepeatedModelSwitchesWhileKeepingGeminiMetadataInRawHistory() {
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("telegram")
                .chatId("chat1")
                .metadata(new HashMap<>(Map.of(me.golemcore.bot.domain.model.ContextAttributes.LLM_MODEL,
                        "google/gemini-3.1-preview")))
                .messages(new ArrayList<>())
                .build();

        Message assistantWithToolCall = Message.builder()
                .role("assistant")
                .content("Calling tool")
                .toolCalls(List.of(Message.ToolCall.builder().id("tc1").name("shell")
                        .arguments(Map.of("command", "echo hi")).build()))
                .metadata(new HashMap<>(Map.of("thinking_signature", "sig-123")))
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
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(2_000_000_000);
        when(modelSelectionService.resolveForTier("coding"))
                .thenReturn(new ModelSelectionService.ModelSelection("openai/gpt-5.1", null))
                .thenReturn(new ModelSelectionService.ModelSelection("google/gemini-3.1-preview", null));

        List<LlmRequest> capturedRequests = new ArrayList<>();
        LlmPort llmPort = mock(LlmPort.class);
        AtomicInteger llmCalls = new AtomicInteger();
        when(llmPort.chat(any(LlmRequest.class))).thenAnswer(inv -> {
            capturedRequests.add(inv.getArgument(0));
            int call = llmCalls.incrementAndGet();
            return CompletableFuture.completedFuture(LlmResponse.builder()
                    .content("ok-" + call)
                    .finishReason("stop")
                    .build());
        });

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);
        DefaultHistoryWriter historyWriter = new DefaultHistoryWriter(
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        BotProperties.ToolLoopProperties settings = new BotProperties.ToolLoopProperties();

        DefaultToolLoopSystem toolLoop = DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(new DefaultConversationViewBuilder(
                        new me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker()))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .contextCompactionPolicy(new ContextCompactionPolicy(
                        mock(me.golemcore.bot.domain.service.RuntimeConfigService.class), modelSelectionService))
                .clock(Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC))
                .build();

        toolLoop.processTurn(ctx);

        Message secondUser = Message.builder()
                .role("user")
                .content("Try again")
                .timestamp(Instant.parse("2026-01-01T00:00:02Z"))
                .build();
        session.addMessage(secondUser);
        ctx.getMessages().add(secondUser);

        toolLoop.processTurn(ctx);

        assertEquals(2, capturedRequests.size());

        Message firstSwitchAssistant = capturedRequests.get(0).getMessages().get(0);
        assertEquals("assistant", firstSwitchAssistant.getRole());
        assertFalse(firstSwitchAssistant.hasToolCalls());
        assertTrue(firstSwitchAssistant.getContent().contains("masked"));
        assertEquals("sig-123", firstSwitchAssistant.getMetadata().get("thinking_signature"));

        Message secondSwitchAssistant = capturedRequests.get(1).getMessages().get(0);
        assertEquals("assistant", secondSwitchAssistant.getRole());
        assertFalse(secondSwitchAssistant.hasToolCalls());
        assertTrue(secondSwitchAssistant.getContent().contains("masked"));
        assertEquals("sig-123", secondSwitchAssistant.getMetadata().get("thinking_signature"));

        assertTrue(ctx.getMessages().get(0).hasToolCalls(), "raw history must keep toolCalls after repeated switches");
        assertEquals("sig-123", ctx.getMessages().get(0).getMetadata().get("thinking_signature"));
        assertTrue(session.getMessages().get(0).hasToolCalls(), "session raw history must keep toolCalls");
        assertEquals("sig-123", session.getMessages().get(0).getMetadata().get("thinking_signature"));
        assertEquals("google/gemini-3.1-preview",
                session.getMetadata().get(me.golemcore.bot.domain.model.ContextAttributes.LLM_MODEL));
    }
}
