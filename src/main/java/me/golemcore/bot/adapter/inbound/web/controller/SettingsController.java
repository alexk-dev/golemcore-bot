package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.dto.PreferencesUpdateRequest;
import me.golemcore.bot.adapter.inbound.web.dto.SettingsResponse;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings and preferences management endpoints.
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private static final String SECRET_MASK = "***";

    private final UserPreferencesService preferencesService;
    private final ModelSelectionService modelSelectionService;
    private final RuntimeConfigService runtimeConfigService;
    private final ApplicationEventPublisher eventPublisher;

    @GetMapping
    public Mono<ResponseEntity<SettingsResponse>> getSettings() {
        UserPreferences prefs = preferencesService.getPreferences();
        Map<String, SettingsResponse.TierOverrideDto> overrideDtos = new LinkedHashMap<>();
        if (prefs.getTierOverrides() != null) {
            prefs.getTierOverrides()
                    .forEach((tier, override) -> overrideDtos.put(tier, SettingsResponse.TierOverrideDto.builder()
                            .model(override.getModel())
                            .reasoning(override.getReasoning())
                            .build()));
        }
        SettingsResponse response = SettingsResponse.builder()
                .language(prefs.getLanguage())
                .timezone(prefs.getTimezone())
                .notificationsEnabled(prefs.isNotificationsEnabled())
                .modelTier(prefs.getModelTier())
                .tierForce(prefs.isTierForce())
                .tierOverrides(overrideDtos)
                .build();
        return Mono.just(ResponseEntity.ok(response));
    }

    @PutMapping("/preferences")
    public Mono<ResponseEntity<SettingsResponse>> updatePreferences(
            @RequestBody PreferencesUpdateRequest request) {
        UserPreferences prefs = preferencesService.getPreferences();
        if (request.getLanguage() != null) {
            prefs.setLanguage(request.getLanguage());
        }
        if (request.getTimezone() != null) {
            prefs.setTimezone(request.getTimezone());
        }
        if (request.getNotificationsEnabled() != null) {
            prefs.setNotificationsEnabled(request.getNotificationsEnabled());
        }
        if (request.getModelTier() != null) {
            prefs.setModelTier(request.getModelTier());
        }
        if (request.getTierForce() != null) {
            prefs.setTierForce(request.getTierForce());
        }
        preferencesService.savePreferences(prefs);
        return getSettings();
    }

    @GetMapping("/models")
    public Mono<ResponseEntity<Map<String, List<ModelDto>>>> getModels() {
        Map<String, List<ModelSelectionService.AvailableModel>> grouped = modelSelectionService
                .getAvailableModelsGrouped();
        Map<String, List<ModelDto>> result = new LinkedHashMap<>();
        grouped.forEach((provider, models) -> result.put(provider, models.stream()
                .map(m -> new ModelDto(m.id(), m.displayName(), m.hasReasoning(), m.reasoningLevels()))
                .toList()));
        return Mono.just(ResponseEntity.ok(result));
    }

    @PutMapping("/tier-overrides")
    public Mono<ResponseEntity<SettingsResponse>> updateTierOverrides(
            @RequestBody Map<String, SettingsResponse.TierOverrideDto> overrides) {
        UserPreferences prefs = preferencesService.getPreferences();
        Map<String, UserPreferences.TierOverride> tierOverrides = new LinkedHashMap<>();
        overrides.forEach((tier, dto) -> tierOverrides.put(tier,
                new UserPreferences.TierOverride(dto.getModel(), dto.getReasoning())));
        prefs.setTierOverrides(tierOverrides);
        preferencesService.savePreferences(prefs);
        return getSettings();
    }

    // ==================== Runtime Config ====================

    @GetMapping("/runtime")
    public Mono<ResponseEntity<RuntimeConfig>> getRuntimeConfig() {
        RuntimeConfig runtimeConfig = runtimeConfigService.getRuntimeConfig();
        return Mono.just(ResponseEntity.ok(maskSecrets(runtimeConfig)));
    }

    @PutMapping("/runtime")
    public Mono<ResponseEntity<RuntimeConfig>> updateRuntimeConfig(@RequestBody RuntimeConfig config) {
        RuntimeConfig current = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig merged = preserveExistingSecrets(current, config);
        runtimeConfigService.updateRuntimeConfig(merged);
        return Mono.just(ResponseEntity.ok(maskSecrets(runtimeConfigService.getRuntimeConfig())));
    }

    @PutMapping("/runtime/secrets")
    public Mono<ResponseEntity<RuntimeConfig>> updateRuntimeSecrets(@RequestBody SecretUpdateRequest request) {
        RuntimeConfig cfg = runtimeConfigService.getRuntimeConfig();

        if (request.telegramToken() != null && !isMaskedSecret(request.telegramToken())) {
            cfg.getTelegram().setToken(request.telegramToken());
        }
        if (request.braveSearchApiKey() != null && !isMaskedSecret(request.braveSearchApiKey())) {
            cfg.getTools().setBraveSearchApiKey(request.braveSearchApiKey());
        }
        if (request.imapPassword() != null && !isMaskedSecret(request.imapPassword())) {
            cfg.getTools().getImap().setPassword(request.imapPassword());
        }
        if (request.smtpPassword() != null && !isMaskedSecret(request.smtpPassword())) {
            cfg.getTools().getSmtp().setPassword(request.smtpPassword());
        }
        if (request.voiceApiKey() != null && !isMaskedSecret(request.voiceApiKey())) {
            cfg.getVoice().setApiKey(request.voiceApiKey());
        }

        runtimeConfigService.updateRuntimeConfig(cfg);
        return Mono.just(ResponseEntity.ok(maskSecrets(runtimeConfigService.getRuntimeConfig())));
    }

    @PutMapping("/runtime/telegram")
    public Mono<ResponseEntity<RuntimeConfig>> updateTelegramConfig(
            @RequestBody RuntimeConfig.TelegramConfig telegramConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.TelegramConfig merged = RuntimeConfig.TelegramConfig.builder()
                .enabled(telegramConfig.getEnabled())
                .token(config.getTelegram().getToken())
                .authMode(telegramConfig.getAuthMode())
                .allowedUsers(telegramConfig.getAllowedUsers())
                .inviteCodes(telegramConfig.getInviteCodes())
                .build();
        config.setTelegram(merged);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(maskSecrets(runtimeConfigService.getRuntimeConfig())));
    }

    @PutMapping("/runtime/models")
    public Mono<ResponseEntity<RuntimeConfig>> updateModelRouterConfig(
            @RequestBody RuntimeConfig.ModelRouterConfig modelRouterConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setModelRouter(modelRouterConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(maskSecrets(runtimeConfigService.getRuntimeConfig())));
    }

    @PutMapping("/runtime/tools")
    public Mono<ResponseEntity<RuntimeConfig>> updateToolsConfig(
            @RequestBody RuntimeConfig.ToolsConfig toolsConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.ToolsConfig merged = mergeToolsWithSecretPreservation(config.getTools(), toolsConfig);
        config.setTools(merged);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(maskSecrets(runtimeConfigService.getRuntimeConfig())));
    }

    @PutMapping("/runtime/voice")
    public Mono<ResponseEntity<RuntimeConfig>> updateVoiceConfig(
            @RequestBody RuntimeConfig.VoiceConfig voiceConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.VoiceConfig merged = RuntimeConfig.VoiceConfig.builder()
                .enabled(voiceConfig.getEnabled())
                .apiKey(config.getVoice().getApiKey())
                .voiceId(voiceConfig.getVoiceId())
                .ttsModelId(voiceConfig.getTtsModelId())
                .sttModelId(voiceConfig.getSttModelId())
                .speed(voiceConfig.getSpeed())
                .build();
        config.setVoice(merged);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(maskSecrets(runtimeConfigService.getRuntimeConfig())));
    }

    @PutMapping("/runtime/webhooks")
    public Mono<ResponseEntity<Void>> updateWebhooksConfig(
            @RequestBody UserPreferences.WebhookConfig webhookConfig) {
        UserPreferences prefs = preferencesService.getPreferences();
        prefs.setWebhooks(webhookConfig);
        preferencesService.savePreferences(prefs);
        return Mono.just(ResponseEntity.ok().build());
    }

    @PutMapping("/runtime/auto")
    public Mono<ResponseEntity<RuntimeConfig>> updateAutoConfig(
            @RequestBody RuntimeConfig.AutoModeConfig autoConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setAutoMode(autoConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(maskSecrets(runtimeConfigService.getRuntimeConfig())));
    }

    @PutMapping("/runtime/advanced")
    public Mono<ResponseEntity<RuntimeConfig>> updateAdvancedConfig(
            @RequestBody AdvancedConfigRequest request) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        if (request.rateLimit() != null) {
            config.setRateLimit(request.rateLimit());
        }
        if (request.security() != null) {
            config.setSecurity(request.security());
        }
        if (request.compaction() != null) {
            config.setCompaction(request.compaction());
        }
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(maskSecrets(runtimeConfigService.getRuntimeConfig())));
    }

    // ==================== Invite Codes ====================

    @PostMapping("/telegram/invite-codes")
    public Mono<ResponseEntity<RuntimeConfig.InviteCode>> generateInviteCode() {
        RuntimeConfig.InviteCode code = runtimeConfigService.generateInviteCode();
        return Mono.just(ResponseEntity.ok(code));
    }

    @DeleteMapping("/telegram/invite-codes/{code}")
    public Mono<ResponseEntity<Void>> revokeInviteCode(@PathVariable String code) {
        boolean revoked = runtimeConfigService.revokeInviteCode(code);
        if (revoked) {
            return Mono.just(ResponseEntity.ok().build());
        }
        return Mono.just(ResponseEntity.notFound().build());
    }

    // ==================== Telegram Restart ====================

    @PostMapping("/telegram/restart")
    public Mono<ResponseEntity<Void>> restartTelegram() {
        eventPublisher.publishEvent(new TelegramRestartEvent(this));
        return Mono.just(ResponseEntity.ok().build());
    }

    private RuntimeConfig preserveExistingSecrets(RuntimeConfig current, RuntimeConfig incoming) {
        RuntimeConfig merged = incoming;

        if (incoming.getTelegram() != null && current.getTelegram() != null) {
            incoming.getTelegram().setToken(current.getTelegram().getToken());
        }

        if (incoming.getVoice() != null && current.getVoice() != null) {
            incoming.getVoice().setApiKey(current.getVoice().getApiKey());
        }

        if (incoming.getTools() != null && current.getTools() != null) {
            incoming.setTools(mergeToolsWithSecretPreservation(current.getTools(), incoming.getTools()));
        }

        return merged;
    }

    private RuntimeConfig.ToolsConfig mergeToolsWithSecretPreservation(
            RuntimeConfig.ToolsConfig current,
            RuntimeConfig.ToolsConfig incoming) {

        if (incoming == null) {
            return current;
        }

        incoming.setBraveSearchApiKey(current.getBraveSearchApiKey());

        if (incoming.getImap() != null && current.getImap() != null) {
            incoming.getImap().setPassword(current.getImap().getPassword());
        }

        if (incoming.getSmtp() != null && current.getSmtp() != null) {
            incoming.getSmtp().setPassword(current.getSmtp().getPassword());
        }

        return incoming;
    }

    private RuntimeConfig maskSecrets(RuntimeConfig source) {
        RuntimeConfig masked = source;

        if (masked.getTelegram() != null) {
            masked.getTelegram().setToken(maskValue(masked.getTelegram().getToken()));
        }

        if (masked.getTools() != null) {
            masked.getTools().setBraveSearchApiKey(maskValue(masked.getTools().getBraveSearchApiKey()));
            if (masked.getTools().getImap() != null) {
                masked.getTools().getImap().setPassword(maskValue(masked.getTools().getImap().getPassword()));
            }
            if (masked.getTools().getSmtp() != null) {
                masked.getTools().getSmtp().setPassword(maskValue(masked.getTools().getSmtp().getPassword()));
            }
        }

        if (masked.getVoice() != null) {
            masked.getVoice().setApiKey(maskValue(masked.getVoice().getApiKey()));
        }

        return masked;
    }

    private String maskValue(String value) {
        return value != null && !value.isBlank() ? SECRET_MASK : value;
    }

    private boolean isMaskedSecret(String value) {
        return SECRET_MASK.equals(value);
    }

    // ==================== DTOs ====================

    private record ModelDto(String id, String displayName, boolean hasReasoning, List<String> reasoningLevels) {
    }

    private record AdvancedConfigRequest(
            RuntimeConfig.RateLimitConfig rateLimit,
            RuntimeConfig.SecurityConfig security,
            RuntimeConfig.CompactionConfig compaction) {
    }

    private record SecretUpdateRequest(
            String telegramToken,
            String braveSearchApiKey,
            String imapPassword,
            String smtpPassword,
            String voiceApiKey) {
    }

    /**
     * Event published when Telegram adapter should restart.
     */
    public static class TelegramRestartEvent extends org.springframework.context.ApplicationEvent {
        private static final long serialVersionUID = 1L;

        public TelegramRestartEvent(Object source) {
            super(source);
        }
    }
}
