package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.dto.PreferencesUpdateRequest;
import me.golemcore.bot.adapter.inbound.web.dto.SettingsResponse;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.TelegramRestartEvent;
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

    private static final String TELEGRAM_AUTH_MODE_USER = "user";
    private static final String TELEGRAM_AUTH_MODE_INVITE = "invite";

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
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfig()));
    }

    @PutMapping("/runtime")
    public Mono<ResponseEntity<RuntimeConfig>> updateRuntimeConfig(@RequestBody RuntimeConfig config) {
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfig()));
    }

    @PutMapping("/runtime/telegram")
    public Mono<ResponseEntity<RuntimeConfig>> updateTelegramConfig(
            @RequestBody RuntimeConfig.TelegramConfig telegramConfig) {
        normalizeAndValidateTelegramConfig(telegramConfig);
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setTelegram(telegramConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfig()));
    }

    @PutMapping("/runtime/models")
    public Mono<ResponseEntity<RuntimeConfig>> updateModelRouterConfig(
            @RequestBody RuntimeConfig.ModelRouterConfig modelRouterConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setModelRouter(modelRouterConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfig()));
    }

    @PutMapping("/runtime/tools")
    public Mono<ResponseEntity<RuntimeConfig>> updateToolsConfig(
            @RequestBody RuntimeConfig.ToolsConfig toolsConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setTools(toolsConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfig()));
    }

    @PutMapping("/runtime/voice")
    public Mono<ResponseEntity<RuntimeConfig>> updateVoiceConfig(
            @RequestBody RuntimeConfig.VoiceConfig voiceConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setVoice(voiceConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfig()));
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
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfig()));
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
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfig()));
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
        eventPublisher.publishEvent(new TelegramRestartEvent());
        return Mono.just(ResponseEntity.ok().build());
    }

    // ==================== DTOs ====================

    private record ModelDto(String id, String displayName, boolean hasReasoning, List<String> reasoningLevels) {
    }

    private record AdvancedConfigRequest(
            RuntimeConfig.RateLimitConfig rateLimit,
            RuntimeConfig.SecurityConfig security,
            RuntimeConfig.CompactionConfig compaction) {
    }

    private void normalizeAndValidateTelegramConfig(RuntimeConfig.TelegramConfig telegramConfig) {
        String authMode = telegramConfig.getAuthMode();
        if (authMode == null || authMode.isBlank()) {
            telegramConfig.setAuthMode(TELEGRAM_AUTH_MODE_INVITE);
            authMode = TELEGRAM_AUTH_MODE_INVITE;
        }

        if (!TELEGRAM_AUTH_MODE_USER.equals(authMode) && !TELEGRAM_AUTH_MODE_INVITE.equals(authMode)) {
            throw new IllegalArgumentException("telegram.authMode must be 'user' or 'invite'");
        }

        List<String> allowedUsers = telegramConfig.getAllowedUsers();
        if (allowedUsers == null) {
            telegramConfig.setAllowedUsers(List.of());
            return;
        }

        for (String userId : allowedUsers) {
            if (userId == null || !userId.matches("\\d+")) {
                throw new IllegalArgumentException("telegram.allowedUsers must contain numeric IDs only");
            }
        }

        if (TELEGRAM_AUTH_MODE_USER.equals(authMode) && allowedUsers.size() > 1) {
            throw new IllegalArgumentException("telegram.allowedUsers supports only one ID in user mode");
        }
    }

}
