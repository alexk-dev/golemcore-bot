package me.golemcore.bot.domain.loop;

import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.outbound.ChannelDeliveryPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TurnFeedbackCoordinatorTest {

    @Test
    void shouldSendTypingToTransportChatIdAndStopHandle() {
        ChannelDeliveryPort channel = mock(ChannelDeliveryPort.class);
        ChannelRuntimePort channelRuntimePort = mock(ChannelRuntimePort.class);
        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(channelRuntimePort.findChannel("telegram")).thenReturn(Optional.of(channel));
        TurnFeedbackCoordinator coordinator = new TurnFeedbackCoordinator(channelRuntimePort, preferencesService);
        Message message = Message.builder().channelType("telegram").chatId("conversation")
                .metadata(Map.of(ContextAttributes.TRANSPORT_CHAT_ID, "transport")).build();

        try (TurnFeedbackCoordinator.TypingHandle ignored = coordinator.startTyping(message)) {
            verify(channel).showTyping("transport");
        } finally {
            coordinator.shutdown();
        }
    }

    @Test
    void shouldSendRateLimitFeedbackThroughChannel() {
        ChannelDeliveryPort channel = mock(ChannelDeliveryPort.class);
        ChannelRuntimePort channelRuntimePort = mock(ChannelRuntimePort.class);
        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(channelRuntimePort.findChannel("telegram")).thenReturn(Optional.of(channel));
        when(preferencesService.getMessage("system.rate.limit")).thenReturn("Slow down");
        TurnFeedbackCoordinator coordinator = new TurnFeedbackCoordinator(channelRuntimePort, preferencesService);
        Message message = Message.builder().channelType("telegram").chatId("chat-1").build();

        coordinator.notifyRateLimited(message);

        verify(channel).sendMessage("chat-1", "Slow down");
        coordinator.shutdown();
    }
}
