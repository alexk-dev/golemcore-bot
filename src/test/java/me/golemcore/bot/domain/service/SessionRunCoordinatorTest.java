package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionRunCoordinatorTest {

    private static final String CHANNEL_TYPE = "telegram";
    private static final String CHAT_ID = "1";

    @Test
    void shouldPauseAfterStopAndResumeOnNextInboundFlushingQueuedMessages() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
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
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
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
    void shouldProcessQueuedMessageAfterRunCompletes() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
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
    void shouldPrioritizeSteeringMessageBeforeFollowUp() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "all", "all");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
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
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
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
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
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
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
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
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
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
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
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
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService);

            Message a = user("A");
            org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                    .when(agentLoop).processMessage(a);

            coordinator.enqueue(a);

            executor.shutdown();
            executor.awaitTermination(2, TimeUnit.SECONDS);

            // Should not propagate — executor thread stays alive
            verify(agentLoop, times(1)).processMessage(a);
        }
    }

    @Test
    void shouldEvictRunnerWhenSessionBecomesIdle() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService(true, "one-at-a-time", "one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
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
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
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

    private static RuntimeConfigService runtimeConfigService(boolean steeringEnabled, String steeringMode,
            String followUpMode) {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isTurnQueueSteeringEnabled()).thenReturn(steeringEnabled);
        when(runtimeConfigService.getTurnQueueSteeringMode()).thenReturn(steeringMode);
        when(runtimeConfigService.getTurnQueueFollowUpMode()).thenReturn(followUpMode);
        return runtimeConfigService;
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

    private static Message user(String content, Map<String, Object> metadata) {
        return user(content, metadata, Instant.EPOCH);
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

    private static int getRunnerCount(SessionRunCoordinator coordinator) throws Exception {
        Field runnersField = SessionRunCoordinator.class.getDeclaredField("runners");
        runnersField.setAccessible(true);
        Map<?, ?> runners = (Map<?, ?>) runnersField.get(coordinator);
        return runners.size();
    }

    private static boolean waitForRunnerCount(SessionRunCoordinator coordinator, int expected, long timeout,
            TimeUnit unit) throws Exception {
        long timeoutNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + timeoutNanos;
        while (System.nanoTime() < deadline) {
            if (getRunnerCount(coordinator) == expected) {
                return true;
            }
            Thread.sleep(10);
        }
        return getRunnerCount(coordinator) == expected;
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
