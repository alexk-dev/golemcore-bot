package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor);

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
        } finally {
            executor.shutdownNow();
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
