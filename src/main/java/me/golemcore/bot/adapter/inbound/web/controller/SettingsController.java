package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.inbound.web.dto.PreferencesUpdateRequest;
import me.golemcore.bot.adapter.inbound.web.dto.SettingsResponse;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Settings and preferences management endpoints.
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
@Slf4j
public class SettingsController {

    private static final String TELEGRAM_AUTH_MODE_USER = "user";
    private static final String TELEGRAM_AUTH_MODE_INVITE = "invite_only";

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
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime")
    public Mono<ResponseEntity<RuntimeConfig>> updateRuntimeConfig(@RequestBody RuntimeConfig config) {
        mergeRuntimeSecrets(runtimeConfigService.getRuntimeConfig(), config);
        validateLlmConfig(config.getLlm(), config.getModelRouter());
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/telegram")
    public Mono<ResponseEntity<RuntimeConfig>> updateTelegramConfig(
            @RequestBody RuntimeConfig.TelegramConfig telegramConfig) {
        normalizeAndValidateTelegramConfig(telegramConfig);
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        telegramConfig.setToken(mergeSecret(config.getTelegram().getToken(), telegramConfig.getToken()));
        config.setTelegram(telegramConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        eventPublisher.publishEvent(new TelegramRestartEvent());
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/models")
    public Mono<ResponseEntity<RuntimeConfig>> updateModelRouterConfig(
            @RequestBody RuntimeConfig.ModelRouterConfig modelRouterConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setModelRouter(modelRouterConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/llm")
    public Mono<ResponseEntity<RuntimeConfig>> updateLlmConfig(
            @RequestBody RuntimeConfig.LlmConfig llmConfig) {
        log.info("[Settings] Updating LLM config with {} providers: {}",
                llmConfig.getProviders() != null ? llmConfig.getProviders().size() : 0,
                llmConfig.getProviders() != null ? llmConfig.getProviders().keySet() : "null");
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        mergeLlmSecrets(config.getLlm(), llmConfig);
        validateLlmConfig(llmConfig, config.getModelRouter());
        config.setLlm(llmConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        log.info("[Settings] LLM config updated successfully");
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PostMapping("/runtime/llm/providers/{name}")
    public Mono<ResponseEntity<RuntimeConfig>> addLlmProvider(
            @PathVariable String name,
            @RequestBody RuntimeConfig.LlmProviderConfig providerConfig) {
        String normalizedName = name.toLowerCase(Locale.ROOT);
        if (!normalizedName.matches("[a-z0-9][a-z0-9_-]*")) {
            throw new IllegalArgumentException("Provider name must match [a-z0-9][a-z0-9_-]*");
        }
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        if (config.getLlm().getProviders().containsKey(normalizedName)) {
            throw new IllegalArgumentException("Provider '" + normalizedName + "' already exists");
        }
        validateProviderConfig(normalizedName, providerConfig);
        runtimeConfigService.addLlmProvider(normalizedName, providerConfig);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/llm/providers/{name}")
    public Mono<ResponseEntity<RuntimeConfig>> updateLlmProvider(
            @PathVariable String name,
            @RequestBody RuntimeConfig.LlmProviderConfig providerConfig) {
        String normalizedName = name.toLowerCase(Locale.ROOT);
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        if (!config.getLlm().getProviders().containsKey(normalizedName)) {
            throw new IllegalArgumentException("Provider '" + normalizedName + "' does not exist");
        }
        validateProviderConfig(normalizedName, providerConfig);
        runtimeConfigService.updateLlmProvider(normalizedName, providerConfig);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @DeleteMapping("/runtime/llm/providers/{name}")
    public Mono<ResponseEntity<Void>> removeLlmProvider(@PathVariable String name) {
        String normalizedName = name.toLowerCase(Locale.ROOT);
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        Set<String> usedProviders = getProvidersUsedByModelRouter(config.getModelRouter());
        if (usedProviders.contains(normalizedName)) {
            throw new IllegalArgumentException(
                    "Cannot remove provider '" + normalizedName + "' because it is used by model router tiers");
        }
        boolean removed = runtimeConfigService.removeLlmProvider(normalizedName);
        if (removed) {
            return Mono.just(ResponseEntity.ok().build());
        }
        return Mono.just(ResponseEntity.notFound().build());
    }

    private void validateProviderConfig(String name, RuntimeConfig.LlmProviderConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Provider config is required");
        }
        Integer timeout = config.getRequestTimeoutSeconds();
        if (timeout != null && (timeout < 1 || timeout > 3600)) {
            throw new IllegalArgumentException(
                    "llm.providers." + name + ".requestTimeoutSeconds must be between 1 and 3600");
        }
        String baseUrl = config.getBaseUrl();
        if (baseUrl != null && !baseUrl.isBlank() && !isValidHttpUrl(baseUrl)) {
            throw new IllegalArgumentException(
                    "llm.providers." + name + ".baseUrl must be a valid http(s) URL");
        }
    }

    @PutMapping("/runtime/tools")
    public Mono<ResponseEntity<RuntimeConfig>> updateToolsConfig(
            @RequestBody RuntimeConfig.ToolsConfig toolsConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        mergeToolsSecrets(config.getTools(), toolsConfig);
        config.setTools(toolsConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/voice")
    public Mono<ResponseEntity<RuntimeConfig>> updateVoiceConfig(
            @RequestBody RuntimeConfig.VoiceConfig voiceConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        voiceConfig.setApiKey(mergeSecret(config.getVoice().getApiKey(), voiceConfig.getApiKey()));
        config.setVoice(voiceConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/turn")
    public Mono<ResponseEntity<RuntimeConfig>> updateTurnConfig(
            @RequestBody RuntimeConfig.TurnConfig turnConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setTurn(turnConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/memory")
    public Mono<ResponseEntity<RuntimeConfig>> updateMemoryConfig(
            @RequestBody RuntimeConfig.MemoryConfig memoryConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setMemory(memoryConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/skills")
    public Mono<ResponseEntity<RuntimeConfig>> updateSkillsConfig(
            @RequestBody RuntimeConfig.SkillsConfig skillsConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setSkills(skillsConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/usage")
    public Mono<ResponseEntity<RuntimeConfig>> updateUsageConfig(
            @RequestBody RuntimeConfig.UsageConfig usageConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setUsage(usageConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/rag")
    public Mono<ResponseEntity<RuntimeConfig>> updateRagConfig(
            @RequestBody RuntimeConfig.RagConfig ragConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        ragConfig.setApiKey(mergeSecret(config.getRag().getApiKey(), ragConfig.getApiKey()));
        config.setRag(ragConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/mcp")
    public Mono<ResponseEntity<RuntimeConfig>> updateMcpConfig(
            @RequestBody RuntimeConfig.McpConfig mcpConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setMcp(mcpConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/webhooks")
    public Mono<ResponseEntity<Void>> updateWebhooksConfig(
            @RequestBody UserPreferences.WebhookConfig webhookConfig) {
        UserPreferences prefs = preferencesService.getPreferences();
        mergeWebhookSecrets(prefs.getWebhooks(), webhookConfig);
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
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
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
            throw new IllegalArgumentException("telegram.authMode is required");
        }

        if (!TELEGRAM_AUTH_MODE_USER.equals(authMode) && !TELEGRAM_AUTH_MODE_INVITE.equals(authMode)) {
            throw new IllegalArgumentException("telegram.authMode must be 'user' or 'invite_only'");
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

        if (TELEGRAM_AUTH_MODE_USER.equals(authMode) && allowedUsers.isEmpty()) {
            throw new IllegalArgumentException("telegram.allowedUsers must contain one ID in user mode");
        }

        if (TELEGRAM_AUTH_MODE_INVITE.equals(authMode) && !allowedUsers.isEmpty()) {
            throw new IllegalArgumentException("telegram.allowedUsers must be empty in invite_only mode");
        }
    }

    private void validateLlmConfig(RuntimeConfig.LlmConfig llmConfig,
            RuntimeConfig.ModelRouterConfig modelRouterConfig) {
        if (llmConfig == null) {
            throw new IllegalArgumentException("llm config is required");
        }

        Map<String, RuntimeConfig.LlmProviderConfig> providers = llmConfig.getProviders();
        if (providers == null) {
            llmConfig.setProviders(new LinkedHashMap<>());
            return;
        }

        Set<String> normalizedNames = new LinkedHashSet<>();
        for (Map.Entry<String, RuntimeConfig.LlmProviderConfig> entry : providers.entrySet()) {
            String providerName = entry.getKey();
            RuntimeConfig.LlmProviderConfig providerConfig = entry.getValue();

            if (providerName == null || providerName.isBlank()) {
                throw new IllegalArgumentException("llm.providers keys must be non-empty");
            }
            if (!providerName.equals(providerName.trim())) {
                throw new IllegalArgumentException("llm.providers keys must not have leading/trailing spaces");
            }
            if (!providerName.equals(providerName.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("llm.providers keys must be lowercase");
            }
            if (!providerName.matches("[a-z0-9][a-z0-9_-]*")) {
                throw new IllegalArgumentException(
                        "llm.providers keys must match [a-z0-9][a-z0-9_-]*");
            }
            if (!normalizedNames.add(providerName)) {
                throw new IllegalArgumentException("llm.providers contains duplicate provider key: " + providerName);
            }
            if (providerConfig == null) {
                throw new IllegalArgumentException("llm.providers." + providerName + " config is required");
            }

            Integer requestTimeoutSeconds = providerConfig.getRequestTimeoutSeconds();
            if (requestTimeoutSeconds != null && (requestTimeoutSeconds < 1 || requestTimeoutSeconds > 3600)) {
                throw new IllegalArgumentException(
                        "llm.providers." + providerName + ".requestTimeoutSeconds must be between 1 and 3600");
            }

            String baseUrl = providerConfig.getBaseUrl();
            if (baseUrl != null && !baseUrl.isBlank()) {
                if (!isValidHttpUrl(baseUrl)) {
                    throw new IllegalArgumentException(
                            "llm.providers." + providerName + ".baseUrl must be a valid http(s) URL");
                }
            }
        }

        Set<String> providersUsedByModelRouter = getProvidersUsedByModelRouter(modelRouterConfig);
        for (String usedProvider : providersUsedByModelRouter) {
            if (!providers.containsKey(usedProvider)) {
                throw new IllegalArgumentException(
                        "Cannot remove provider '" + usedProvider + "' because it is used by model router tiers");
            }
        }
    }

    private Set<String> getProvidersUsedByModelRouter(RuntimeConfig.ModelRouterConfig modelRouterConfig) {
        Set<String> usedProviders = new LinkedHashSet<>();
        if (modelRouterConfig == null) {
            return usedProviders;
        }
        addProviderFromModel(usedProviders, modelRouterConfig.getRoutingModel());
        addProviderFromModel(usedProviders, modelRouterConfig.getBalancedModel());
        addProviderFromModel(usedProviders, modelRouterConfig.getSmartModel());
        addProviderFromModel(usedProviders, modelRouterConfig.getCodingModel());
        addProviderFromModel(usedProviders, modelRouterConfig.getDeepModel());
        return usedProviders;
    }

    private void addProviderFromModel(Set<String> usedProviders, String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        int delimiterIndex = model.indexOf('/');
        if (delimiterIndex <= 0) {
            return;
        }
        usedProviders.add(model.substring(0, delimiterIndex));
    }

    private void mergeRuntimeSecrets(RuntimeConfig current, RuntimeConfig incoming) {
        if (current == null || incoming == null) {
            return;
        }
        if (incoming.getTelegram() != null && current.getTelegram() != null) {
            incoming.getTelegram()
                    .setToken(mergeSecret(current.getTelegram().getToken(), incoming.getTelegram().getToken()));
        }
        if (incoming.getVoice() != null && current.getVoice() != null) {
            incoming.getVoice().setApiKey(mergeSecret(current.getVoice().getApiKey(), incoming.getVoice().getApiKey()));
        }
        if (incoming.getRag() != null && current.getRag() != null) {
            incoming.getRag().setApiKey(mergeSecret(current.getRag().getApiKey(), incoming.getRag().getApiKey()));
        }
        mergeLlmSecrets(current.getLlm(), incoming.getLlm());
        mergeToolsSecrets(current.getTools(), incoming.getTools());
    }

    private void mergeLlmSecrets(RuntimeConfig.LlmConfig current, RuntimeConfig.LlmConfig incoming) {
        if (current == null || incoming == null || incoming.getProviders() == null) {
            return;
        }
        Map<String, RuntimeConfig.LlmProviderConfig> currentProviders = current.getProviders();
        for (Map.Entry<String, RuntimeConfig.LlmProviderConfig> entry : incoming.getProviders().entrySet()) {
            RuntimeConfig.LlmProviderConfig incomingProvider = entry.getValue();
            RuntimeConfig.LlmProviderConfig currentProvider = currentProviders != null
                    ? currentProviders.get(entry.getKey())
                    : null;
            if (incomingProvider != null && currentProvider != null) {
                incomingProvider.setApiKey(mergeSecret(currentProvider.getApiKey(), incomingProvider.getApiKey()));
            }
        }
    }

    private void mergeToolsSecrets(RuntimeConfig.ToolsConfig current, RuntimeConfig.ToolsConfig incoming) {
        if (current == null || incoming == null) {
            return;
        }
        incoming.setBraveSearchApiKey(mergeSecret(current.getBraveSearchApiKey(), incoming.getBraveSearchApiKey()));

        RuntimeConfig.ImapConfig currentImap = current.getImap();
        RuntimeConfig.ImapConfig incomingImap = incoming.getImap();
        if (incomingImap != null && currentImap != null) {
            incomingImap.setPassword(mergeSecret(currentImap.getPassword(), incomingImap.getPassword()));
        }

        RuntimeConfig.SmtpConfig currentSmtp = current.getSmtp();
        RuntimeConfig.SmtpConfig incomingSmtp = incoming.getSmtp();
        if (incomingSmtp != null && currentSmtp != null) {
            incomingSmtp.setPassword(mergeSecret(currentSmtp.getPassword(), incomingSmtp.getPassword()));
        }
    }

    private void mergeWebhookSecrets(UserPreferences.WebhookConfig current, UserPreferences.WebhookConfig incoming) {
        if (incoming == null) {
            return;
        }
        if (current != null) {
            incoming.setToken(mergeSecret(current.getToken(), incoming.getToken()));
        }
        if (incoming.getMappings() == null || incoming.getMappings().isEmpty() || current == null
                || current.getMappings() == null) {
            return;
        }

        Map<String, UserPreferences.HookMapping> currentByName = new LinkedHashMap<>();
        for (UserPreferences.HookMapping mapping : current.getMappings()) {
            if (mapping != null && mapping.getName() != null) {
                currentByName.put(mapping.getName(), mapping);
            }
        }

        for (UserPreferences.HookMapping mapping : incoming.getMappings()) {
            if (mapping == null || mapping.getName() == null) {
                continue;
            }
            UserPreferences.HookMapping existing = currentByName.get(mapping.getName());
            if (existing != null) {
                mapping.setHmacSecret(mergeSecret(existing.getHmacSecret(), mapping.getHmacSecret()));
            }
        }
    }

    private Secret mergeSecret(Secret current, Secret incoming) {
        if (incoming == null) {
            return current;
        }
        if (!Secret.hasValue(incoming)) {
            Secret retained = current != null ? current : Secret.builder().build();
            if (incoming.getEncrypted() != null) {
                retained.setEncrypted(incoming.getEncrypted());
            }
            retained.setPresent(Secret.hasValue(retained));
            return retained;
        }
        return Secret.builder()
                .value(incoming.getValue())
                .encrypted(Boolean.TRUE.equals(incoming.getEncrypted()))
                .present(true)
                .build();
    }

    private boolean isValidHttpUrl(String value) {
        try {
            java.net.URI uri = new java.net.URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return host != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (java.net.URISyntaxException ignored) {
            return false;
        }
    }

}
