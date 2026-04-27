package me.golemcore.bot.domain.scheduling;

import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ProgressUpdate;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.port.channel.ChannelPort;
import me.golemcore.bot.port.outbound.ChannelDeliveryPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import org.junit.jupiter.api.Test;

class DelayedActionPolicyServiceCapabilityTest {

    @Test
    void shouldUseChannelCapabilitiesInsteadOfConcreteAdapterTypes() {
        ChannelPort web = new CapabilityChannel("web", true, false, true, false);
        ChannelPort webhook = new CapabilityChannel("webhook", true, false, false, false);

        DelayedActionPolicyService webService = createService(true, true, List.of(web));
        assertThat(webService.supportsProactiveMessage("web", "active-chat")).isTrue();
        assertThat(webService.supportsProactiveDocument("web", "active-chat")).isFalse();

        DelayedActionPolicyService webhookService = createService(true, true, List.of(webhook));
        assertThat(webhookService.canPersistDelayedIntent("webhook")).isFalse();
        assertThat(webhookService.supportsProactiveMessage("webhook", "chat-1")).isFalse();
    }

    private static DelayedActionPolicyService createService(boolean delayedActionsEnabled, boolean notificationsEnabled,
            List<ChannelPort> channels) {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(delayedActionsEnabled);
        when(runtimeConfigService.isDelayedActionsRunLaterEnabled()).thenReturn(true);

        UserPreferencesService userPreferencesService = mock(UserPreferencesService.class);
        when(userPreferencesService.getPreferences())
                .thenReturn(UserPreferences.builder().notificationsEnabled(notificationsEnabled).build());

        ChannelRuntimePort channelRuntimePort = new ChannelRuntimePort() {
            @Override
            public java.util.Optional<ChannelDeliveryPort> findChannel(String channelType) {
                return channels.stream().filter(channel -> channel.getChannelType().equals(channelType))
                        .map(channel -> (ChannelDeliveryPort) channel).findFirst();
            }

            @Override
            public List<ChannelDeliveryPort> listChannels() {
                return channels.stream().map(channel -> (ChannelDeliveryPort) channel).toList();
            }
        };

        return new DelayedActionPolicyService(runtimeConfigService, userPreferencesService, channelRuntimePort);
    }

    private static final class CapabilityChannel implements ChannelPort {

        private final String channelType;
        private final boolean running;
        private final boolean proactiveDocument;
        private final boolean proactiveMessage;
        private final boolean voiceResponseEnabled;

        private CapabilityChannel(String channelType, boolean running, boolean proactiveDocument,
                boolean proactiveMessage, boolean voiceResponseEnabled) {
            this.channelType = channelType;
            this.running = running;
            this.proactiveDocument = proactiveDocument;
            this.proactiveMessage = proactiveMessage;
            this.voiceResponseEnabled = voiceResponseEnabled;
        }

        @Override
        public String getChannelType() {
            return channelType;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public CompletableFuture<Void> sendMessage(String chatId, String content) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> sendMessage(Message message) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> sendVoice(String chatId, byte[] voiceData) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean isVoiceResponseEnabled() {
            return voiceResponseEnabled;
        }

        @Override
        public CompletableFuture<Void> sendRuntimeEvent(String chatId, RuntimeEvent event) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> sendProgressUpdate(String chatId, ProgressUpdate update) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean isAuthorized(String senderId) {
            return true;
        }

        @Override
        public void onMessage(Consumer<Message> handler) {
        }

        @Override
        public CompletableFuture<Void> sendMessage(String chatId, String content, Map<String, Object> hints) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean supportsProactiveMessage(String chatId) {
            return proactiveMessage;
        }

        @Override
        public boolean supportsProactiveDocument(String chatId) {
            return proactiveDocument;
        }
    }
}
