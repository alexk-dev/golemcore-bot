package me.golemcore.bot.plugin.runtime.api;

import me.golemcore.plugin.api.runtime.ActiveSessionPointerService;
import me.golemcore.plugin.api.runtime.AutoModeService;
import me.golemcore.plugin.api.runtime.ModelSelectionService;
import me.golemcore.plugin.api.runtime.PluginConfigurationService;
import me.golemcore.plugin.api.runtime.PlanExecutionService;
import me.golemcore.plugin.api.runtime.PlanService;
import me.golemcore.plugin.api.runtime.RuntimeConfigService;
import me.golemcore.plugin.api.runtime.UserPreferencesService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.CompletableFuture;

/**
 * Exposes engine delegates under the isolated plugin runtime API namespace.
 */
@Configuration(proxyBeanMethods = false)
public class PluginRuntimeApiConfiguration {

    @Bean
    public ActiveSessionPointerService pluginActiveSessionPointerService(
            me.golemcore.bot.domain.service.ActiveSessionPointerService delegate) {
        return new ActiveSessionPointerService() {
            @Override
            public String buildTelegramPointerKey(String transportChatId) {
                return delegate.buildTelegramPointerKey(transportChatId);
            }

            @Override
            public java.util.Optional<String> getActiveConversationKey(String pointerKey) {
                return delegate.getActiveConversationKey(pointerKey);
            }

            @Override
            public void setActiveConversationKey(String pointerKey, String conversationKey) {
                delegate.setActiveConversationKey(pointerKey, conversationKey);
            }
        };
    }

    @Bean
    public AutoModeService pluginAutoModeService(me.golemcore.bot.domain.service.AutoModeService delegate) {
        return new AutoModeService() {
            @Override
            public boolean isFeatureEnabled() {
                return delegate.isFeatureEnabled();
            }

            @Override
            public boolean isAutoModeEnabled() {
                return delegate.isAutoModeEnabled();
            }

            @Override
            public void enableAutoMode() {
                delegate.enableAutoMode();
            }

            @Override
            public void disableAutoMode() {
                delegate.disableAutoMode();
            }
        };
    }

    @Bean
    public ModelSelectionService pluginModelSelectionService(
            me.golemcore.bot.domain.service.ModelSelectionService delegate) {
        return tier -> {
            me.golemcore.bot.domain.service.ModelSelectionService.ModelSelection selection = delegate.resolveForTier(
                    tier);
            return new ModelSelectionService.ModelSelection(selection.model(), selection.reasoning());
        };
    }

    @Bean
    public PlanService pluginPlanService(
            me.golemcore.bot.domain.service.PlanService delegate,
            PluginRuntimeApiMapper mapper) {
        return new PlanService() {
            @Override
            public boolean isFeatureEnabled() {
                return delegate.isFeatureEnabled();
            }

            @Override
            public boolean isPlanModeActive(me.golemcore.plugin.api.runtime.model.SessionIdentity sessionIdentity) {
                return delegate.isPlanModeActive(mapper.toHostSessionIdentity(sessionIdentity));
            }

            @Override
            public void activatePlanMode(me.golemcore.plugin.api.runtime.model.SessionIdentity sessionIdentity,
                    String transportChatId,
                    String modelTier) {
                delegate.activatePlanMode(mapper.toHostSessionIdentity(sessionIdentity), transportChatId, modelTier);
            }

            @Override
            public void deactivatePlanMode(me.golemcore.plugin.api.runtime.model.SessionIdentity sessionIdentity) {
                delegate.deactivatePlanMode(mapper.toHostSessionIdentity(sessionIdentity));
            }

            @Override
            public java.util.Optional<me.golemcore.plugin.api.runtime.model.Plan> getPlan(String planId) {
                return delegate.getPlan(planId)
                        .map(mapper::toPluginPlan);
            }

            @Override
            public void approvePlan(String planId) {
                throw new UnsupportedOperationException("Plan approval is not supported by ephemeral plan mode");
            }

            @Override
            public void cancelPlan(String planId) {
                delegate.cancelPlan(planId);
            }
        };
    }

    @Bean
    public PlanExecutionService pluginPlanExecutionService() {
        return planId -> CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Plan execution is not supported by ephemeral plan mode: " + planId));
    }

