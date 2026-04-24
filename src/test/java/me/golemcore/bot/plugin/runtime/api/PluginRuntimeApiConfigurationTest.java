package me.golemcore.bot.plugin.runtime.api;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginRuntimeApiConfigurationTest {

    @Test
    void shouldPreserveHostRuntimeSectionsWhenPluginUpdatesPartialConfig() {
        PluginRuntimeApiConfiguration configuration = new PluginRuntimeApiConfiguration();
        me.golemcore.bot.domain.service.RuntimeConfigService delegate = mock(
                me.golemcore.bot.domain.service.RuntimeConfigService.class);
        PluginRuntimeApiMapper mapper = new PluginRuntimeApiMapper();

        RuntimeConfig current = RuntimeConfig.builder()
                .telegram(RuntimeConfig.TelegramConfig.builder()
                        .enabled(true)
                        .token(Secret.of("telegram-token"))
                        .transportMode("webhook")
                        .webhookSecretToken("webhook-secret")
                        .conversationScope("thread")
                        .aggregateIncomingMessages(false)
                        .aggregationDelayMs(750)
                        .mergeForwardedMessages(false)
                        .mergeSequentialFragments(false)
                        .allowedUsers(List.of("123"))
                        .build())
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder()
                        .balancedModel("openai/gpt-5.1")
                        .build())
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(new LinkedHashMap<>(Map.of(
                                "openai", RuntimeConfig.LlmProviderConfig.builder()
                                        .apiKey(Secret.of("openai-secret"))
                                        .build())))
                        .build())
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .sttProvider("golemcore/elevenlabs")
                        .ttsProvider("golemcore/elevenlabs")
                        .build())
                .build();
        when(delegate.getRuntimeConfig()).thenReturn(current);

        me.golemcore.plugin.api.runtime.RuntimeConfigService pluginService = configuration
                .pluginRuntimeConfigService(delegate, mapper);

        me.golemcore.plugin.api.runtime.model.RuntimeConfig incoming = me.golemcore.plugin.api.runtime.model.RuntimeConfig
                .builder()
                .voice(me.golemcore.plugin.api.runtime.model.RuntimeConfig.VoiceConfig.builder()
                        .sttProvider("golemcore/whisper")
                        .ttsProvider("golemcore/elevenlabs")
                        .whisperSttUrl("http://localhost:5092")
                        .build())
                .build();

        pluginService.updateRuntimeConfig(incoming);

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(delegate).updateRuntimeConfig(captor.capture());
        RuntimeConfig saved = captor.getValue();

        assertEquals("openai/gpt-5.1", saved.getModelRouter().getBalancedModel());
        assertEquals("openai-secret", saved.getLlm().getProviders().get("openai").getApiKey().getValue());
        assertEquals("telegram-token", saved.getTelegram().getToken().getValue());
        assertEquals("webhook", saved.getTelegram().getTransportMode());
        assertEquals("webhook-secret", saved.getTelegram().getWebhookSecretToken());
        assertEquals("thread", saved.getTelegram().getConversationScope());
        assertEquals(false, saved.getTelegram().getAggregateIncomingMessages());
        assertEquals(750, saved.getTelegram().getAggregationDelayMs());
        assertEquals(false, saved.getTelegram().getMergeForwardedMessages());
        assertEquals(false, saved.getTelegram().getMergeSequentialFragments());
        assertTrue(saved.getTelegram().getAllowedUsers().contains("123"));
        assertEquals("golemcore/whisper", saved.getVoice().getSttProvider());
        assertEquals("http://localhost:5092", saved.getVoice().getWhisperSttUrl());
    }

    @Test
    void shouldIgnoreNullOrDefaultRuntimeSectionsWhenPluginUpdatesConfig() {
        PluginRuntimeApiConfiguration configuration = new PluginRuntimeApiConfiguration();
        me.golemcore.bot.domain.service.RuntimeConfigService delegate = mock(
                me.golemcore.bot.domain.service.RuntimeConfigService.class);
        PluginRuntimeApiMapper mapper = new PluginRuntimeApiMapper();

        RuntimeConfig current = RuntimeConfig.builder()
                .telegram(RuntimeConfig.TelegramConfig.builder()
                        .enabled(true)
                        .token(Secret.of("telegram-token"))
                        .transportMode("webhook")
                        .webhookSecretToken("webhook-secret")
                        .conversationScope("thread")
                        .aggregateIncomingMessages(false)
                        .aggregationDelayMs(750)
                        .mergeForwardedMessages(false)
                        .mergeSequentialFragments(false)
                        .allowedUsers(List.of("123"))
                        .build())
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .enabled(true)
                        .sttProvider("golemcore/whisper")
                        .ttsProvider("golemcore/elevenlabs")
                        .whisperSttUrl("http://localhost:5092")
                        .build())
                .build();
        when(delegate.getRuntimeConfig()).thenReturn(current);

        me.golemcore.plugin.api.runtime.RuntimeConfigService pluginService = configuration
                .pluginRuntimeConfigService(delegate, mapper);

        pluginService.updateRuntimeConfig(null);
        pluginService.updateRuntimeConfig(me.golemcore.plugin.api.runtime.model.RuntimeConfig.builder().build());

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(delegate, times(2)).updateRuntimeConfig(captor.capture());
        for (RuntimeConfig saved : captor.getAllValues()) {
            assertEquals("telegram-token", saved.getTelegram().getToken().getValue());
            assertEquals("webhook", saved.getTelegram().getTransportMode());
            assertEquals("webhook-secret", saved.getTelegram().getWebhookSecretToken());
            assertEquals("thread", saved.getTelegram().getConversationScope());
            assertEquals(false, saved.getTelegram().getAggregateIncomingMessages());
            assertEquals(750, saved.getTelegram().getAggregationDelayMs());
            assertEquals(false, saved.getTelegram().getMergeForwardedMessages());
            assertEquals(false, saved.getTelegram().getMergeSequentialFragments());
            assertTrue(saved.getTelegram().getAllowedUsers().contains("123"));
            assertEquals("golemcore/whisper", saved.getVoice().getSttProvider());
            assertEquals("http://localhost:5092", saved.getVoice().getWhisperSttUrl());
        }
    }

    @Test
    void shouldDelegateRuntimeConfigAccessorsAndInviteWorkflow() {
        PluginRuntimeApiConfiguration configuration = new PluginRuntimeApiConfiguration();
        me.golemcore.bot.domain.service.RuntimeConfigService delegate = mock(
                me.golemcore.bot.domain.service.RuntimeConfigService.class);
        PluginRuntimeApiMapper mapper = new PluginRuntimeApiMapper();

        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .telegram(RuntimeConfig.TelegramConfig.builder()
                        .enabled(true)
                        .token(Secret.of("telegram-token"))
                        .allowedUsers(List.of("42"))
                        .inviteCodes(List.of(RuntimeConfig.InviteCode.builder()
                                .code("invite-1")
                                .used(false)
                                .createdAt(Instant.parse("2026-03-08T10:15:30Z"))
                                .build()))
                        .build())
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .enabled(true)
                        .apiKey(Secret.of("voice-key"))
                        .voiceId("voice-1")
                        .ttsModelId("tts-1")
                        .sttModelId("stt-1")
                        .speed(1.5f)
                        .telegramTranscribeIncoming(true)
                        .sttProvider("golemcore/whisper")
                        .ttsProvider("golemcore/elevenlabs")
                        .whisperSttUrl("http://localhost:5092")
                        .whisperSttApiKey(Secret.of("whisper-key"))
                        .build())
                .build();
        when(delegate.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(delegate.getRuntimeConfigForApi()).thenReturn(runtimeConfig);
        when(delegate.isTelegramEnabled()).thenReturn(true);
        when(delegate.getTelegramToken()).thenReturn("telegram-token");
        when(delegate.getTelegramAllowedUsers()).thenReturn(List.of("42"));
        when(delegate.isVoiceEnabled()).thenReturn(true);
        when(delegate.getVoiceApiKey()).thenReturn("voice-key");
        when(delegate.getVoiceId()).thenReturn("voice-1");
        when(delegate.getTtsModelId()).thenReturn("tts-1");
        when(delegate.getSttModelId()).thenReturn("stt-1");
        when(delegate.getVoiceSpeed()).thenReturn(1.5f);
        when(delegate.isTelegramTranscribeIncomingEnabled()).thenReturn(true);
        when(delegate.getSttProvider()).thenReturn("golemcore/whisper");
        when(delegate.getTtsProvider()).thenReturn("golemcore/elevenlabs");
        when(delegate.getWhisperSttUrl()).thenReturn("http://localhost:5092");
        when(delegate.getWhisperSttApiKey()).thenReturn("whisper-key");
        when(delegate.isToolConfirmationEnabled()).thenReturn(true);
        when(delegate.getToolConfirmationTimeoutSeconds()).thenReturn(30);
        when(delegate.generateInviteCode()).thenReturn(RuntimeConfig.InviteCode.builder()
                .code("invite-2")
                .used(false)
                .createdAt(Instant.parse("2026-03-08T10:16:00Z"))
                .build());
        when(delegate.revokeInviteCode("invite-2")).thenReturn(true);
        when(delegate.redeemInviteCode("invite-2", "42")).thenReturn(true);
        when(delegate.removeTelegramAllowedUser("42")).thenReturn(true);

        me.golemcore.plugin.api.runtime.RuntimeConfigService pluginService = configuration
                .pluginRuntimeConfigService(delegate, mapper);

        assertEquals("telegram-token", pluginService.getRuntimeConfig().getTelegram().getToken().getValue());
        assertEquals("invite-1",
                pluginService.getRuntimeConfigForApi().getTelegram().getInviteCodes().getFirst().getCode());
        assertTrue(pluginService.isTelegramEnabled());
        assertEquals("telegram-token", pluginService.getTelegramToken());
        assertEquals(List.of("42"), pluginService.getTelegramAllowedUsers());
        assertTrue(pluginService.isVoiceEnabled());
        assertEquals("voice-key", pluginService.getVoiceApiKey());
        assertEquals("voice-1", pluginService.getVoiceId());
        assertEquals("tts-1", pluginService.getTtsModelId());
        assertEquals("stt-1", pluginService.getSttModelId());
        assertEquals(1.5f, pluginService.getVoiceSpeed());
        assertTrue(pluginService.isTelegramTranscribeIncomingEnabled());
        assertEquals("golemcore/whisper", pluginService.getSttProvider());
        assertEquals("golemcore/elevenlabs", pluginService.getTtsProvider());
        assertEquals("http://localhost:5092", pluginService.getWhisperSttUrl());
        assertEquals("whisper-key", pluginService.getWhisperSttApiKey());
        assertTrue(pluginService.isToolConfirmationEnabled());
        assertEquals(30, pluginService.getToolConfirmationTimeoutSeconds());
        assertEquals("invite-2", pluginService.generateInviteCode().getCode());
        assertTrue(pluginService.revokeInviteCode("invite-2"));
        assertTrue(pluginService.redeemInviteCode("invite-2", "42"));
        assertTrue(pluginService.removeTelegramAllowedUser("42"));
    }

    @Test
    void shouldDelegatePlanAndRuntimeUtilityServices() {
        PluginRuntimeApiConfiguration configuration = new PluginRuntimeApiConfiguration();
        PluginRuntimeApiMapper mapper = new PluginRuntimeApiMapper();

        me.golemcore.bot.domain.service.ActiveSessionPointerService activePointerDelegate = mock(
                me.golemcore.bot.domain.service.ActiveSessionPointerService.class);
        when(activePointerDelegate.buildTelegramPointerKey("42")).thenReturn("telegram:42");
        when(activePointerDelegate.getActiveConversationKey("telegram:42")).thenReturn(Optional.of("conv-1"));

        me.golemcore.plugin.api.runtime.ActiveSessionPointerService activePointerService = configuration
                .pluginActiveSessionPointerService(activePointerDelegate);
        assertEquals("telegram:42", activePointerService.buildTelegramPointerKey("42"));
        assertEquals(Optional.of("conv-1"), activePointerService.getActiveConversationKey("telegram:42"));
        activePointerService.setActiveConversationKey("telegram:42", "conv-2");
        verify(activePointerDelegate).setActiveConversationKey("telegram:42", "conv-2");

        me.golemcore.bot.domain.service.AutoModeService autoModeDelegate = mock(
                me.golemcore.bot.domain.service.AutoModeService.class);
        when(autoModeDelegate.isFeatureEnabled()).thenReturn(true);
        when(autoModeDelegate.isAutoModeEnabled()).thenReturn(false);

        me.golemcore.plugin.api.runtime.AutoModeService autoModeService = configuration
                .pluginAutoModeService(autoModeDelegate);
        assertTrue(autoModeService.isFeatureEnabled());
        assertFalse(autoModeService.isAutoModeEnabled());
        autoModeService.enableAutoMode();
        autoModeService.disableAutoMode();
        verify(autoModeDelegate).enableAutoMode();
        verify(autoModeDelegate).disableAutoMode();

        me.golemcore.bot.domain.service.ModelSelectionService modelSelectionDelegate = mock(
                me.golemcore.bot.domain.service.ModelSelectionService.class);
        when(modelSelectionDelegate.resolveForTier("balanced"))
                .thenReturn(new me.golemcore.bot.domain.service.ModelSelectionService.ModelSelection(
                        "openai/gpt-5.1",
                        "high"));

        me.golemcore.plugin.api.runtime.ModelSelectionService modelSelectionService = configuration
                .pluginModelSelectionService(modelSelectionDelegate);
        me.golemcore.plugin.api.runtime.ModelSelectionService.ModelSelection selection = modelSelectionService
                .resolveForTier("balanced");
        assertEquals("openai/gpt-5.1", selection.model());
        assertEquals("high", selection.reasoning());

        me.golemcore.bot.domain.service.PlanService planDelegate = mock(
                me.golemcore.bot.domain.service.PlanService.class);
        me.golemcore.bot.domain.model.SessionIdentity hostIdentity = new me.golemcore.bot.domain.model.SessionIdentity(
                "telegram",
                "chat-1");
        when(planDelegate.isFeatureEnabled()).thenReturn(true);
        when(planDelegate.isPlanModeActive(hostIdentity)).thenReturn(true);
        when(planDelegate.getPlan("plan-1")).thenReturn(Optional.of(me.golemcore.bot.domain.model.Plan.builder()
                .id("plan-1")
                .title("My plan")
                .status(me.golemcore.bot.domain.model.Plan.PlanStatus.READY)
                .build()));

        me.golemcore.plugin.api.runtime.PlanService planService = configuration.pluginPlanService(planDelegate, mapper);
        me.golemcore.plugin.api.runtime.model.SessionIdentity pluginIdentity = new me.golemcore.plugin.api.runtime.model.SessionIdentity(
                "telegram",
                "chat-1");
        assertTrue(planService.isFeatureEnabled());
        assertTrue(planService.isPlanModeActive(pluginIdentity));
        planService.activatePlanMode(pluginIdentity, "transport-1", "balanced");
        planService.deactivatePlanMode(pluginIdentity);
        assertEquals("plan-1", planService.getPlan("plan-1").orElseThrow().getId());
        assertThrows(UnsupportedOperationException.class, () -> planService.approvePlan("plan-1"));
        planService.cancelPlan("plan-1");
        verify(planDelegate).activatePlanMode(hostIdentity, "transport-1", "balanced");
        verify(planDelegate).deactivatePlanMode(hostIdentity);
        verify(planDelegate).cancelPlan("plan-1");

        me.golemcore.bot.domain.service.PluginConfigurationService pluginConfigDelegate = mock(
                me.golemcore.bot.domain.service.PluginConfigurationService.class);
        when(pluginConfigDelegate.hasPluginConfig("plugin-1")).thenReturn(true);
        when(pluginConfigDelegate.getPluginConfig("plugin-1")).thenReturn(Map.of("enabled", true));

        me.golemcore.plugin.api.runtime.PluginConfigurationService pluginConfigurationService = configuration
                .pluginRuntimePluginConfigurationService(pluginConfigDelegate);
        assertTrue(pluginConfigurationService.hasPluginConfig("plugin-1"));
        assertEquals(Boolean.TRUE, pluginConfigurationService.getPluginConfig("plugin-1").get("enabled"));
        pluginConfigurationService.savePluginConfig("plugin-1", Map.of("enabled", false));
        pluginConfigurationService.deletePluginConfig("plugin-1");
        verify(pluginConfigDelegate).savePluginConfig("plugin-1", Map.of("enabled", false));
        verify(pluginConfigDelegate).deletePluginConfig("plugin-1");
    }

    @Test
    void shouldDelegateUserPreferencesMessageAndAllowlistServices() {
        PluginRuntimeApiConfiguration configuration = new PluginRuntimeApiConfiguration();
        PluginRuntimeApiMapper mapper = new PluginRuntimeApiMapper();

        me.golemcore.bot.domain.service.UserPreferencesService userPreferencesDelegate = mock(
                me.golemcore.bot.domain.service.UserPreferencesService.class);
        me.golemcore.bot.domain.model.UserPreferences hostPreferences = me.golemcore.bot.domain.model.UserPreferences
                .builder()
                .language("en")
                .timezone("UTC")
                .notificationsEnabled(true)
                .modelTier("balanced")
                .build();
        when(userPreferencesDelegate.getPreferences()).thenReturn(hostPreferences);
        when(userPreferencesDelegate.getLanguage()).thenReturn("en");
        when(userPreferencesDelegate.getMessage("greeting", "Alex")).thenReturn("Hello Alex");

        me.golemcore.plugin.api.runtime.UserPreferencesService userPreferencesService = configuration
                .pluginUserPreferencesService(userPreferencesDelegate, mapper);
        assertEquals("en", userPreferencesService.getPreferences().getLanguage());
        assertEquals("en", userPreferencesService.getLanguage());

        me.golemcore.plugin.api.runtime.model.UserPreferences pluginPreferences = me.golemcore.plugin.api.runtime.model.UserPreferences
                .builder()
                .language("fr")
                .timezone("Europe/Paris")
                .notificationsEnabled(false)
                .build();
        userPreferencesService.savePreferences(pluginPreferences);
        userPreferencesService.setLanguage("fr");
        assertEquals("Hello Alex", userPreferencesService.getMessage("greeting", "Alex"));

        ArgumentCaptor<me.golemcore.bot.domain.model.UserPreferences> preferencesCaptor = ArgumentCaptor
                .forClass(me.golemcore.bot.domain.model.UserPreferences.class);
        verify(userPreferencesDelegate).savePreferences(preferencesCaptor.capture());
        assertEquals("fr", preferencesCaptor.getValue().getLanguage());
        assertEquals("Europe/Paris", preferencesCaptor.getValue().getTimezone());
        verify(userPreferencesDelegate).setLanguage("fr");

        me.golemcore.bot.infrastructure.i18n.MessageService messageDelegate = mock(
                me.golemcore.bot.infrastructure.i18n.MessageService.class);
        when(messageDelegate.getMessage(eq("status.ready"), any(Object[].class))).thenReturn("Ready Alex");
        when(messageDelegate.getLanguageDisplayName("en")).thenReturn("English");

        me.golemcore.plugin.api.runtime.i18n.MessageService messageService = configuration
                .pluginMessageService(messageDelegate);
        assertEquals("Ready Alex", messageService.getMessage("status.ready", "Alex"));
        assertEquals("English", messageService.getLanguageDisplayName("en"));

        me.golemcore.bot.security.AllowlistValidator allowlistDelegate = mock(
                me.golemcore.bot.security.AllowlistValidator.class);
        when(allowlistDelegate.isAllowed("telegram", "42")).thenReturn(true);

        me.golemcore.plugin.api.runtime.security.AllowlistValidator allowlistValidator = configuration
                .pluginAllowlistValidator(allowlistDelegate);
        assertTrue(allowlistValidator.isAllowed("telegram", "42"));
    }
}
