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

    @Test
    void shouldStoreCompactionDetailsAndFileChangesWhenOverflowRecoveryReturnsDetails() {
        AgentSession session = AgentSession.builder()
                .id("s4")
                .channelType("telegram")
                .chatId("c4")
                .messages(new ArrayList<>())
                .metadata(new java.util.LinkedHashMap<>())
                .build();
        for (int i = 0; i < 6; i++) {
            session.addMessage(Message.builder()
                    .role(i % 2 == 0 ? "user" : "assistant")
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
                    .content("recovered with details")
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
        when(compactionOrchestrationService.compact("s4", CompactionReason.CONTEXT_OVERFLOW_RECOVERY, 2))
                .thenAnswer(invocation -> {
                    Message summary = Message.builder()
                            .role("system")
                            .content("[Conversation summary]\nsummary with details")
                            .timestamp(NOW)
                            .build();
                    List<Message> compacted = new ArrayList<>();
                    compacted.add(summary);
                    compacted.addAll(session.getMessages().subList(4, 6));
                    session.getMessages().clear();
                    session.getMessages().addAll(compacted);

                    me.golemcore.bot.domain.model.CompactionDetails details = me.golemcore.bot.domain.model.CompactionDetails
                            .builder()
                            .schemaVersion(1)
                            .reason(CompactionReason.CONTEXT_OVERFLOW_RECOVERY)
                            .summarizedCount(4)
                            .keptCount(2)
                            .usedLlmSummary(true)
                            .summaryLength(21)
                            .toolCount(1)
                            .readFilesCount(1)
                            .modifiedFilesCount(1)
                            .durationMs(7)
                            .toolNames(List.of("filesystem"))
                            .readFiles(List.of("src/A.java"))
                            .modifiedFiles(List.of("src/B.java"))
                            .fileChanges(List.of(
                                    me.golemcore.bot.domain.model.CompactionDetails.FileChangeStat.builder()
                                            .path("src/B.java")
                                            .addedLines(3)
                                            .removedLines(1)
                                            .deleted(false)
                                            .build()))
                            .splitTurnDetected(true)
                            .fallbackUsed(false)
                            .build();

                    return CompactionResult.builder()
                            .removed(4)
                            .usedSummary(true)
                            .summaryMessage(summary)
                            .details(details)
                            .build();
                });

        DefaultToolLoopSystem system = buildSystem(llmPort, runtimeConfigService, compactionOrchestrationService,
                mock(ToolExecutorPort.class));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(2, llmCalls.get());
        assertNotNull(context.getAttribute(ContextAttributes.COMPACTION_LAST_DETAILS));

        Object fileChangesObject = context.getAttribute(ContextAttributes.TURN_FILE_CHANGES);
        assertTrue(fileChangesObject instanceof List<?>);
        List<?> fileChanges = (List<?>) fileChangesObject;
        assertEquals(1, fileChanges.size());
    }

    @Test
    void shouldCaptureFilesystemTurnFileChangesFromToolCalls() {
        AgentSession session = AgentSession.builder()
                .id("s5")
                .channelType("telegram")
                .chatId("c5")
                .messages(new ArrayList<>())
                .metadata(new java.util.LinkedHashMap<>())
                .build();
        session.addMessage(Message.builder().role("user").content("edit files").timestamp(NOW).build());

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        Message.ToolCall writeOne = Message.ToolCall.builder()
                .id("fs-1")
                .name("filesystem")
                .arguments(Map.of("operation", "write_file", "path", "a.txt", "content", "l1\nl2"))
                .build();
        Message.ToolCall appendOne = Message.ToolCall.builder()
                .id("fs-2")
                .name("filesystem")
                .arguments(Map.of("operation", "append", "path", "a.txt", "content", "l3"))
                .build();
        Message.ToolCall deleteFile = Message.ToolCall.builder()
                .id("fs-3")
                .name("filesystem")
                .arguments(Map.of("operation", "delete", "path", "b.txt"))
                .build();

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("editing")
                        .toolCalls(List.of(writeOne, appendOne, deleteFile))
                        .build()))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("done")
                        .finishReason("stop")
                        .build()));

        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getTurnMaxLlmCalls()).thenReturn(10);
        when(runtimeConfigService.getTurnMaxToolExecutions()).thenReturn(10);
        when(runtimeConfigService.getTurnDeadline()).thenReturn(java.time.Duration.ofMinutes(5));
        when(runtimeConfigService.isTurnAutoRetryEnabled()).thenReturn(false);

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);
        when(toolExecutor.execute(any(), any())).thenAnswer(invocation -> {
            Message.ToolCall call = invocation.getArgument(1);
            return new ToolExecutionOutcome(call.getId(), call.getName(), ToolResult.success("ok"), "ok", false,
                    null);
        });

        DefaultToolLoopSystem system = buildSystem(llmPort, runtimeConfigService, null, toolExecutor);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        Object fileChangesObject = context.getAttribute(ContextAttributes.TURN_FILE_CHANGES);
        assertTrue(fileChangesObject instanceof List<?>);

        List<?> fileChanges = (List<?>) fileChangesObject;
        assertEquals(2, fileChanges.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) fileChanges.get(0);
        assertEquals("a.txt", first.get("path"));
        assertEquals(3, ((Number) first.get("addedLines")).intValue());
        assertEquals(0, ((Number) first.get("removedLines")).intValue());
        assertEquals(Boolean.FALSE, first.get("deleted"));

        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) fileChanges.get(1);
        assertEquals("b.txt", second.get("path"));
        assertEquals(0, ((Number) second.get("addedLines")).intValue());
        assertEquals(1, ((Number) second.get("removedLines")).intValue());
        assertEquals(Boolean.TRUE, second.get("deleted"));
    }

    @Test
    void shouldNotCaptureFileChangesForNonFilesystemToolCalls() {
        AgentSession session = AgentSession.builder()
                .id("s6")
                .channelType("telegram")
                .chatId("c6")
                .messages(new ArrayList<>())
                .metadata(new java.util.LinkedHashMap<>())
                .build();
        session.addMessage(Message.builder().role("user").content("run shell").timestamp(NOW).build());

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        Message.ToolCall shellCall = Message.ToolCall.builder()
                .id("sh-1")
                .name("shell")
                .arguments(Map.of("command", "echo hello"))
                .build();

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("running shell")
                        .toolCalls(List.of(shellCall))
                        .build()))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("done")
                        .finishReason("stop")
                        .build()));

        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getTurnMaxLlmCalls()).thenReturn(10);
        when(runtimeConfigService.getTurnMaxToolExecutions()).thenReturn(10);
        when(runtimeConfigService.getTurnDeadline()).thenReturn(java.time.Duration.ofMinutes(5));
        when(runtimeConfigService.isTurnAutoRetryEnabled()).thenReturn(false);

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);
        when(toolExecutor.execute(any(), any())).thenReturn(new ToolExecutionOutcome(
                shellCall.getId(), shellCall.getName(), ToolResult.success("ok"), "ok", false, null));

        DefaultToolLoopSystem system = buildSystem(llmPort, runtimeConfigService, null, toolExecutor);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertTrue(context.getAttribute(ContextAttributes.TURN_FILE_CHANGES) == null);
    }

    @Test
    void shouldIgnoreFilesystemCallsWithMissingArgumentsForFileChangeCapture() {
        AgentSession session = AgentSession.builder()
                .id("s7")
                .channelType("telegram")
                .chatId("c7")
                .messages(new ArrayList<>())
                .metadata(new java.util.LinkedHashMap<>())
                .build();
        session.addMessage(Message.builder().role("user").content("broken fs args").timestamp(NOW).build());

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        Message.ToolCall noArgs = Message.ToolCall.builder()
                .id("fs-x")
                .name("filesystem")
                .arguments(null)
                .build();
        Message.ToolCall missingPath = Message.ToolCall.builder()
                .id("fs-y")
                .name("filesystem")
                .arguments(Map.of("operation", "write_file"))
                .build();
        Message.ToolCall blankPath = Message.ToolCall.builder()
                .id("fs-z")
                .name("filesystem")
                .arguments(Map.of("operation", "delete", "path", "   "))
                .build();

        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("running fs")
                        .toolCalls(List.of(noArgs, missingPath, blankPath))
                        .build()))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("done")
                        .finishReason("stop")
                        .build()));

        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getTurnMaxLlmCalls()).thenReturn(10);
        when(runtimeConfigService.getTurnMaxToolExecutions()).thenReturn(10);
        when(runtimeConfigService.getTurnDeadline()).thenReturn(java.time.Duration.ofMinutes(5));
        when(runtimeConfigService.isTurnAutoRetryEnabled()).thenReturn(false);

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);
        when(toolExecutor.execute(any(), any())).thenAnswer(invocation -> {
            Message.ToolCall call = invocation.getArgument(1);
            return new ToolExecutionOutcome(call.getId(), call.getName(), ToolResult.success("ok"), "ok", false,
                    null);
        });

        DefaultToolLoopSystem system = buildSystem(llmPort, runtimeConfigService, null, toolExecutor);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertTrue(context.getAttribute(ContextAttributes.TURN_FILE_CHANGES) == null);
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
