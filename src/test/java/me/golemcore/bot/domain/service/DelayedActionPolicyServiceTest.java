package me.golemcore.bot.domain.service;

import me.golemcore.bot.adapter.inbound.web.WebChannelAdapter;
import me.golemcore.bot.adapter.inbound.webhook.WebhookChannelAdapter;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ProgressUpdate;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.inbound.ChannelPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DelayedActionPolicyServiceTest {

    @Test
    void shouldRejectBlankAndWebhookChannelsForScheduling() {
        DelayedActionPolicyService service = createService(true, true, new ChannelRegistry(List.of()));

        assertFalse(service.canScheduleActions((String) null));
        assertFalse(service.canScheduleActions("   "));
        assertFalse(service.canScheduleActions("webhook"));
        assertFalse(service.canScheduleActions(" WEBHOOK "));
        assertTrue(service.canScheduleActions("telegram"));
    }

    @Test
    void shouldRequireEnabledRuntimeAndRunLaterSupport() {
        ChannelPort telegram = new BasicChannel("telegram", true);
        ChannelRegistry registry = new ChannelRegistry(List.of(telegram));

        DelayedActionPolicyService disabled = createService(false, true, registry);
        assertFalse(disabled.canScheduleActions());
        assertFalse(disabled.canScheduleActions("telegram"));
        assertFalse(disabled.canPersistDelayedIntent("telegram"));
        assertFalse(disabled.canWakeSessionLater("telegram", "chat-1"));
        assertFalse(disabled.canScheduleRunLater("telegram", "chat-1"));

        DelayedActionPolicyService runLaterDisabled = createService(true, true, registry, false);
        assertTrue(runLaterDisabled.canPersistDelayedIntent("telegram"));
        assertFalse(runLaterDisabled.canWakeSessionLater("telegram", "chat-1"));
        assertFalse(runLaterDisabled.canScheduleRunLater("telegram", "chat-1"));

        DelayedActionPolicyService enabled = createService(true, true, registry, true);
        assertTrue(enabled.canPersistDelayedIntent("telegram"));
        assertTrue(enabled.canWakeSessionLater("telegram", "chat-1"));
        assertTrue(enabled.canScheduleRunLater("telegram", "chat-1"));
    }

    @Test
    void shouldRespectNotificationAndChannelRuntimeStateForProactiveMessages() {
        ChannelPort stoppedTelegram = new BasicChannel("telegram", false);
        DelayedActionPolicyService notificationsDisabled = createService(true, false,
                new ChannelRegistry(List.of(stoppedTelegram)));
        assertFalse(notificationsDisabled.supportsProactiveMessage("telegram", "chat-1"));

        DelayedActionPolicyService missingChannel = createService(true, true, new ChannelRegistry(List.of()));
        assertFalse(missingChannel.supportsProactiveMessage("telegram", "chat-1"));

        DelayedActionPolicyService stoppedChannel = createService(true, true,
                new ChannelRegistry(List.of(stoppedTelegram)));
        assertFalse(stoppedChannel.supportsProactiveMessage("telegram", "chat-1"));

        ChannelPort runningTelegram = new BasicChannel("telegram", true);
        DelayedActionPolicyService enabled = createService(true, true,
                new ChannelRegistry(List.of(runningTelegram)));
        assertTrue(enabled.supportsProactiveMessage("telegram", "chat-1"));
        assertTrue(enabled.supportsDelayedExecution("telegram", "chat-1"));
    }

    @Test
    void shouldAllowDelayedExecutionWithoutImmediateProactiveMessageSupport() {
        ChannelPort runningTelegram = new BasicChannel("telegram", true);
        DelayedActionPolicyService notificationsDisabled = createService(true, false,
                new ChannelRegistry(List.of(runningTelegram)));

        assertTrue(notificationsDisabled.canPersistDelayedIntent("telegram"));
        assertTrue(notificationsDisabled.canWakeSessionLater("telegram", "chat-1"));
        assertTrue(notificationsDisabled.canScheduleRunLater("telegram", "chat-1"));
        assertFalse(notificationsDisabled.supportsProactiveMessage("telegram", "chat-1"));
        assertTrue(notificationsDisabled.supportsDelayedExecution("telegram", "chat-1"));
    }

    @Test
    void shouldUseActiveWebSessionsAndRejectWebhookChannels() {
        WebChannelAdapter webChannel = mock(WebChannelAdapter.class);
        when(webChannel.getChannelType()).thenReturn("web");
        when(webChannel.isRunning()).thenReturn(true);
        when(webChannel.hasActiveSession("active-chat")).thenReturn(true);
        when(webChannel.hasActiveSession("inactive-chat")).thenReturn(false);

        DelayedActionPolicyService webService = createService(true, true,
                new ChannelRegistry(List.of(webChannel)));
        assertTrue(webService.canPersistDelayedIntent("web"));
        assertTrue(webService.canWakeSessionLater("web", "active-chat"));
        assertTrue(webService.supportsProactiveMessage("web", "active-chat"));
        assertTrue(webService.supportsDelayedExecution("web", "active-chat"));

        assertTrue(webService.canWakeSessionLater("web", "inactive-chat"));
        assertFalse(webService.supportsProactiveMessage("web", "inactive-chat"));
        assertTrue(webService.supportsDelayedExecution("web", "inactive-chat"));

        WebhookChannelAdapter webhookChannel = mock(WebhookChannelAdapter.class);
        when(webhookChannel.getChannelType()).thenReturn("webhook");
        when(webhookChannel.isRunning()).thenReturn(true);

        DelayedActionPolicyService webhookService = createService(true, true,
                new ChannelRegistry(List.of(webhookChannel)));
        assertFalse(webhookService.canPersistDelayedIntent("webhook"));
        assertFalse(webhookService.canWakeSessionLater("webhook", "chat-1"));
        assertFalse(webhookService.supportsProactiveMessage("webhook", "chat-1"));
        assertFalse(webhookService.supportsDelayedExecution("webhook", "chat-1"));
    }

    @Test
    void shouldDetectWhetherChannelOverridesDocumentDelivery() {
        DelayedActionPolicyService defaultDocumentService = createService(true, true,
                new ChannelRegistry(List.of(new BasicChannel("telegram", true))));
        assertFalse(defaultDocumentService.supportsProactiveDocument("telegram", "chat-1"));

        DelayedActionPolicyService customDocumentService = createService(true, true,
                new ChannelRegistry(List.of(new DocumentChannel("telegram", true))));
        assertTrue(customDocumentService.supportsProactiveDocument("telegram", "chat-1"));

        DelayedActionPolicyService unsupportedService = createService(true, false,
                new ChannelRegistry(List.of(new DocumentChannel("telegram", true))));
        assertFalse(unsupportedService.supportsProactiveDocument("telegram", "chat-1"));
    }

    private static DelayedActionPolicyService createService(boolean delayedActionsEnabled, boolean notificationsEnabled,
            ChannelRegistry channelRegistry) {
        return createService(delayedActionsEnabled, notificationsEnabled, channelRegistry, true);
    }

    private static DelayedActionPolicyService createService(boolean delayedActionsEnabled, boolean notificationsEnabled,
            ChannelRegistry channelRegistry, boolean runLaterEnabled) {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(delayedActionsEnabled);
        when(runtimeConfigService.isDelayedActionsRunLaterEnabled()).thenReturn(runLaterEnabled);

        UserPreferencesService userPreferencesService = mock(UserPreferencesService.class);
        UserPreferences preferences = UserPreferences.builder()
                .notificationsEnabled(notificationsEnabled)
                .build();
        when(userPreferencesService.getPreferences()).thenReturn(preferences);

        return new DelayedActionPolicyService(runtimeConfigService, userPreferencesService, channelRegistry);
    }

    private static class BasicChannel implements ChannelPort {

        private final String channelType;
        private final boolean running;

        private BasicChannel(String channelType, boolean running) {
            this.channelType = channelType;
            this.running = running;
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
    }

    private static final class DocumentChannel extends BasicChannel {

        private DocumentChannel(String channelType, boolean running) {
            super(channelType, running);
        }

        @Override
        public CompletableFuture<Void> sendDocument(String chatId, byte[] fileData,
                String filename, String caption) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> sendMessage(String chatId, String content, Map<String, Object> hints) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
