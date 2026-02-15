package me.golemcore.bot.domain.loop;

import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.SessionRunCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InboundMessageListenerTest {

    private SessionRunCoordinator coordinator;
    private InboundMessageListener listener;

    @BeforeEach
    void setUp() {
        coordinator = mock(SessionRunCoordinator.class);
        listener = new InboundMessageListener(coordinator);
    }

    @Test
    void shouldEnqueueMessageOnInboundEvent() {
        Message message = Message.builder()
                .role("user")
                .content("Hello")
                .channelType("telegram")
                .chatId("123")
                .senderId("u1")
                .timestamp(Instant.now())
                .build();

        AgentLoop.InboundMessageEvent event = new AgentLoop.InboundMessageEvent(message);

        listener.onInboundMessage(event);

        verify(coordinator).enqueue(message);
    }
}
