package me.golemcore.bot.domain.service;

import me.golemcore.bot.port.outbound.HiveEventPublishPort;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.system.toolloop.DefaultHistoryWriter;
import me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem;
import me.golemcore.bot.domain.system.toolloop.ToolExecutorPort;
import me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder;
import me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionRunCoordinatorStopIntegrationTest {

    private static final String CHANNEL_TYPE = "telegram";
    private static final String CHAT_ID = "1";
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    @Test
    void shouldStopBlockedToolLoopRunAndResumeWithNextInbound() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService();

        try (ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService, null, mock(HiveEventPublishPort.class));

            AgentSession session = AgentSession.builder()
                    .id("s-stop-llm")
                    .channelType(CHANNEL_TYPE)
                    .chatId(CHAT_ID)
                    .messages(new ArrayList<>())
                    .metadata(new LinkedHashMap<>())
                    .build();
            when(sessionPort.getOrCreate(CHANNEL_TYPE, CHAT_ID)).thenReturn(session);
            doNothing().when(sessionPort).save(any(AgentSession.class));

            CountDownLatch llmStarted = new CountDownLatch(1);
            CountDownLatch resumeProcessed = new CountDownLatch(1);
            AtomicInteger llmCalls = new AtomicInteger();
            CompletableFuture<LlmResponse> pendingFirstResponse = new CompletableFuture<>();

            LlmPort llmPort = mock(LlmPort.class);
            when(llmPort.chat(any(LlmRequest.class))).thenAnswer(invocation -> {
                int call = llmCalls.incrementAndGet();
                if (call == 1) {
                    llmStarted.countDown();
                    return pendingFirstResponse;
                }
                return CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("resumed")
                        .finishReason("stop")
                        .build());
            });

            DefaultToolLoopSystem system = buildToolLoopSystem(llmPort, runtimeConfigService);
            doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                session.addMessage(inbound);
                AgentContext context = AgentContext.builder()
                        .session(session)
                        .messages(new ArrayList<>(session.getMessages()))
                        .build();
                system.processTurn(context);
                if ("RESUME".equals(inbound.getContent())) {
                    resumeProcessed.countDown();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            coordinator.enqueue(user("A"));
            assertTrue(llmStarted.await(1, TimeUnit.SECONDS), "Initial LLM call should have started");

            coordinator.requestStop(CHANNEL_TYPE, CHAT_ID);
            coordinator.enqueue(user("RESUME"));

            assertTrue(resumeProcessed.await(2, TimeUnit.SECONDS), "Next inbound should resume after stop");

            verify(runtimeEventService).emitForSession(session, RuntimeEventType.TURN_INTERRUPT_REQUESTED,
                    Map.of("source", "command.stop"));
            assertEquals(2, llmCalls.get());
            assertFalse(Boolean.TRUE.equals(session.getMetadata().get(ContextAttributes.TURN_INTERRUPT_REQUESTED)));
            assertTrue(session.getMessages().stream()
                    .anyMatch(message -> "assistant".equals(message.getRole())
                            && message.getContent() != null
                            && message.getContent().contains("interrupted by user")));
            assertTrue(session.getMessages().stream()
                    .anyMatch(message -> "assistant".equals(message.getRole())
                            && "resumed".equals(message.getContent())));
        }
    }

    private static RuntimeConfigService runtimeConfigService() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isTurnQueueSteeringEnabled()).thenReturn(true);
        when(runtimeConfigService.getTurnQueueSteeringMode()).thenReturn("one-at-a-time");
        when(runtimeConfigService.getTurnQueueFollowUpMode()).thenReturn("one-at-a-time");
        when(runtimeConfigService.getTurnMaxLlmCalls()).thenReturn(10);
        when(runtimeConfigService.getTurnMaxToolExecutions()).thenReturn(10);
        when(runtimeConfigService.getTurnDeadline()).thenReturn(Duration.ofMinutes(5));
        when(runtimeConfigService.isTurnAutoRetryEnabled()).thenReturn(false);
        return runtimeConfigService;
    }

    private DefaultToolLoopSystem buildToolLoopSystem(LlmPort llmPort, RuntimeConfigService runtimeConfigService) {
        BotProperties.TurnProperties turnProperties = new BotProperties.TurnProperties();
        BotProperties.ToolLoopProperties toolLoopProperties = new BotProperties.ToolLoopProperties();
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveMaxInputTokensForContext(any()))
                .thenReturn(2_000_000_000);
        when(modelSelectionService.resolveForTier(any()))
                .thenReturn(new ModelSelectionService.ModelSelection(null, null));

        return DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(mock(ToolExecutorPort.class))
                .historyWriter(new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC)))
                .viewBuilder(new DefaultConversationViewBuilder(new FlatteningToolMessageMasker()))
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnProperties))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(toolLoopProperties))
                .modelSelectionService(modelSelectionService)
                .runtimeConfigService(runtimeConfigService)
                .runtimeEventService(new RuntimeEventService(Clock.fixed(NOW, ZoneOffset.UTC)))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .build();
    }

    private static Message user(String content) {
        return Message.builder()
                .role("user")
                .content(content)
                .channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID)
                .senderId("u1")
                .timestamp(NOW)
                .build();
    }
}
