package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.CompactionOrchestrationService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.RuntimeEventService;
import me.golemcore.bot.domain.system.toolloop.DefaultHistoryWriter;
import me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem;
import me.golemcore.bot.domain.system.toolloop.ToolExecutionOutcome;
import me.golemcore.bot.domain.system.toolloop.ToolExecutorPort;
import me.golemcore.bot.domain.system.toolloop.ToolLoopTurnResult;
import me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder;
import me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolLoopResilienceBddTest {

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    @Test
    void shouldAutoRetryTransientLlmFailureAndFinishTurn() {
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("telegram")
                .chatId("c1")
                .messages(new ArrayList<>())
                .build();
        session.addMessage(Message.builder().role("user").content("hello").timestamp(NOW).build());

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        LlmPort llmPort = mock(LlmPort.class);
        AtomicInteger llmCalls = new AtomicInteger();
        when(llmPort.chat(any(LlmRequest.class))).thenAnswer(invocation -> {
            int call = llmCalls.incrementAndGet();
            if (call == 1) {
                return CompletableFuture.failedFuture(new RuntimeException("["
                        + LlmErrorClassifier.LANGCHAIN4J_RATE_LIMIT + "] 429"));
            }
            return CompletableFuture.completedFuture(LlmResponse.builder()
                    .content("done")
                    .finishReason("stop")
                    .build());
        });

        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getTurnMaxLlmCalls()).thenReturn(10);
        when(runtimeConfigService.getTurnMaxToolExecutions()).thenReturn(10);
        when(runtimeConfigService.getTurnDeadline()).thenReturn(java.time.Duration.ofMinutes(5));
        when(runtimeConfigService.isTurnAutoRetryEnabled()).thenReturn(true);
        when(runtimeConfigService.getTurnAutoRetryMaxAttempts()).thenReturn(2);
        when(runtimeConfigService.getTurnAutoRetryBaseDelayMs()).thenReturn(1L);

        DefaultToolLoopSystem system = buildSystem(llmPort, runtimeConfigService, null, mock(ToolExecutorPort.class));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(2, llmCalls.get());
        assertTrue(context.getAttribute(ContextAttributes.LLM_ERROR) == null);

        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(event -> RuntimeEventType.RETRY_STARTED.equals(event.type())));
        assertTrue(events.stream().anyMatch(event -> RuntimeEventType.RETRY_FINISHED.equals(event.type())));
    }

    @Test
    void shouldRecoverFromContextOverflowByCompactingAndRetrying() {
        AgentSession session = AgentSession.builder()
                .id("s2")
                .channelType("telegram")
                .chatId("c2")
                .messages(new ArrayList<>())
                .build();
        for (int i = 0; i < 8; i++) {
            session.addMessage(Message.builder().role(i % 2 == 0 ? "user" : "assistant")
                    .content("msg-" + i)
                    .timestamp(NOW)
                    .build());
        }

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        LlmPort llmPort = mock(LlmPort.class);
        AtomicInteger llmCalls = new AtomicInteger();
        when(llmPort.chat(any(LlmRequest.class))).thenAnswer(invocation -> {
            int call = llmCalls.incrementAndGet();
            if (call == 1) {
                return CompletableFuture.failedFuture(new RuntimeException("context length exceeded"));
            }
            return CompletableFuture.completedFuture(LlmResponse.builder()
                    .content("recovered")
                    .finishReason("stop")
                    .build());
        });

        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getTurnMaxLlmCalls()).thenReturn(10);
        when(runtimeConfigService.getTurnMaxToolExecutions()).thenReturn(10);
        when(runtimeConfigService.getTurnDeadline()).thenReturn(java.time.Duration.ofMinutes(5));
        when(runtimeConfigService.isTurnAutoRetryEnabled()).thenReturn(false);
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(2);

        CompactionOrchestrationService compactionOrchestrationService = mock(CompactionOrchestrationService.class);
        when(compactionOrchestrationService.compact("s2", CompactionReason.CONTEXT_OVERFLOW_RECOVERY, 2))
                .thenAnswer(invocation -> {
                    Message summary = Message.builder()
                            .role("system")
                            .content("[Conversation summary]\nsummary")
                            .timestamp(NOW)
                            .build();
                    List<Message> compacted = new ArrayList<>();
                    compacted.add(summary);
                    compacted.addAll(session.getMessages().subList(6, 8));
                    session.getMessages().clear();
                    session.getMessages().addAll(compacted);
                    return CompactionResult.builder()
                            .removed(6)
                            .usedSummary(true)
                            .summaryMessage(summary)
                            .build();
                });

        DefaultToolLoopSystem system = buildSystem(llmPort, runtimeConfigService, compactionOrchestrationService,
                mock(ToolExecutorPort.class));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(2, llmCalls.get());
        assertTrue(session.getMessages().stream().anyMatch(message -> "system".equals(message.getRole())
                && message.getContent() != null
                && message.getContent().contains("summary")));

        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(event -> RuntimeEventType.COMPACTION_STARTED.equals(event.type())));
        assertTrue(events.stream().anyMatch(event -> RuntimeEventType.COMPACTION_FINISHED.equals(event.type())));
    }

    @Test
    void shouldStopGracefullyWhenInterruptRequestedBetweenToolCalls() {
        AgentSession session = AgentSession.builder()
                .id("s3")
                .channelType("telegram")
                .chatId("c3")
                .messages(new ArrayList<>())
                .build();
        session.addMessage(Message.builder().role("user").content("run tools").timestamp(NOW).build());

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        LlmPort llmPort = mock(LlmPort.class);
        LlmResponse toolCallResponse = LlmResponse.builder()
                .content("running")
                .toolCalls(List.of(
                        Message.ToolCall.builder().id("tc1").name("shell").arguments(Map.of("command", "echo 1"))
                                .build(),
                        Message.ToolCall.builder().id("tc2").name("shell").arguments(Map.of("command", "echo 2"))
                                .build()))
                .build();
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(CompletableFuture.completedFuture(toolCallResponse));

        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getTurnMaxLlmCalls()).thenReturn(10);
        when(runtimeConfigService.getTurnMaxToolExecutions()).thenReturn(10);
        when(runtimeConfigService.getTurnDeadline()).thenReturn(java.time.Duration.ofMinutes(5));
        when(runtimeConfigService.isTurnAutoRetryEnabled()).thenReturn(false);

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);
        when(toolExecutor.execute(any(), any())).thenAnswer(invocation -> {
            session.getMetadata().put(ContextAttributes.TURN_INTERRUPT_REQUESTED, true);
            Message.ToolCall call = invocation.getArgument(1);
            return new ToolExecutionOutcome(call.getId(), call.getName(), ToolResult.success("ok"), "ok", false,
                    null);
        });

        DefaultToolLoopSystem system = buildSystem(llmPort, runtimeConfigService, null, toolExecutor);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        verify(toolExecutor).execute(any(), any());
        verify(toolExecutor, never()).execute(any(), argThat(call -> "tc2".equals(call.getId())));
        assertFalse(Boolean.TRUE.equals(session.getMetadata().get(ContextAttributes.TURN_INTERRUPT_REQUESTED)));
        assertTrue(result.context() != null);
    }

    private DefaultToolLoopSystem buildSystem(LlmPort llmPort, RuntimeConfigService runtimeConfigService,
            CompactionOrchestrationService compactionOrchestrationService, ToolExecutorPort toolExecutor) {
        BotProperties.TurnProperties turnProperties = new BotProperties.TurnProperties();
        BotProperties.ToolLoopProperties toolLoopProperties = new BotProperties.ToolLoopProperties();
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveForTier(any()))
                .thenReturn(new ModelSelectionService.ModelSelection(null, null));
        RuntimeEventService runtimeEventService = new RuntimeEventService(Clock.fixed(NOW, ZoneOffset.UTC), List.of());

        return new DefaultToolLoopSystem(
                llmPort,
                toolExecutor,
                new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC)),
                new DefaultConversationViewBuilder(new FlatteningToolMessageMasker()),
                turnProperties,
                toolLoopProperties,
                modelSelectionService,
                null,
                runtimeConfigService,
                compactionOrchestrationService,
                runtimeEventService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
