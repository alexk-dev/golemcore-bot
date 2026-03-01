package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
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

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService);

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
            assertEquals(List.of("B", "C"), contents);

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

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService);
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

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService);

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
    void shouldHandleExceptionInProcessMessage() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService);

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

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService);

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

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService);

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

    private static Message user(String content) {
        return Message.builder()
                .role("user")
                .content(content)
                .channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID)
                .senderId("u1")
                .timestamp(Instant.EPOCH)
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
