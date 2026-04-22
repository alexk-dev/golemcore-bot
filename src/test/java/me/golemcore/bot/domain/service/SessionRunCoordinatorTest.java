package me.golemcore.bot.domain.service;

import me.golemcore.bot.port.outbound.HiveEventPublishPort;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionRunCoordinatorTest {

    private static final String CHANNEL_TYPE = "telegram";
    private static final String CHAT_ID = "1";

    @Test
    void shouldRejectNullInboundOnEnqueue() {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(false, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            assertThrows(NullPointerException.class, () -> coordinator.enqueue(null));
        }
    }

    @Test
    void shouldPauseAfterStopAndResumeOnNextInboundFlushingQueuedMessages() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            AgentSession session = AgentSession.builder()
                    .id("s1")
                    .channelType(CHANNEL_TYPE)
                    .chatId(CHAT_ID)
                    .messages(new ArrayList<>())
                    .build();
            when(sessionPort.getOrCreate(CHANNEL_TYPE, CHAT_ID)).thenReturn(session);

            Message a = user("A");
            Message b = user("B");
            Message c = user("C");
            Message d = user("D");

            Gate gateA = new Gate();
            org.mockito.Mockito.doAnswer(inv -> {
                gateA.await();
                return null;
            }).when(agentLoop).processMessage(a);

            coordinator.enqueue(a);
            gateA.awaitStarted();

            coordinator.enqueue(b);
            coordinator.enqueue(c);

            coordinator.requestStop(CHANNEL_TYPE, CHAT_ID);

            coordinator.enqueue(d);

            // Cancelled run should unblock; if not, we release explicitly.
            gateA.release();

            executor.shutdown();
            executor.awaitTermination(2, TimeUnit.SECONDS);

            List<String> contents = session.getMessages().stream().map(Message::getContent).toList();
            assertEquals(List.of("C"), contents);

            verify(agentLoop, times(2)).processMessage(any(Message.class));
            verify(agentLoop, times(1)).processMessage(a);
            verify(agentLoop, times(1)).processMessage(d);
            verify(runtimeEventService).emitForSession(session, RuntimeEventType.TURN_INTERRUPT_REQUESTED,
                    Map.of("source", "command.stop"));
        }
    }

    @Test
    void shouldHandleStopWhenIdle() {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);
            AgentSession session = AgentSession.builder()
                    .id("s-idle")
                    .channelType(CHANNEL_TYPE)
                    .chatId(CHAT_ID)
                    .messages(new ArrayList<>())
                    .build();
            when(sessionPort.getOrCreate(CHANNEL_TYPE, CHAT_ID)).thenReturn(session);

            // requestStop when no task is running should not throw
            coordinator.requestStop(CHANNEL_TYPE, CHAT_ID);

            verify(runtimeEventService).emitForSession(session, RuntimeEventType.TURN_INTERRUPT_REQUESTED,
                    Map.of("source", "command.stop"));
        }
    }

    @Test
    void shouldExposeActiveOrQueuedWork() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            AgentSession session = AgentSession.builder()
                    .id("s-busy")
                    .channelType(CHANNEL_TYPE)
                    .chatId(CHAT_ID)
                    .messages(new ArrayList<>())
                    .build();
            when(sessionPort.getOrCreate(CHANNEL_TYPE, CHAT_ID)).thenReturn(session);

            Message first = user("first");
            Message second = user("second");

            Gate gate = new Gate();
            org.mockito.Mockito.doAnswer(invocation -> {
                gate.await();
                return null;
            }).when(agentLoop).processMessage(first);

            assertFalse(coordinator.hasActiveOrQueuedWork());

            coordinator.enqueue(first);
            gate.awaitStarted();
            assertTrue(coordinator.hasActiveOrQueuedWork());

            coordinator.enqueue(second);
            assertTrue(coordinator.hasActiveOrQueuedWork());

            gate.release();
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

            assertFalse(coordinator.hasActiveOrQueuedWork());
        }
    }

    @Test
    void shouldPublishHiveCancellationFallbackWhenInterruptedOutsideGracefulTurnHandling() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = new RuntimeEventService(
                Clock.fixed(Instant.parse("2026-03-18T00:00:00Z"), ZoneOffset.UTC));
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");
        HiveEventPublishPort hiveEventPublishPort = mock(HiveEventPublishPort.class);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService, null, hiveEventPublishPort);

            AgentSession session = AgentSession.builder()
                    .id("hive:thread-1")
                    .channelType("hive")
                    .chatId("thread-1")
                    .messages(new ArrayList<>())
                    .metadata(new LinkedHashMap<>())
                    .build();
            when(sessionPort.getOrCreate("hive", "thread-1")).thenReturn(session);
            org.mockito.Mockito.doNothing().when(sessionPort).save(any(AgentSession.class));

            CountDownLatch started = new CountDownLatch(1);
            org.mockito.Mockito.doAnswer(invocation -> {
                started.countDown();
                CountDownLatch blocked = new CountDownLatch(1);
                blocked.await();
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            Message inbound = hiveMessage("Inspect the workspace");
            coordinator.enqueue(inbound);
            assertTrue(started.await(1, TimeUnit.SECONDS), "Hive run should have started");

            coordinator.requestStop("hive", "thread-1");

            verify(hiveEventPublishPort, timeout(1000)).publishRuntimeEvents(
                    argThat(SessionRunCoordinatorTest::isUserInterruptFinishEvent),
                    argThat(metadata -> "thread-1".equals(metadata.get(ContextAttributes.HIVE_THREAD_ID))
                            && "card-1".equals(metadata.get(ContextAttributes.HIVE_CARD_ID))
                            && "cmd-1".equals(metadata.get(ContextAttributes.HIVE_COMMAND_ID))
                            && "run-1".equals(metadata.get(ContextAttributes.HIVE_RUN_ID))
                            && "golem-1".equals(metadata.get(ContextAttributes.HIVE_GOLEM_ID))));
            assertFalse(Boolean.TRUE.equals(session.getMetadata().get(ContextAttributes.TURN_INTERRUPT_REQUESTED)));
        }
    }

    @Test
    void shouldCancelOnlyMatchingQueuedHiveRun() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");
        HiveEventPublishPort hiveEventPublishPort = mock(HiveEventPublishPort.class);

        RuntimeEvent cancelledEvent = RuntimeEvent.builder()
                .type(RuntimeEventType.TURN_FINISHED)
                .timestamp(Instant.parse("2026-03-18T00:00:00Z"))
                .sessionId("hive:thread-1")
                .channelType("hive")
                .chatId("thread-1")
                .payload(Map.of("reason", "user_interrupt"))
                .build();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService, null, hiveEventPublishPort);

            AgentSession session = AgentSession.builder()
                    .id("hive:thread-1")
                    .channelType("hive")
                    .chatId("thread-1")
                    .messages(new ArrayList<>())
                    .metadata(new LinkedHashMap<>())
                    .build();
            when(sessionPort.getOrCreate("hive", "thread-1")).thenReturn(session);
            org.mockito.Mockito.doNothing().when(sessionPort).save(any(AgentSession.class));
            when(runtimeEventService.emitForSession(any(AgentSession.class), any(RuntimeEventType.class),
                    any(Map.class)))
                    .thenReturn(cancelledEvent);

            Gate gateA = new Gate();
            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("Inspect run-1".equals(inbound.getContent())) {
                    gateA.await();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            Message firstInbound = hiveMessage("Inspect run-1", "cmd-1", "run-1");
            Message secondInbound = hiveMessage("Inspect run-2", "cmd-2", "run-2");

            coordinator.enqueue(firstInbound);
            gateA.awaitStarted();
            coordinator.enqueue(secondInbound);

            coordinator.requestStop("hive", "thread-1", "run-2", "cmd-2");
            gateA.release();

            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "Coordinator should terminate cleanly");

            verify(agentLoop, times(1)).processMessage(firstInbound);
            verify(agentLoop, never()).processMessage(secondInbound);
            verify(hiveEventPublishPort, timeout(1000)).publishRuntimeEvents(
                    argThat(SessionRunCoordinatorTest::isUserInterruptFinishEvent),
                    argThat(metadata -> "thread-1".equals(metadata.get(ContextAttributes.HIVE_THREAD_ID))
                            && "card-1".equals(metadata.get(ContextAttributes.HIVE_CARD_ID))
                            && "cmd-2".equals(metadata.get(ContextAttributes.HIVE_COMMAND_ID))
                            && "run-2".equals(metadata.get(ContextAttributes.HIVE_RUN_ID))
                            && "golem-1".equals(metadata.get(ContextAttributes.HIVE_GOLEM_ID))));
        }
    }

    @Test
    void shouldContinueWithQueuedHiveCommandAfterCancellingActiveRun() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");
        HiveEventPublishPort hiveEventPublishPort = mock(HiveEventPublishPort.class);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService, null, hiveEventPublishPort);

            AgentSession session = AgentSession.builder()
                    .id("hive:thread-1")
                    .channelType("hive")
                    .chatId("thread-1")
                    .messages(new ArrayList<>())
                    .metadata(new LinkedHashMap<>())
                    .build();
            when(sessionPort.getOrCreate("hive", "thread-1")).thenReturn(session);
            org.mockito.Mockito.doNothing().when(sessionPort).save(any(AgentSession.class));

            Gate gateA = new Gate();
            CountDownLatch secondProcessed = new CountDownLatch(1);
            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("Inspect run-1".equals(inbound.getContent())) {
                    gateA.await();
                    return null;
                }
                if ("Inspect run-2".equals(inbound.getContent())) {
                    secondProcessed.countDown();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            Message firstInbound = hiveMessage("Inspect run-1", "cmd-1", "run-1");
            Message secondInbound = hiveMessage("Inspect run-2", "cmd-2", "run-2");

            coordinator.enqueue(firstInbound);
            gateA.awaitStarted();
            coordinator.enqueue(secondInbound);

            coordinator.requestStop("hive", "thread-1", "run-1", "cmd-1");
            gateA.release();

            assertTrue(secondProcessed.await(2, TimeUnit.SECONDS), "Queued hive command should continue automatically");
            verify(agentLoop, times(1)).processMessage(firstInbound);
            verify(agentLoop, times(1)).processMessage(secondInbound);
        }
    }

    @Test
    void shouldSuppressFallbackPublishFailureWhenHiveSessionIsGone() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = new RuntimeEventService(
                Clock.fixed(Instant.parse("2026-03-18T00:00:00Z"), ZoneOffset.UTC));
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");
        HiveEventPublishPort hiveEventPublishPort = mock(HiveEventPublishPort.class);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService, null, hiveEventPublishPort);

            AgentSession session = AgentSession.builder()
                    .id("hive:thread-1")
                    .channelType("hive")
                    .chatId("thread-1")
                    .messages(new ArrayList<>())
                    .metadata(new LinkedHashMap<>())
                    .build();
            when(sessionPort.getOrCreate("hive", "thread-1")).thenReturn(session);
            org.mockito.Mockito.doNothing().when(sessionPort).save(any(AgentSession.class));

            CountDownLatch started = new CountDownLatch(1);
            org.mockito.Mockito.doAnswer(invocation -> {
                started.countDown();
                CountDownLatch blocked = new CountDownLatch(1);
                blocked.await();
                return null;
            }).when(agentLoop).processMessage(any(Message.class));
            doThrow(new IllegalStateException("Hive session is not available"))
                    .when(hiveEventPublishPort)
                    .publishRuntimeEvents(any(List.class), any(Map.class));

            CompletableFuture<Void> completion = coordinator.submit(hiveMessage("Inspect the workspace"));
            assertTrue(started.await(1, TimeUnit.SECONDS), "Hive run should have started");

            coordinator.requestStop("hive", "thread-1", "run-1", "cmd-1");

            ExecutionException failure = assertThrows(ExecutionException.class, () -> completion.get(2,
                    TimeUnit.SECONDS));
            assertFalse("Hive session is not available".equals(failure.getCause().getMessage()));
            verify(hiveEventPublishPort, timeout(1000)).publishRuntimeEvents(any(List.class), any(Map.class));
        }
    }

    @Test
    void shouldProcessQueuedMessageAfterRunCompletes() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            Message a = user("A");
            Message b = user("B");

            Gate gateA = new Gate();
            CountDownLatch bProcessed = new CountDownLatch(1);

            org.mockito.Mockito.doAnswer(inv -> {
                gateA.await();
                return null;
            }).when(agentLoop).processMessage(a);

            org.mockito.Mockito.doAnswer(inv -> {
                bProcessed.countDown();
                return null;
            }).when(agentLoop).processMessage(b);

            // Start first run
            coordinator.enqueue(a);
            gateA.awaitStarted();

            // Queue second message while first is running
            coordinator.enqueue(b);

            // Release first run — onRunComplete should pick up B
            gateA.release();

            // Wait for B to be processed before shutting down
            assertTrue(bProcessed.await(2, TimeUnit.SECONDS), "B should have been processed");

            verify(agentLoop, times(1)).processMessage(a);
            verify(agentLoop, times(1)).processMessage(b);
        }
    }

    @Test
    void shouldCompleteSubmittedRunAfterQueuedSessionWorkFinishes() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            Message a = user("A");
            Message auto = auto("AUTO");

            Gate gateA = new Gate();
            CountDownLatch autoProcessed = new CountDownLatch(1);

            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("A".equals(inbound.getContent())) {
                    gateA.await();
                }
                if ("AUTO".equals(inbound.getContent())) {
                    autoProcessed.countDown();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            coordinator.enqueue(a);
            gateA.awaitStarted();

            CompletableFuture<Void> completion = coordinator.submit(auto);
            assertFalse(completion.isDone());

            gateA.release();

            completion.get(2, TimeUnit.SECONDS);
            assertTrue(autoProcessed.await(2, TimeUnit.SECONDS));

            verify(agentLoop, times(1)).processMessage(a);
            verify(agentLoop, times(1)).processMessage(auto);
        }
    }

    @Test
    void shouldCompleteSubmitForContextWithProcessedAgentContext() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);
            Message inbound = internalRetry("retry");
            AgentContext processed = AgentContext.builder().build();
            processed.setAttribute(ContextAttributes.RESILIENCE_L5_TERMINAL_FAILURE, true);
            when(agentLoop.processMessage(inbound)).thenReturn(processed);

            CompletableFuture<AgentContext> completion = coordinator.submitForContext(inbound);

            assertEquals(processed, completion.get(2, TimeUnit.SECONDS));
            verify(agentLoop).processMessage(inbound);
        }
    }

    @Test
    void shouldPrioritizeSteeringMessageBeforeFollowUp() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "all", "all");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            Message a = user("A");
            Message followUp = user("F1");
            Message steering = steering("S1");

            Gate gateA = new Gate();
            List<String> processedOrder = new ArrayList<>();
            CountDownLatch queuedProcessed = new CountDownLatch(2);

            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("A".equals(inbound.getContent())) {
                    gateA.await();
                }
                synchronized (processedOrder) {
                    processedOrder.add(inbound.getContent());
                }
                if (!"A".equals(inbound.getContent())) {
                    queuedProcessed.countDown();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            coordinator.enqueue(a);
            gateA.awaitStarted();
            coordinator.enqueue(followUp);
            coordinator.enqueue(steering);
            gateA.release();

            assertTrue(queuedProcessed.await(2, TimeUnit.SECONDS));
            List<String> orderSnapshot;
            synchronized (processedOrder) {
                orderSnapshot = new ArrayList<>(processedOrder);
            }
            assertTrue(orderSnapshot.indexOf("S1") < orderSnapshot.indexOf("F1"));
        }
    }

    @Test
    void shouldMergeQueuedFollowUpsWhenModeAll() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "all");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            Message a = user("A");
            Message b = user("B");
            Message c = user("C");

            Gate gateA = new Gate();
            List<Message> nonInitialProcessed = new ArrayList<>();
            CountDownLatch mergedProcessed = new CountDownLatch(1);

            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("A".equals(inbound.getContent())) {
                    gateA.await();
                } else {
                    synchronized (nonInitialProcessed) {
                        nonInitialProcessed.add(inbound);
                    }
                    mergedProcessed.countDown();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            coordinator.enqueue(a);
            gateA.awaitStarted();
            coordinator.enqueue(b);
            coordinator.enqueue(c);
            gateA.release();

            assertTrue(mergedProcessed.await(2, TimeUnit.SECONDS));
            List<Message> processedSnapshot;
            synchronized (nonInitialProcessed) {
                processedSnapshot = new ArrayList<>(nonInitialProcessed);
            }
            assertEquals(1, processedSnapshot.size());
            Message merged = processedSnapshot.get(0);
            assertEquals("B\n\nC", merged.getContent());
            assertEquals(ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP,
                    merged.getMetadata().get(ContextAttributes.TURN_QUEUE_KIND));
        }
    }

    @Test
    void shouldKeepOnlyLatestSteeringMessageWhenModeOneAtATime() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            Message a = user("A");
            Message s1 = steering("S1");
            Message s2 = steering("S2");

            Gate gateA = new Gate();
            List<Message> processedAfterA = new ArrayList<>();
            CountDownLatch secondRunProcessed = new CountDownLatch(1);

            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("A".equals(inbound.getContent())) {
                    gateA.await();
                } else {
                    synchronized (processedAfterA) {
                        processedAfterA.add(inbound);
                    }
                    secondRunProcessed.countDown();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            coordinator.enqueue(a);
            gateA.awaitStarted();
            coordinator.enqueue(s1);
            coordinator.enqueue(s2);
            gateA.release();

            assertTrue(secondRunProcessed.await(2, TimeUnit.SECONDS));
            assertTrue(waitForRunnerCount(coordinator, 0, 2, TimeUnit.SECONDS));

            List<Message> processedSnapshot;
            synchronized (processedAfterA) {
                processedSnapshot = new ArrayList<>(processedAfterA);
            }
            assertEquals(1, processedSnapshot.size());
            Message onlyQueued = processedSnapshot.get(0);
            assertEquals("S2", onlyQueued.getContent());
            assertEquals(ContextAttributes.TURN_QUEUE_KIND_STEERING,
                    onlyQueued.getMetadata().get(ContextAttributes.TURN_QUEUE_KIND));
        }
    }

    @Test
    void shouldProcessMixedBurstWithSteeringPriorityWhenQueueModesAll() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "all", "all");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            Message a = user("A");
            Message f1 = user("F1");
            Message s1 = steering("S1");
            Message f2 = user("F2");
            Message s2 = steering("S2");
            Message f3 = user("F3");

            Gate gateA = new Gate();
            List<String> processedOrder = new ArrayList<>();
            CountDownLatch queuedProcessed = new CountDownLatch(2);

            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("A".equals(inbound.getContent())) {
                    gateA.await();
                }
                synchronized (processedOrder) {
                    processedOrder.add(inbound.getContent());
                }
                if (!"A".equals(inbound.getContent())) {
                    queuedProcessed.countDown();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            coordinator.enqueue(a);
            gateA.awaitStarted();

            coordinator.enqueue(f1);
            coordinator.enqueue(s1);
            coordinator.enqueue(f2);
            coordinator.enqueue(s2);
            coordinator.enqueue(f3);

            gateA.release();

            assertTrue(queuedProcessed.await(2, TimeUnit.SECONDS));
            assertTrue(waitForRunnerCount(coordinator, 0, 2, TimeUnit.SECONDS));

            List<String> orderSnapshot;
            synchronized (processedOrder) {
                orderSnapshot = new ArrayList<>(processedOrder);
            }

            assertEquals(List.of("A", "S1\n\nS2", "F1\n\nF2\n\nF3"), orderSnapshot);
        }
    }

    @Test
    void shouldKeepLatestPerQueueKindInBurstWhenModesOneAtATime() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            Message a = user("A");
            Message f1 = user("F1");
            Message s1 = steering("S1");
            Message f2 = user("F2");
            Message s2 = steering("S2");
            Message f3 = user("F3");

            Gate gateA = new Gate();
            List<String> processedOrder = new ArrayList<>();
            CountDownLatch queuedProcessed = new CountDownLatch(2);

            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("A".equals(inbound.getContent())) {
                    gateA.await();
                }
                synchronized (processedOrder) {
                    processedOrder.add(inbound.getContent());
                }
                if (!"A".equals(inbound.getContent())) {
                    queuedProcessed.countDown();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            coordinator.enqueue(a);
            gateA.awaitStarted();

            coordinator.enqueue(f1);
            coordinator.enqueue(s1);
            coordinator.enqueue(f2);
            coordinator.enqueue(s2);
            coordinator.enqueue(f3);

            gateA.release();

            assertTrue(queuedProcessed.await(2, TimeUnit.SECONDS));
            assertTrue(waitForRunnerCount(coordinator, 0, 2, TimeUnit.SECONDS));

            List<String> orderSnapshot;
            synchronized (processedOrder) {
                orderSnapshot = new ArrayList<>(processedOrder);
            }

            assertEquals(List.of("A", "S2", "F3"), orderSnapshot);
        }
    }

    @Test
    void shouldFlushMixedQueuedMessagesChronologicallyAfterStop() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "all", "all");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            AgentSession session = AgentSession.builder()
                    .id("s-burst-stop")
                    .channelType(CHANNEL_TYPE)
                    .chatId(CHAT_ID)
                    .messages(new ArrayList<>())
                    .build();
            when(sessionPort.getOrCreate(CHANNEL_TYPE, CHAT_ID)).thenReturn(session);

            Message a = userAt("A", Instant.parse("2026-01-01T00:00:00Z"));
            Message f1 = userAt("F1", Instant.parse("2026-01-01T00:00:01Z"));
            Message s1 = steeringAt("S1", Instant.parse("2026-01-01T00:00:02Z"));
            Message f2 = userAt("F2", Instant.parse("2026-01-01T00:00:03Z"));
            Message s2 = steeringAt("S2", Instant.parse("2026-01-01T00:00:04Z"));
            Message resume = userAt("RESUME", Instant.parse("2026-01-01T00:00:10Z"));

            Gate gateA = new Gate();
            List<String> processedOrder = new ArrayList<>();
            CountDownLatch resumeProcessed = new CountDownLatch(1);

            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("A".equals(inbound.getContent())) {
                    gateA.await();
                }
                synchronized (processedOrder) {
                    processedOrder.add(inbound.getContent());
                }
                if ("RESUME".equals(inbound.getContent())) {
                    resumeProcessed.countDown();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            coordinator.enqueue(a);
            gateA.awaitStarted();

            coordinator.enqueue(f1);
            coordinator.enqueue(s1);
            coordinator.enqueue(f2);
            coordinator.enqueue(s2);

            coordinator.requestStop(CHANNEL_TYPE, CHAT_ID);
            coordinator.enqueue(resume);
            gateA.release();

            assertTrue(resumeProcessed.await(2, TimeUnit.SECONDS));
            assertTrue(waitForRunnerCount(coordinator, 0, 2, TimeUnit.SECONDS));

            List<String> contents = session.getMessages().stream().map(Message::getContent).toList();
            assertEquals(List.of("F1", "S1", "F2", "S2"), contents);

            List<String> queueKinds = session.getMessages().stream()
                    .map(message -> (String) message.getMetadata().get(ContextAttributes.TURN_QUEUE_KIND))
                    .toList();
            assertEquals(List.of(
                    ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP,
                    ContextAttributes.TURN_QUEUE_KIND_STEERING,
                    ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP,
                    ContextAttributes.TURN_QUEUE_KIND_STEERING), queueKinds);

            List<String> processedSnapshot;
            synchronized (processedOrder) {
                processedSnapshot = new ArrayList<>(processedOrder);
            }
            boolean onlyResumeProcessed = List.of("RESUME").equals(processedSnapshot);
            boolean initialAndResumeProcessed = List.of("A", "RESUME").equals(processedSnapshot);
            assertTrue(onlyResumeProcessed || initialAndResumeProcessed);
        }
    }

    @Test
    void shouldHandleExceptionInProcessMessage() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            Message a = user("A");
            doThrow(new RuntimeException("boom"))
                    .when(agentLoop).processMessage(a);

            coordinator.enqueue(a);

            executor.shutdown();
            executor.awaitTermination(2, TimeUnit.SECONDS);

            // Should not propagate — executor thread stays alive
            verify(agentLoop, times(1)).processMessage(a);
        }
    }

    @Test
    void shouldFailSubmittedRunWhenFlushedAfterStop() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            AgentSession session = AgentSession.builder()
                    .id("s-submit-stop")
                    .channelType(CHANNEL_TYPE)
                    .chatId(CHAT_ID)
                    .messages(new ArrayList<>())
                    .build();
            when(sessionPort.getOrCreate(CHANNEL_TYPE, CHAT_ID)).thenReturn(session);

            Message a = user("A");
            Message auto = auto("AUTO");
            Message resume = user("RESUME");

            Gate gateA = new Gate();
            CountDownLatch resumeProcessed = new CountDownLatch(1);

            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("A".equals(inbound.getContent())) {
                    gateA.await();
                }
                if ("RESUME".equals(inbound.getContent())) {
                    resumeProcessed.countDown();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            coordinator.enqueue(a);
            gateA.awaitStarted();

            CompletableFuture<Void> completion = coordinator.submit(auto);

            coordinator.requestStop(CHANNEL_TYPE, CHAT_ID);
            coordinator.enqueue(resume);
            gateA.release();

            assertTrue(resumeProcessed.await(2, TimeUnit.SECONDS));

            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> completion.get(2, TimeUnit.SECONDS));
            assertTrue(exception.getCause() instanceof IllegalStateException);
            assertEquals("Skipped after stop request", exception.getCause().getMessage());
        }
    }

    @Test
    void shouldEvictRunnerWhenSessionBecomesIdle() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            CountDownLatch processed = new CountDownLatch(1);
            Message a = user("A");
            org.mockito.Mockito.doAnswer(inv -> {
                processed.countDown();
                return null;
            }).when(agentLoop).processMessage(a);

            coordinator.enqueue(a);
            assertTrue(processed.await(2, TimeUnit.SECONDS));
            assertTrue(waitForRunnerCount(coordinator, 0, 2, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldDropOldestQueuedMessagesWhenQueueLimitReached() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "all");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            AgentSession session = AgentSession.builder()
                    .id("s-limit")
                    .channelType(CHANNEL_TYPE)
                    .chatId(CHAT_ID)
                    .messages(new ArrayList<>())
                    .build();
            when(sessionPort.getOrCreate(CHANNEL_TYPE, CHAT_ID)).thenReturn(session);

            Message a = user("A");
            Message resume = user("RESUME");

            Gate gateA = new Gate();
            org.mockito.Mockito.doAnswer(inv -> {
                gateA.await();
                return null;
            }).when(agentLoop).processMessage(a);

            coordinator.enqueue(a);
            gateA.awaitStarted();

            for (int i = 1; i <= 105; i++) {
                coordinator.enqueue(user("M" + i));
            }

            coordinator.requestStop(CHANNEL_TYPE, CHAT_ID);
            coordinator.enqueue(resume);
            gateA.release();

            executor.shutdown();
            executor.awaitTermination(3, TimeUnit.SECONDS);

            List<String> contents = session.getMessages().stream().map(Message::getContent).toList();
            assertEquals(100, contents.size());
            assertEquals("M6", contents.get(0));
            assertEquals("M105", contents.get(contents.size() - 1));
            verify(agentLoop, times(1)).processMessage(resume);
        }
    }

    @Test
    void shouldKeepOnlyLatestInternalRetryMessageAcrossQueuedFollowUps() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(false, "one-at-a-time", "all");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            Message a = user("A");
            Message retryOne = internalRetry("retry-1");
            Message followUp = user("follow-up");
            Message retryTwo = internalRetry("retry-2");

            Gate gateA = new Gate();
            List<String> processedAfterA = new ArrayList<>();
            CountDownLatch queuedProcessed = new CountDownLatch(2);

            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("A".equals(inbound.getContent())) {
                    gateA.await();
                } else {
                    synchronized (processedAfterA) {
                        processedAfterA.add(inbound.getContent());
                    }
                    queuedProcessed.countDown();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            coordinator.enqueue(a);
            gateA.awaitStarted();
            coordinator.enqueue(retryOne);
            coordinator.enqueue(followUp);
            coordinator.enqueue(retryTwo);
            gateA.release();

            assertTrue(queuedProcessed.await(2, TimeUnit.SECONDS));
            assertTrue(waitForRunnerCount(coordinator, 0, 2, TimeUnit.SECONDS));

            List<String> processedSnapshot;
            synchronized (processedAfterA) {
                processedSnapshot = new ArrayList<>(processedAfterA);
            }
            assertEquals(List.of("retry-2", "follow-up"), processedSnapshot);
        }
    }

    @Test
    void shouldKeepOnlyLatestInternalRetryMessage() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(false, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = newCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            Message a = user("A");
            Message retryOne = internalRetry("retry-1");
            Message retryTwo = internalRetry("retry-2");

            Gate gateA = new Gate();
            List<String> processedAfterA = new ArrayList<>();
            CountDownLatch secondRunProcessed = new CountDownLatch(1);

            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("A".equals(inbound.getContent())) {
                    gateA.await();
                } else {
                    synchronized (processedAfterA) {
                        processedAfterA.add(inbound.getContent());
                    }
                    secondRunProcessed.countDown();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            coordinator.enqueue(a);
            gateA.awaitStarted();
            coordinator.enqueue(retryOne);
            coordinator.enqueue(retryTwo);
            gateA.release();

            assertTrue(secondRunProcessed.await(2, TimeUnit.SECONDS));
            assertTrue(waitForRunnerCount(coordinator, 0, 2, TimeUnit.SECONDS));

            List<String> processedSnapshot;
            synchronized (processedAfterA) {
                processedSnapshot = new ArrayList<>(processedAfterA);
            }
            assertEquals(List.of("retry-2"), processedSnapshot);
        }
    }

    @Test
    void shouldCancelQueuedInternalContinuationWhenRealUserFollowUpArrives() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(false, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService, null, mock(HiveEventPublishPort.class));

            Message a = user("A");
            Message followUp = user("follow-up");
            Message continuation = internalContinuation("continue-old-plan", 1L);

            Gate gateA = new Gate();
            List<String> processedAfterA = new ArrayList<>();
            CountDownLatch queuedProcessed = new CountDownLatch(1);

            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("A".equals(inbound.getContent())) {
                    gateA.await();
                } else {
                    synchronized (processedAfterA) {
                        processedAfterA.add(inbound.getContent());
                    }
                    queuedProcessed.countDown();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            coordinator.enqueue(a);
            gateA.awaitStarted();

            Number baseline = (Number) a.getMetadata().get(ContextAttributes.MESSAGE_REAL_USER_ACTIVITY_SEQUENCE);
            assertEquals(1L, baseline.longValue());
            coordinator.enqueue(continuation);
            assertEquals(1L, ((Number) continuation.getMetadata()
                    .get(ContextAttributes.MESSAGE_REAL_USER_ACTIVITY_SEQUENCE)).longValue());

            coordinator.enqueue(followUp);
            gateA.release();

            assertTrue(queuedProcessed.await(2, TimeUnit.SECONDS));
            assertTrue(waitForRunnerCount(coordinator, 0, 2, TimeUnit.SECONDS));

            List<String> processedSnapshot;
            synchronized (processedAfterA) {
                processedSnapshot = new ArrayList<>(processedAfterA);
            }
            assertEquals(List.of("follow-up"), processedSnapshot);
        }
    }

    @Test
    void shouldSkipInternalContinuationWhenNewerRealUserActivityAlreadyQueued() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(false, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService, null, mock(HiveEventPublishPort.class));

            Message a = user("A");
            Message followUp = user("follow-up");
            Message continuation = autoProceedContinuation("continue-old-plan", 1L);

            Gate gateA = new Gate();
            List<String> processedAfterA = new ArrayList<>();
            CountDownLatch queuedProcessed = new CountDownLatch(1);

            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("A".equals(inbound.getContent())) {
                    gateA.await();
                } else {
                    synchronized (processedAfterA) {
                        processedAfterA.add(inbound.getContent());
                    }
                    queuedProcessed.countDown();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            coordinator.enqueue(a);
            gateA.awaitStarted();
            Number baseline = (Number) a.getMetadata().get(ContextAttributes.MESSAGE_REAL_USER_ACTIVITY_SEQUENCE);
            assertEquals(1L, baseline.longValue());

            coordinator.enqueue(followUp);
            coordinator.enqueue(continuation);

            gateA.release();

            assertTrue(queuedProcessed.await(2, TimeUnit.SECONDS));
            assertTrue(waitForRunnerCount(coordinator, 0, 2, TimeUnit.SECONDS));

            List<String> processedSnapshot;
            synchronized (processedAfterA) {
                processedSnapshot = new ArrayList<>(processedAfterA);
            }
            assertEquals(List.of("follow-up"), processedSnapshot);
        }
    }

    @Test
    void shouldPreserveInternalRetryWhenLatestFollowUpReplacesOlderFollowUp() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(false, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService, null, mock(HiveEventPublishPort.class));

            Message a = user("A");
            Message retry = internalRetry("retry");
            Message followUpOne = user("follow-up-1");
            Message followUpTwo = user("follow-up-2");

            Gate gateA = new Gate();
            List<String> processedAfterA = new ArrayList<>();
            CountDownLatch queuedProcessed = new CountDownLatch(2);

            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("A".equals(inbound.getContent())) {
                    gateA.await();
                } else {
                    synchronized (processedAfterA) {
                        processedAfterA.add(inbound.getContent());
                    }
                    queuedProcessed.countDown();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            coordinator.enqueue(a);
            gateA.awaitStarted();
            coordinator.enqueue(retry);
            coordinator.enqueue(followUpOne);
            coordinator.enqueue(followUpTwo);
            gateA.release();

            assertTrue(queuedProcessed.await(2, TimeUnit.SECONDS));
            assertTrue(waitForRunnerCount(coordinator, 0, 2, TimeUnit.SECONDS));

            List<String> processedSnapshot;
            synchronized (processedAfterA) {
                processedSnapshot = new ArrayList<>(processedAfterA);
            }
            assertEquals(List.of("retry", "follow-up-2"), processedSnapshot);
        }
    }

    @Test
    void shouldPreserveInternalRetryWhenFollowUpQueueLimitIsReached() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(false, "one-at-a-time", "all");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService, null, mock(HiveEventPublishPort.class));

            Message a = user("A");
            Message retry = internalRetry("retry");

            Gate gateA = new Gate();
            List<String> processedAfterA = new ArrayList<>();
            CountDownLatch queuedProcessed = new CountDownLatch(2);

            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("A".equals(inbound.getContent())) {
                    gateA.await();
                } else {
                    synchronized (processedAfterA) {
                        processedAfterA.add(inbound.getContent());
                    }
                    queuedProcessed.countDown();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            coordinator.enqueue(a);
            gateA.awaitStarted();
            coordinator.enqueue(retry);
            for (int i = 1; i <= 101; i++) {
                coordinator.enqueue(user("follow-up-" + i));
            }
            gateA.release();

            assertTrue(queuedProcessed.await(2, TimeUnit.SECONDS));
            assertTrue(waitForRunnerCount(coordinator, 0, 2, TimeUnit.SECONDS));

            List<String> processedSnapshot;
            synchronized (processedAfterA) {
                processedSnapshot = new ArrayList<>(processedAfterA);
            }
            assertEquals(2, processedSnapshot.size());
            assertEquals("retry", processedSnapshot.get(0));
            List<String> mergedFollowUps = List.of(processedSnapshot.get(1).split("\\n\\n"));
            assertFalse(mergedFollowUps.contains("follow-up-1"));
            assertFalse(mergedFollowUps.contains("follow-up-2"));
            assertEquals("follow-up-3", mergedFollowUps.get(0));
            assertTrue(mergedFollowUps.contains("follow-up-50"));
            assertEquals("follow-up-101", mergedFollowUps.get(mergedFollowUps.size() - 1));
        }
    }

    private static RuntimeConfigService runtimeConfigService(boolean steeringEnabled, String steeringMode,
            String followUpMode) {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isTurnQueueSteeringEnabled()).thenReturn(steeringEnabled);
        when(runtimeConfigService.getTurnQueueSteeringMode()).thenReturn(steeringMode);
        when(runtimeConfigService.getTurnQueueFollowUpMode()).thenReturn(followUpMode);
        return runtimeConfigService;
    }

    private static SessionRunCoordinator newCoordinator(
            SessionPort sessionPort,
            AgentLoop agentLoop,
            ExecutorService executor,
            RuntimeEventService runtimeEventService,
            RuntimeConfigService runtimeConfigService) {
        return new SessionRunCoordinator(sessionPort, agentLoop, executor, runtimeEventService,
                runtimeConfigService, null, mock(HiveEventPublishPort.class));
    }

    private static Message steering(String content) {
        return steeringAt(content, Instant.EPOCH);
    }

    private static Message steeringAt(String content, Instant timestamp) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.TURN_QUEUE_KIND, ContextAttributes.TURN_QUEUE_KIND_STEERING);
        return user(content, metadata, timestamp);
    }

    private static Message user(String content) {
        return userAt(content, Instant.EPOCH);
    }

    private static Message userAt(String content, Instant timestamp) {
        return user(content, null, timestamp);
    }

    private static Message auto(String content) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.AUTO_MODE, true);
        metadata.put(ContextAttributes.AUTO_RUN_ID, "run-" + content);
        return Message.builder()
                .role("user")
                .content(content)
                .channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID)
                .senderId("auto")
                .timestamp(Instant.EPOCH)
                .metadata(metadata)
                .build();
    }

    private static Message internalRetry(String content) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.MESSAGE_INTERNAL, true);
        metadata.put(ContextAttributes.MESSAGE_INTERNAL_KIND, ContextAttributes.MESSAGE_INTERNAL_KIND_AUTO_CONTINUE);
        metadata.put(ContextAttributes.TURN_QUEUE_KIND, ContextAttributes.TURN_QUEUE_KIND_INTERNAL_MODEL_RETRY);
        return Message.builder()
                .role("user")
                .content(content)
                .channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID)
                .senderId("internal")
                .timestamp(Instant.EPOCH)
                .metadata(metadata)
                .build();
    }

    private static Message internalContinuation(String content, long baselineRealUserActivitySequence) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.MESSAGE_INTERNAL, true);
        metadata.put(ContextAttributes.MESSAGE_INTERNAL_KIND,
                ContextAttributes.MESSAGE_INTERNAL_KIND_FOLLOW_THROUGH_NUDGE);
        metadata.put(ContextAttributes.TURN_QUEUE_KIND, ContextAttributes.TURN_QUEUE_KIND_INTERNAL_FOLLOW_THROUGH);
        metadata.put(ContextAttributes.MESSAGE_REAL_USER_ACTIVITY_SEQUENCE, baselineRealUserActivitySequence);
        return Message.builder()
                .role("user")
                .content(content)
                .channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID)
                .senderId("internal")
                .timestamp(Instant.EPOCH)
                .metadata(metadata)
                .build();
    }

    private static Message autoProceedContinuation(String content, long baselineRealUserActivitySequence) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.MESSAGE_INTERNAL, true);
        metadata.put(ContextAttributes.MESSAGE_INTERNAL_KIND,
                ContextAttributes.MESSAGE_INTERNAL_KIND_AUTO_PROCEED);
        metadata.put(ContextAttributes.TURN_QUEUE_KIND, ContextAttributes.TURN_QUEUE_KIND_INTERNAL_AUTO_PROCEED);
        metadata.put(ContextAttributes.MESSAGE_REAL_USER_ACTIVITY_SEQUENCE, baselineRealUserActivitySequence);
        return Message.builder()
                .role("user")
                .content(content)
                .channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID)
                .senderId("internal")
                .timestamp(Instant.EPOCH)
                .metadata(metadata)
                .build();
    }

    private static Message hiveMessage(String content) {
        return hiveMessage(content, "cmd-1", "run-1");
    }

    private static Message hiveMessage(String content, String commandId, String runId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.HIVE_THREAD_ID, "thread-1");
        metadata.put(ContextAttributes.HIVE_CARD_ID, "card-1");
        metadata.put(ContextAttributes.HIVE_COMMAND_ID, commandId);
        metadata.put(ContextAttributes.HIVE_RUN_ID, runId);
        metadata.put(ContextAttributes.HIVE_GOLEM_ID, "golem-1");
        return Message.builder()
                .role("user")
                .content(content)
                .channelType("hive")
                .chatId("thread-1")
                .senderId("hive")
                .timestamp(Instant.EPOCH)
                .metadata(metadata)
                .build();
    }

    private static Message user(String content, Map<String, Object> metadata, Instant timestamp) {
        return Message.builder()
                .role("user")
                .content(content)
                .channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID)
                .senderId("u1")
                .timestamp(timestamp)
                .metadata(metadata)
                .build();
    }

    private static boolean waitForRunnerCount(SessionRunCoordinator coordinator, int expected, long timeout,
            TimeUnit unit) throws InterruptedException {
        long timeoutNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + timeoutNanos;
        while (System.nanoTime() < deadline) {
            if (coordinator.runnerCount() == expected) {
                return true;
            }
            Thread.sleep(10);
        }
        return coordinator.runnerCount() == expected;
    }

    private static boolean isUserInterruptFinishEvent(List<RuntimeEvent> events) {
        if (events == null || events.size() != 1) {
            return false;
        }
        RuntimeEvent event = events.get(0);
        return event != null
                && event.type() == RuntimeEventType.TURN_FINISHED
                && event.payload() != null
                && "user_interrupt".equals(event.payload().get("reason"));
    }

    private static final class Gate {
        private final Object lock = new Object();
        private boolean released = false;
        private boolean started = false;

        void await() throws InterruptedException {
            synchronized (lock) {
                started = true;
                lock.notifyAll();
                while (!released) {
                    lock.wait();
                }
            }
        }

        void awaitStarted() throws InterruptedException {
            synchronized (lock) {
                while (!started) {
                    lock.wait();
                }
            }
        }

        void release() {
            synchronized (lock) {
                released = true;
                lock.notifyAll();
            }
        }
    }
}
