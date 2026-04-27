package me.golemcore.bot.domain.session;

import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.scheduling.DelayedSessionActionService;
import me.golemcore.bot.domain.events.RuntimeEventService;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionRunCoordinatorDelayedActionsTest {

    private static final String CHANNEL_TYPE = "telegram";
    private static final String CHAT_ID = "conv-1";

    @Test
    void shouldCancelDelayedActionsForRegularUserInboundOnly() {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService, delayedActionService, null);

            Message regular = Message.builder()
                    .role("user")
                    .content("hello")
                    .channelType("telegram")
                    .chatId("conv-1")
                    .build();
            coordinator.enqueue(regular);
            verify(delayedActionService).cancelOnUserActivity(regular);

            Message internal = Message.builder()
                    .role("user")
                    .content("internal")
                    .channelType("telegram")
                    .chatId("conv-1")
                    .metadata(Map.of(ContextAttributes.MESSAGE_INTERNAL, true))
                    .build();
            coordinator.enqueue(internal);
            verify(delayedActionService, never()).cancelOnUserActivity(internal);

            Message auto = Message.builder()
                    .role("user")
                    .content("auto")
                    .channelType("telegram")
                    .chatId("conv-1")
                    .metadata(new LinkedHashMap<>(Map.of(ContextAttributes.AUTO_MODE, true)))
                    .build();
            coordinator.enqueue(auto);
            verify(delayedActionService, never()).cancelOnUserActivity(auto);
        }
    }

    @Test
    void shouldClearDelayedActionsWhenStopIsRequested() {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService, delayedActionService, null);

            coordinator.requestStop(CHANNEL_TYPE, CHAT_ID);

            verify(delayedActionService).clearActiveActions(CHANNEL_TYPE, CHAT_ID);
        }
    }

    @Test
    void shouldContinueProcessingInboundWhenDelayedCancellationFails() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService("one-at-a-time");
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);
        doThrow(new IllegalStateException("boom")).when(delayedActionService).cancelOnUserActivity(any(Message.class));

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService, delayedActionService, null);

            CountDownLatch processed = new CountDownLatch(1);
            org.mockito.Mockito.doAnswer(invocation -> {
                processed.countDown();
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            Message regular = user("hello");
            coordinator.enqueue(regular);

            assertTrue(processed.await(2, TimeUnit.SECONDS));
            verify(agentLoop).processMessage(regular);
            verify(delayedActionService).cancelOnUserActivity(regular);
        }
    }

    @Test
    void shouldDropInternalInboundArrivingWhilePausedAfterStopWithoutResuming() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService("one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService, null, null);

            Gate gate = new Gate();
            CountDownLatch resumeProcessed = new CountDownLatch(1);

            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("A".equals(inbound.getContent())) {
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } else if ("RESUME".equals(inbound.getContent())) {
                    resumeProcessed.countDown();
                }
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            CompletableFuture<Void> initialRun = coordinator.submit(user("A"));
            gate.awaitStarted();

            coordinator.requestStop(CHANNEL_TYPE, CHAT_ID);
            gate.release();
            try {
                initialRun.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) { // NOSONAR - run may complete exceptionally
                // initial run may complete exceptionally due to cancellation; that is fine for
                // this test
            }

            Message internalRetry = internalRetry();
            coordinator.enqueue(internalRetry);

            coordinator.enqueue(user("RESUME"));
            assertTrue(resumeProcessed.await(2, TimeUnit.SECONDS),
                    "A regular user message must still be able to resume after /stop");

            verify(agentLoop, never()).processMessage(internalRetry);
        }
    }

    @Test
    void shouldKeepDelayedWakeUpSeparateFromUserFollowUpsWhenModeOneAtATime() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService("one-at-a-time");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService, null, null);

            Message initial = user("A");
            Message followUpOne = user("F1");
            Message delayed = delayed("D1");
            Message followUpTwo = user("F2");

            Gate gate = new Gate();
            List<String> processedAfterInitial = new ArrayList<>();
            CountDownLatch queuedProcessed = new CountDownLatch(2);

            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("A".equals(inbound.getContent())) {
                    gate.await();
                    return null;
                }
                synchronized (processedAfterInitial) {
                    processedAfterInitial.add(inbound.getContent());
                }
                queuedProcessed.countDown();
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            coordinator.enqueue(initial);
            gate.awaitStarted();
            coordinator.enqueue(followUpOne);
            coordinator.enqueue(delayed);
            coordinator.enqueue(followUpTwo);
            gate.release();

            assertTrue(queuedProcessed.await(2, TimeUnit.SECONDS));

            List<String> snapshot;
            synchronized (processedAfterInitial) {
                snapshot = new ArrayList<>(processedAfterInitial);
            }
            assertEquals(List.of("D1", "F2"), snapshot);
            verify(agentLoop, times(1)).processMessage(delayed);
        }
    }

    @Test
    void shouldNotMergeDelayedWakeUpWithQueuedUserFollowUpsWhenModeAll() throws Exception {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = runtimeConfigService("all");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService, null, null);

            Message initial = user("A");
            Message followUpOne = user("F1");
            Message delayed = delayed("D1");
            Message followUpTwo = user("F2");

            Gate gate = new Gate();
            List<String> processedAfterInitial = new ArrayList<>();
            CountDownLatch queuedProcessed = new CountDownLatch(3);

            org.mockito.Mockito.doAnswer(invocation -> {
                Message inbound = invocation.getArgument(0);
                if ("A".equals(inbound.getContent())) {
                    gate.await();
                    return null;
                }
                synchronized (processedAfterInitial) {
                    processedAfterInitial.add(inbound.getContent());
                }
                queuedProcessed.countDown();
                return null;
            }).when(agentLoop).processMessage(any(Message.class));

            coordinator.enqueue(initial);
            gate.awaitStarted();
            coordinator.enqueue(followUpOne);
            coordinator.enqueue(delayed);
            coordinator.enqueue(followUpTwo);
            gate.release();

            assertTrue(queuedProcessed.await(2, TimeUnit.SECONDS));

            List<String> snapshot;
            synchronized (processedAfterInitial) {
                snapshot = new ArrayList<>(processedAfterInitial);
            }
            assertEquals(List.of("F1", "D1", "F2"), snapshot);
        }
    }

    private static RuntimeConfigService runtimeConfigService(String followUpMode) {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isTurnQueueSteeringEnabled()).thenReturn(false);
        when(runtimeConfigService.getTurnQueueSteeringMode()).thenReturn("one-at-a-time");
        when(runtimeConfigService.getTurnQueueFollowUpMode()).thenReturn(followUpMode);
        return runtimeConfigService;
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

    private static Message internalRetry() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.MESSAGE_INTERNAL, true);
        metadata.put(ContextAttributes.TURN_QUEUE_KIND, ContextAttributes.TURN_QUEUE_KIND_INTERNAL_MODEL_RETRY);
        return Message.builder()
                .role("user")
                .content("retry-llm-turn")
                .channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID)
                .senderId("internal")
                .timestamp(Instant.EPOCH)
                .metadata(metadata)
                .build();
    }

    private static Message delayed(String content) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.MESSAGE_INTERNAL, true);
        metadata.put(ContextAttributes.MESSAGE_INTERNAL_KIND, ContextAttributes.MESSAGE_INTERNAL_KIND_DELAYED_ACTION);
        metadata.put(ContextAttributes.TURN_QUEUE_KIND, ContextAttributes.TURN_QUEUE_KIND_INTERNAL_DELAYED_ACTION);
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