    @Bean
    public RuntimeConfigService pluginRuntimeConfigService(
            me.golemcore.bot.domain.service.RuntimeConfigService delegate,
            PluginRuntimeApiMapper mapper) {
        return new RuntimeConfigService() {
            @Override
            public me.golemcore.plugin.api.runtime.model.RuntimeConfig getRuntimeConfig() {
                return mapper.toPluginRuntimeConfig(delegate.getRuntimeConfig());
            }

            @Override
            public me.golemcore.plugin.api.runtime.model.RuntimeConfig getRuntimeConfigForApi() {
                return mapper.toPluginRuntimeConfig(delegate.getRuntimeConfigForApi());
            }

            @Override
            public void updateRuntimeConfig(me.golemcore.plugin.api.runtime.model.RuntimeConfig newConfig) {
                me.golemcore.bot.domain.model.RuntimeConfig current = delegate.getRuntimeConfig();
                me.golemcore.bot.domain.model.RuntimeConfig incoming = mapper.toHostRuntimeConfig(newConfig);
                if (incoming != null) {
                    if (incoming.getTelegram() != null
                            && !incoming.getTelegram()
                                    .equals(new me.golemcore.bot.domain.model.RuntimeConfig.TelegramConfig())) {
                        current.setTelegram(incoming.getTelegram());
                    }
                    if (incoming.getVoice() != null
                            && !incoming.getVoice()
                                    .equals(new me.golemcore.bot.domain.model.RuntimeConfig.VoiceConfig())) {
                        current.setVoice(incoming.getVoice());
                    }
                }
                delegate.updateRuntimeConfig(current);
            }

            @Override
            public boolean isTelegramEnabled() {
                return delegate.isTelegramEnabled();
            }

            @Override
            public String getTelegramToken() {
                return delegate.getTelegramToken();
            }

            @Override
            public java.util.List<String> getTelegramAllowedUsers() {
                return delegate.getTelegramAllowedUsers();
            }

            @Override
            public boolean isVoiceEnabled() {
                return delegate.isVoiceEnabled();
            }

            @Override
            public String getVoiceApiKey() {
                return delegate.getVoiceApiKey();
            }

            @Override
            public String getVoiceId() {
                return delegate.getVoiceId();
            }

            @Override
            public String getTtsModelId() {
                return delegate.getTtsModelId();
            }

            @Override
            public String getSttModelId() {
                return delegate.getSttModelId();
            }

            @Override
            public float getVoiceSpeed() {
                return delegate.getVoiceSpeed();
            }

            @Override
            public boolean isTelegramTranscribeIncomingEnabled() {
                return delegate.isTelegramTranscribeIncomingEnabled();
            }

            @Override
            public String getSttProvider() {
                return delegate.getSttProvider();
            }

            @Override
            public String getTtsProvider() {
                return delegate.getTtsProvider();
            }

            @Override
            public String getWhisperSttUrl() {
                return delegate.getWhisperSttUrl();
            }

            @Override
            public String getWhisperSttApiKey() {
                return delegate.getWhisperSttApiKey();
            }

            @Override
            public boolean isToolConfirmationEnabled() {
                return delegate.isToolConfirmationEnabled();
            }

            @Override
            public int getToolConfirmationTimeoutSeconds() {
                return delegate.getToolConfirmationTimeoutSeconds();
            }

            @Override
            public me.golemcore.plugin.api.runtime.model.RuntimeConfig.InviteCode generateInviteCode() {
                return mapper.toPluginInviteCode(delegate.generateInviteCode());
            }

            @Override
            public boolean revokeInviteCode(String code) {
                return delegate.revokeInviteCode(code);
            }

            @Override
            public boolean redeemInviteCode(String code, String userId) {
                return delegate.redeemInviteCode(code, userId);
            }

            @Override
            public boolean removeTelegramAllowedUser(String userId) {
                return delegate.removeTelegramAllowedUser(userId);
            }
        };
    }

    @Bean
    public PluginConfigurationService pluginRuntimePluginConfigurationService(
            me.golemcore.bot.domain.service.PluginConfigurationService delegate) {
        return new PluginConfigurationService() {
            @Override
            public boolean hasPluginConfig(String pluginId) {
                return delegate.hasPluginConfig(pluginId);
            }

            @Override
            public java.util.Map<String, Object> getPluginConfig(String pluginId) {
                return delegate.getPluginConfig(pluginId);
            }

            @Override
            public void savePluginConfig(String pluginId, java.util.Map<String, Object> config) {
                delegate.savePluginConfig(pluginId, config);
            }

            @Override
            public void deletePluginConfig(String pluginId) {
                delegate.deletePluginConfig(pluginId);
            }
        };
    }

    @Bean
    public UserPreferencesService pluginUserPreferencesService(
            me.golemcore.bot.domain.service.UserPreferencesService delegate,
            PluginRuntimeApiMapper mapper) {
        return new UserPreferencesService() {
            @Override
            public me.golemcore.plugin.api.runtime.model.UserPreferences getPreferences() {
                return mapper.toPluginUserPreferences(delegate.getPreferences());
            }

            @Override
            public void savePreferences(me.golemcore.plugin.api.runtime.model.UserPreferences preferences) {
                delegate.savePreferences(mapper.toHostUserPreferences(preferences));
            }

            @Override
            public String getLanguage() {
                return delegate.getLanguage();
            }

            @Override
            public void setLanguage(String language) {
                delegate.setLanguage(language);
            }

            @Override
            public String getMessage(String key, Object... args) {
                return delegate.getMessage(key, args);
            }
        };
    }

    @Bean
    public me.golemcore.plugin.api.runtime.i18n.MessageService pluginMessageService(
            me.golemcore.bot.infrastructure.i18n.MessageService delegate) {
        return new me.golemcore.plugin.api.runtime.i18n.MessageService() {
            @Override
            public String getMessage(String key, Object... args) {
                return delegate.getMessage(key, args);
            }

            @Override
            public String getLanguageDisplayName(String lang) {
                return delegate.getLanguageDisplayName(lang);
            }
        };
    }

    @Bean
    public me.golemcore.plugin.api.runtime.security.AllowlistValidator pluginAllowlistValidator(
            me.golemcore.bot.security.AllowlistValidator delegate) {
        return delegate::isAllowed;
    }
}
