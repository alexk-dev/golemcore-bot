package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.system.toolloop.DefaultHistoryWriter;
import me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem;
import me.golemcore.bot.domain.system.toolloop.ToolExecutorPort;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ToolLoopModelTierSelectionTest {

    @Test
    void shouldSelectModelFromTierRouterProperties() {
        // GIVEN
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("telegram")
                .chatId("chat1")
                .messages(new ArrayList<>())
                .build();

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .modelTier("coding")
                .build();

        BotProperties.ModelRouterProperties router = new BotProperties.ModelRouterProperties();
        router.setCodingModel("my-coding-model");
        router.setCodingModelReasoning("low");

        AtomicReference<LlmRequest> captured = new AtomicReference<>();

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.chat(any(LlmRequest.class))).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return CompletableFuture.completedFuture(LlmResponse.builder().content("ok").build());
        });

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);
        DefaultHistoryWriter historyWriter = new DefaultHistoryWriter(
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        DefaultToolLoopSystem toolLoop = new DefaultToolLoopSystem(llmPort, toolExecutor, historyWriter, null, router);

        // WHEN
        toolLoop.processTurn(ctx);

        // THEN
        assertNotNull(captured.get());
        assertEquals("my-coding-model", captured.get().getModel());
        assertEquals("low", captured.get().getReasoningEffort());
    }
}
