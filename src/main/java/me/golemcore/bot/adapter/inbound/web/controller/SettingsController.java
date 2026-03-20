package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.inbound.web.dto.PreferencesUpdateRequest;
import me.golemcore.bot.adapter.inbound.web.dto.SettingsResponse;
import me.golemcore.bot.domain.model.MemoryPreset;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.MemoryPresetService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.plugin.runtime.SttProviderRegistry;
import me.golemcore.bot.plugin.runtime.TtsProviderRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Settings and preferences management endpoints.
 */
@RestController
@RequestMapping("/api/settings")
@Slf4j
public class SettingsController {

    private static final String TELEGRAM_AUTH_MODE_INVITE_ONLY = "invite_only";
    private static final String STT_PROVIDER_ELEVENLABS = "golemcore/elevenlabs";
    private static final String STT_PROVIDER_WHISPER = "golemcore/whisper";
    private static final String LEGACY_STT_PROVIDER_ELEVENLABS = "elevenlabs";
    private static final String LEGACY_STT_PROVIDER_WHISPER = "whisper";
    private static final Set<String> VALID_API_TYPES = Set.of("openai", "anthropic", "gemini");
    private static final String DEFAULT_COMPACTION_TRIGGER_MODE = "model_ratio";
    private static final String COMPACTION_TRIGGER_MODE_TOKEN_THRESHOLD = "token_threshold";
    private static final Set<String> VALID_COMPACTION_TRIGGER_MODES = Set.of(
            DEFAULT_COMPACTION_TRIGGER_MODE,
            COMPACTION_TRIGGER_MODE_TOKEN_THRESHOLD);
    private static final double DEFAULT_COMPACTION_MODEL_THRESHOLD_RATIO = 0.95d;
    private static final int MEMORY_SOFT_BUDGET_MIN = 200;
    private static final int MEMORY_SOFT_BUDGET_MAX = 10000;
    private static final int MEMORY_MAX_BUDGET_MIN = 200;
    private static final int MEMORY_MAX_BUDGET_MAX = 12000;
    private static final int MEMORY_TOP_K_MIN = 0;
    private static final int MEMORY_TOP_K_MAX = 30;
    private static final int MEMORY_DECAY_DAYS_MIN = 1;
    private static final int MEMORY_DECAY_DAYS_MAX = 3650;
    private static final int MEMORY_RETRIEVAL_LOOKBACK_DAYS_MIN = 1;
    private static final int MEMORY_RETRIEVAL_LOOKBACK_DAYS_MAX = 90;
    private static final int TURN_PROGRESS_BATCH_SIZE_MIN = 1;
    private static final int TURN_PROGRESS_BATCH_SIZE_MAX = 50;
    private static final int TURN_PROGRESS_MAX_SILENCE_SECONDS_MIN = 1;
    private static final int TURN_PROGRESS_MAX_SILENCE_SECONDS_MAX = 300;
    private static final int TURN_PROGRESS_SUMMARY_TIMEOUT_MS_MIN = 1000;
    private static final int TURN_PROGRESS_SUMMARY_TIMEOUT_MS_MAX = 60000;
    private static final Pattern SHELL_ENV_VAR_NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> RESERVED_SHELL_ENV_VAR_NAMES = Set.of("HOME", "PWD");
    private static final int SHELL_ENV_VAR_NAME_MAX_LENGTH = 128;
    private static final int SHELL_ENV_VAR_VALUE_MAX_LENGTH = 8192;

    private final UserPreferencesService preferencesService;
    private final ModelSelectionService modelSelectionService;
    private final RuntimeConfigService runtimeConfigService;
    private final MemoryPresetService memoryPresetService;
    private final SttProviderRegistry sttProviderRegistry;
    private final TtsProviderRegistry ttsProviderRegistry;

    public SettingsController(UserPreferencesService preferencesService,
            ModelSelectionService modelSelectionService,
            RuntimeConfigService runtimeConfigService,
            MemoryPresetService memoryPresetService,
            SttProviderRegistry sttProviderRegistry,
            TtsProviderRegistry ttsProviderRegistry) {
        this.preferencesService = preferencesService;
        this.modelSelectionService = modelSelectionService;
        this.runtimeConfigService = runtimeConfigService;
        this.memoryPresetService = memoryPresetService;
        this.sttProviderRegistry = sttProviderRegistry;
        this.ttsProviderRegistry = ttsProviderRegistry;
    }

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
                .webhooks(prefs.getWebhooks())
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
                .map(m -> new ModelDto(m.id(), m.displayName(), m.hasReasoning(),
                        m.reasoningLevels(), m.supportsVision()))
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
        RuntimeConfig current = runtimeConfigService.getRuntimeConfig();
        rejectManagedHiveMutation(current, config != null ? config.getHive() : null);
        RuntimeConfig merged = mergeRuntimeConfigSections(current, config);
        if (merged.getTelegram() == null) {
            merged.setTelegram(new RuntimeConfig.TelegramConfig());
        }
        normalizeAndValidateTelegramConfig(merged.getTelegram());
        mergeRuntimeSecrets(current, merged);
        normalizeAndValidateShellEnvironmentVariables(merged.getTools());
        validateLlmConfig(merged.getLlm(), merged.getModelRouter());
        if (merged.getMemory() != null) {
            validateMemoryConfig(merged.getMemory());
        }
        validateCompactionConfig(merged.getCompaction());
        validateVoiceConfig(merged.getVoice());
        validateHiveConfig(merged.getHive());
        runtimeConfigService.updateRuntimeConfig(merged);
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
        if (!removed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Provider '" + normalizedName + "' not found");
        }
        return Mono.just(ResponseEntity.ok().build());
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
        String apiType = config.getApiType();
        if (apiType != null && !apiType.isBlank() && !VALID_API_TYPES.contains(apiType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "llm.providers." + name + ".apiType must be one of " + VALID_API_TYPES);
        }
    }

    @PutMapping("/runtime/tools")
    public Mono<ResponseEntity<RuntimeConfig>> updateToolsConfig(
            @RequestBody RuntimeConfig.ToolsConfig toolsConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        normalizeAndValidateShellEnvironmentVariables(toolsConfig);
        config.setTools(toolsConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @GetMapping("/runtime/tools/shell/env")
    public Mono<ResponseEntity<List<RuntimeConfig.ShellEnvironmentVariable>>> getShellEnvironmentVariables() {
        RuntimeConfig.ToolsConfig toolsConfig = runtimeConfigService.getRuntimeConfigForApi().getTools();
        List<RuntimeConfig.ShellEnvironmentVariable> variables = toolsConfig != null
                && toolsConfig.getShellEnvironmentVariables() != null
                        ? toolsConfig.getShellEnvironmentVariables()
                        : List.of();
        return Mono.just(ResponseEntity.ok(variables));
    }

    @PostMapping("/runtime/tools/shell/env")
    public Mono<ResponseEntity<RuntimeConfig>> createShellEnvironmentVariable(
            @RequestBody RuntimeConfig.ShellEnvironmentVariable variable) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.ToolsConfig toolsConfig = ensureToolsConfig(config);
        List<RuntimeConfig.ShellEnvironmentVariable> variables = ensureShellEnvironmentVariables(toolsConfig);

        RuntimeConfig.ShellEnvironmentVariable normalized = normalizeAndValidateShellEnvironmentVariable(variable);
        if (containsShellEnvironmentVariableName(variables, normalized.getName())) {
            throw new IllegalArgumentException(
                    "tools.shellEnvironmentVariables contains duplicate name: " + normalized.getName());
        }
        variables.add(normalized);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/tools/shell/env/{name}")
    public Mono<ResponseEntity<RuntimeConfig>> updateShellEnvironmentVariable(
            @PathVariable String name,
            @RequestBody RuntimeConfig.ShellEnvironmentVariable variable) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.ToolsConfig toolsConfig = ensureToolsConfig(config);
        List<RuntimeConfig.ShellEnvironmentVariable> variables = ensureShellEnvironmentVariables(toolsConfig);
        String normalizedCurrentName = normalizeAndValidateShellEnvironmentVariableName(name);

        int index = findShellEnvironmentVariableIndex(variables, normalizedCurrentName);
        if (index < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Shell environment variable '" + normalizedCurrentName + "' not found");
        }

        RuntimeConfig.ShellEnvironmentVariable source = variable != null ? variable
                : RuntimeConfig.ShellEnvironmentVariable.builder().build();
        String updatedName = source.getName() == null || source.getName().isBlank()
                ? normalizedCurrentName
                : source.getName();
        RuntimeConfig.ShellEnvironmentVariable normalizedUpdated = normalizeAndValidateShellEnvironmentVariable(
                RuntimeConfig.ShellEnvironmentVariable.builder()
                        .name(updatedName)
                        .value(source.getValue())
                        .build());

        if (!normalizedCurrentName.equals(normalizedUpdated.getName())
                && containsShellEnvironmentVariableName(variables, normalizedUpdated.getName())) {
            throw new IllegalArgumentException(
                    "tools.shellEnvironmentVariables contains duplicate name: " + normalizedUpdated.getName());
        }

        variables.set(index, normalizedUpdated);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @DeleteMapping("/runtime/tools/shell/env/{name}")
    public Mono<ResponseEntity<RuntimeConfig>> deleteShellEnvironmentVariable(@PathVariable String name) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.ToolsConfig toolsConfig = ensureToolsConfig(config);
        List<RuntimeConfig.ShellEnvironmentVariable> variables = ensureShellEnvironmentVariables(toolsConfig);
        String normalizedName = normalizeAndValidateShellEnvironmentVariableName(name);

        int index = findShellEnvironmentVariableIndex(variables, normalizedName);
        if (index < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Shell environment variable '" + normalizedName + "' not found");
        }

        variables.remove(index);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/voice")
    public Mono<ResponseEntity<RuntimeConfig>> updateVoiceConfig(
            @RequestBody RuntimeConfig.VoiceConfig voiceConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        voiceConfig.setApiKey(mergeSecret(config.getVoice().getApiKey(), voiceConfig.getApiKey()));
        voiceConfig.setWhisperSttApiKey(
                mergeSecret(config.getVoice().getWhisperSttApiKey(), voiceConfig.getWhisperSttApiKey()));
        validateVoiceConfig(voiceConfig);
        config.setVoice(voiceConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/turn")
    public Mono<ResponseEntity<RuntimeConfig>> updateTurnConfig(
            @RequestBody RuntimeConfig.TurnConfig turnConfig) {
        validateTurnConfig(turnConfig);
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setTurn(turnConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/memory")
    public Mono<ResponseEntity<RuntimeConfig>> updateMemoryConfig(
            @RequestBody RuntimeConfig.MemoryConfig memoryConfig) {
        validateMemoryConfig(memoryConfig);
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setMemory(memoryConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @GetMapping("/runtime/memory/presets")
    public Mono<ResponseEntity<List<MemoryPreset>>> getMemoryPresets() {
        return Mono.just(ResponseEntity.ok(memoryPresetService.getPresets()));
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

    @PutMapping("/runtime/mcp")
    public Mono<ResponseEntity<RuntimeConfig>> updateMcpConfig(
            @RequestBody RuntimeConfig.McpConfig mcpConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setMcp(mcpConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/hive")
    public Mono<ResponseEntity<RuntimeConfig>> updateHiveConfig(
            @RequestBody RuntimeConfig.HiveConfig hiveConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        rejectManagedHiveMutation(config, hiveConfig);
        validateHiveConfig(hiveConfig);
        config.setHive(hiveConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime/plan")
    public Mono<ResponseEntity<RuntimeConfig>> updatePlanConfig(
            @RequestBody RuntimeConfig.PlanConfig planConfig) {
        validatePlanConfig(planConfig);
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setPlan(planConfig);
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
            validateCompactionConfig(request.compaction());
            config.setCompaction(request.compaction());
        }
        runtimeConfigService.updateRuntimeConfig(config);
        return Mono.just(ResponseEntity.ok(runtimeConfigService.getRuntimeConfig()));
    }

    // ==================== DTOs ====================

    private record ModelDto(String id, String displayName, boolean hasReasoning,
            List<String> reasoningLevels, boolean supportsVision) {
    }

    private record AdvancedConfigRequest(
            RuntimeConfig.RateLimitConfig rateLimit,
            RuntimeConfig.SecurityConfig security,
            RuntimeConfig.CompactionConfig compaction) {
    }

    private void normalizeAndValidateTelegramConfig(RuntimeConfig.TelegramConfig telegramConfig) {
        telegramConfig.setAuthMode(TELEGRAM_AUTH_MODE_INVITE_ONLY);

        List<String> allowedUsers = telegramConfig.getAllowedUsers();
        if (allowedUsers == null) {
            telegramConfig.setAllowedUsers(new ArrayList<>());
            return;
        }

        for (String userId : allowedUsers) {
            if (userId == null || !userId.matches("\\d+")) {
                throw new IllegalArgumentException("telegram.allowedUsers must contain numeric IDs only");
            }
        }

        if (allowedUsers.size() > 1) {
            throw new IllegalArgumentException("telegram.allowedUsers supports only one invited user");
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

            String apiType = providerConfig.getApiType();
            if (apiType != null && !apiType.isBlank()
                    && !VALID_API_TYPES.contains(apiType.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "llm.providers." + providerName + ".apiType must be one of " + VALID_API_TYPES);
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

    private void validateTurnConfig(RuntimeConfig.TurnConfig turnConfig) {
        if (turnConfig == null) {
            throw new IllegalArgumentException("turn config is required");
        }
        if (turnConfig.getMaxLlmCalls() != null && turnConfig.getMaxLlmCalls() < 1) {
            throw new IllegalArgumentException("turn.maxLlmCalls must be >= 1");
        }
        if (turnConfig.getMaxToolExecutions() != null && turnConfig.getMaxToolExecutions() < 1) {
            throw new IllegalArgumentException("turn.maxToolExecutions must be >= 1");
        }
        if (turnConfig.getDeadline() != null && !turnConfig.getDeadline().isBlank()) {
            try {
                java.time.Duration deadline = java.time.Duration.parse(turnConfig.getDeadline().trim());
                if (deadline.isZero() || deadline.isNegative()) {
                    throw new IllegalArgumentException("turn.deadline must be a positive ISO-8601 duration");
                }
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("turn.deadline must be a valid ISO-8601 duration");
            }
        }
        validateRange(turnConfig.getProgressBatchSize(), TURN_PROGRESS_BATCH_SIZE_MIN, TURN_PROGRESS_BATCH_SIZE_MAX,
                "turn.progressBatchSize");
        validateRange(turnConfig.getProgressMaxSilenceSeconds(),
                TURN_PROGRESS_MAX_SILENCE_SECONDS_MIN,
                TURN_PROGRESS_MAX_SILENCE_SECONDS_MAX,
                "turn.progressMaxSilenceSeconds");
        validateRange(turnConfig.getProgressSummaryTimeoutMs(),
                TURN_PROGRESS_SUMMARY_TIMEOUT_MS_MIN,
                TURN_PROGRESS_SUMMARY_TIMEOUT_MS_MAX,
                "turn.progressSummaryTimeoutMs");
    }

    private void validateRange(Integer value, int min, int max, String fieldName) {
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max);
        }
    }

    private void validateMemoryConfig(RuntimeConfig.MemoryConfig memoryConfig) {
        if (memoryConfig == null) {
            throw new IllegalArgumentException("memory config is required");
        }

        validateNullableInteger(memoryConfig.getSoftPromptBudgetTokens(), MEMORY_SOFT_BUDGET_MIN,
                MEMORY_SOFT_BUDGET_MAX,
                "memory.softPromptBudgetTokens");
        validateNullableInteger(memoryConfig.getMaxPromptBudgetTokens(), MEMORY_MAX_BUDGET_MIN, MEMORY_MAX_BUDGET_MAX,
                "memory.maxPromptBudgetTokens");
        validateNullableInteger(memoryConfig.getWorkingTopK(), MEMORY_TOP_K_MIN, MEMORY_TOP_K_MAX,
                "memory.workingTopK");
        validateNullableInteger(memoryConfig.getEpisodicTopK(), MEMORY_TOP_K_MIN, MEMORY_TOP_K_MAX,
                "memory.episodicTopK");
        validateNullableInteger(memoryConfig.getSemanticTopK(), MEMORY_TOP_K_MIN, MEMORY_TOP_K_MAX,
                "memory.semanticTopK");
        validateNullableInteger(memoryConfig.getProceduralTopK(), MEMORY_TOP_K_MIN, MEMORY_TOP_K_MAX,
                "memory.proceduralTopK");
        validateNullableDouble(memoryConfig.getPromotionMinConfidence(), 0.0, 1.0, "memory.promotionMinConfidence");
        validateNullableInteger(memoryConfig.getDecayDays(), MEMORY_DECAY_DAYS_MIN, MEMORY_DECAY_DAYS_MAX,
                "memory.decayDays");
        validateNullableInteger(memoryConfig.getRetrievalLookbackDays(), MEMORY_RETRIEVAL_LOOKBACK_DAYS_MIN,
                MEMORY_RETRIEVAL_LOOKBACK_DAYS_MAX, "memory.retrievalLookbackDays");

        Integer softBudget = memoryConfig.getSoftPromptBudgetTokens();
        Integer maxBudget = memoryConfig.getMaxPromptBudgetTokens();
        if (softBudget != null && maxBudget != null && maxBudget < softBudget) {
            throw new IllegalArgumentException(
                    "memory.maxPromptBudgetTokens must be greater than or equal to memory.softPromptBudgetTokens");
        }
    }

    private void validateCompactionConfig(RuntimeConfig.CompactionConfig compactionConfig) {
        if (compactionConfig == null) {
            return;
        }

        String triggerMode = compactionConfig.getTriggerMode();
        if (triggerMode == null || triggerMode.isBlank()) {
            compactionConfig.setTriggerMode(DEFAULT_COMPACTION_TRIGGER_MODE);
        } else {
            String normalized = triggerMode.trim().toLowerCase(Locale.ROOT);
            if (!VALID_COMPACTION_TRIGGER_MODES.contains(normalized)) {
                throw new IllegalArgumentException(
                        "compaction.triggerMode must be one of " + VALID_COMPACTION_TRIGGER_MODES);
            }
            compactionConfig.setTriggerMode(normalized);
        }

        Double modelThresholdRatio = compactionConfig.getModelThresholdRatio();
        if (modelThresholdRatio == null) {
            compactionConfig.setModelThresholdRatio(DEFAULT_COMPACTION_MODEL_THRESHOLD_RATIO);
        } else if (modelThresholdRatio <= 0.0d || modelThresholdRatio > 1.0d) {
            throw new IllegalArgumentException("compaction.modelThresholdRatio must be between 0 and 1");
        }

        Integer maxContextTokens = compactionConfig.getMaxContextTokens();
        if (maxContextTokens != null && maxContextTokens < 1) {
            throw new IllegalArgumentException("compaction.maxContextTokens must be greater than 0");
        }

        Integer keepLastMessages = compactionConfig.getKeepLastMessages();
        if (keepLastMessages != null && keepLastMessages < 1) {
            throw new IllegalArgumentException("compaction.keepLastMessages must be greater than 0");
        }
    }

    private void validateNullableInteger(Integer value, int min, int max, String fieldName) {
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max);
        }
    }

    private void validateNullableDouble(Double value, double min, double max, String fieldName) {
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max);
        }
    }

    private void validateVoiceConfig(RuntimeConfig.VoiceConfig voiceConfig) {
        if (voiceConfig == null) {
            return;
        }
        boolean voiceEnabled = Boolean.TRUE.equals(voiceConfig.getEnabled());

        String normalizedSttProvider = normalizeProvider(voiceConfig.getSttProvider());
        if (normalizedSttProvider == null) {
            normalizedSttProvider = firstLoadedSttProvider();
        }
        if (normalizedSttProvider == null && voiceEnabled) {
            throw new IllegalArgumentException("voice.sttProvider must resolve to a loaded STT provider");
        }
        if (normalizedSttProvider != null && !isKnownSttProvider(normalizedSttProvider)) {
            throw new IllegalArgumentException("voice.sttProvider must resolve to a loaded STT provider");
        }
        voiceConfig.setSttProvider(normalizedSttProvider);

        String normalizedTtsProvider = normalizeProvider(voiceConfig.getTtsProvider());
        if (normalizedTtsProvider == null) {
            normalizedTtsProvider = firstLoadedTtsProvider();
        }
        if (normalizedTtsProvider == null && voiceEnabled) {
            throw new IllegalArgumentException("voice.ttsProvider must resolve to a loaded TTS provider");
        }
        if (normalizedTtsProvider != null && !isKnownTtsProvider(normalizedTtsProvider)) {
            throw new IllegalArgumentException("voice.ttsProvider must resolve to a loaded TTS provider");
        }
        voiceConfig.setTtsProvider(normalizedTtsProvider);

        String whisperSttUrl = voiceConfig.getWhisperSttUrl();
        if (whisperSttUrl != null && whisperSttUrl.isBlank()) {
            voiceConfig.setWhisperSttUrl(null);
        }
    }

    private void validatePlanConfig(RuntimeConfig.PlanConfig planConfig) {
        if (planConfig == null) {
            throw new IllegalArgumentException("plan config is required");
        }
        validateNullableInteger(planConfig.getMaxPlans(), 1, 100, "plan.maxPlans");
        validateNullableInteger(planConfig.getMaxStepsPerPlan(), 1, 1000, "plan.maxStepsPerPlan");
    }

    private void validateHiveConfig(RuntimeConfig.HiveConfig hiveConfig) {
        if (hiveConfig == null) {
            throw new IllegalArgumentException("hive config is required");
        }
        String serverUrl = hiveConfig.getServerUrl();
        if (serverUrl != null && !serverUrl.isBlank() && !isValidHttpUrl(serverUrl)) {
            throw new IllegalArgumentException("hive.serverUrl must be a valid http(s) URL");
        }
    }

    private RuntimeConfig.ToolsConfig ensureToolsConfig(RuntimeConfig config) {
        RuntimeConfig.ToolsConfig toolsConfig = config.getTools();
        if (toolsConfig == null) {
            toolsConfig = RuntimeConfig.ToolsConfig.builder().build();
            config.setTools(toolsConfig);
        }
        return toolsConfig;
    }

    private RuntimeConfig mergeRuntimeConfigSections(RuntimeConfig current, RuntimeConfig incoming) {
        RuntimeConfig baseline = current != null ? current : RuntimeConfig.builder().build();
        RuntimeConfig patch = incoming != null ? incoming : RuntimeConfig.builder().build();
        return RuntimeConfig.builder()
                .telegram(mergeSection(patch.getTelegram(), baseline.getTelegram(), RuntimeConfig.TelegramConfig::new))
                .modelRouter(mergeSection(patch.getModelRouter(), baseline.getModelRouter(),
                        RuntimeConfig.ModelRouterConfig::new))
                .llm(mergeSection(patch.getLlm(), baseline.getLlm(), RuntimeConfig.LlmConfig::new))
                .tools(mergeSection(patch.getTools(), baseline.getTools(), RuntimeConfig.ToolsConfig::new))
                .voice(mergeSection(patch.getVoice(), baseline.getVoice(), RuntimeConfig.VoiceConfig::new))
                .autoMode(mergeSection(patch.getAutoMode(), baseline.getAutoMode(), RuntimeConfig.AutoModeConfig::new))
                .rateLimit(mergeSection(patch.getRateLimit(), baseline.getRateLimit(),
                        RuntimeConfig.RateLimitConfig::new))
                .security(mergeSection(patch.getSecurity(), baseline.getSecurity(), RuntimeConfig.SecurityConfig::new))
                .compaction(mergeSection(patch.getCompaction(), baseline.getCompaction(),
                        RuntimeConfig.CompactionConfig::new))
                .turn(mergeSection(patch.getTurn(), baseline.getTurn(), RuntimeConfig.TurnConfig::new))
                .memory(mergeSection(patch.getMemory(), baseline.getMemory(), RuntimeConfig.MemoryConfig::new))
                .skills(mergeSection(patch.getSkills(), baseline.getSkills(), RuntimeConfig.SkillsConfig::new))
                .usage(mergeSection(patch.getUsage(), baseline.getUsage(), RuntimeConfig.UsageConfig::new))
                .mcp(mergeSection(patch.getMcp(), baseline.getMcp(), RuntimeConfig.McpConfig::new))
                .plan(mergeSection(patch.getPlan(), baseline.getPlan(), RuntimeConfig.PlanConfig::new))
                .delayedActions(mergeSection(patch.getDelayedActions(), baseline.getDelayedActions(),
                        RuntimeConfig.DelayedActionsConfig::new))
                .hive(mergeSection(patch.getHive(), baseline.getHive(), RuntimeConfig.HiveConfig::new))
                .build();
    }

    private <T> T mergeSection(T incoming, T current, Supplier<T> emptySupplier) {
        if (incoming == null) {
            return current;
        }
        if (current == null) {
            return incoming;
        }
        return incoming.equals(emptySupplier.get()) ? current : incoming;
    }

    private List<RuntimeConfig.ShellEnvironmentVariable> ensureShellEnvironmentVariables(
            RuntimeConfig.ToolsConfig toolsConfig) {
        List<RuntimeConfig.ShellEnvironmentVariable> variables = toolsConfig.getShellEnvironmentVariables();
        if (variables == null) {
            variables = new ArrayList<>();
            toolsConfig.setShellEnvironmentVariables(variables);
        }
        return variables;
    }

    private void normalizeAndValidateShellEnvironmentVariables(RuntimeConfig.ToolsConfig toolsConfig) {
        if (toolsConfig == null) {
            return;
        }
        List<RuntimeConfig.ShellEnvironmentVariable> variables = toolsConfig.getShellEnvironmentVariables();
        if (variables == null || variables.isEmpty()) {
            toolsConfig.setShellEnvironmentVariables(new ArrayList<>());
            return;
        }

        List<RuntimeConfig.ShellEnvironmentVariable> normalized = new ArrayList<>(variables.size());
        Set<String> names = new LinkedHashSet<>();
        for (RuntimeConfig.ShellEnvironmentVariable variable : variables) {
            RuntimeConfig.ShellEnvironmentVariable normalizedVariable = normalizeAndValidateShellEnvironmentVariable(
                    variable);
            if (!names.add(normalizedVariable.getName())) {
                throw new IllegalArgumentException(
                        "tools.shellEnvironmentVariables contains duplicate name: " + normalizedVariable.getName());
            }
            normalized.add(normalizedVariable);
        }
        toolsConfig.setShellEnvironmentVariables(normalized);
    }

    private RuntimeConfig.ShellEnvironmentVariable normalizeAndValidateShellEnvironmentVariable(
            RuntimeConfig.ShellEnvironmentVariable variable) {
        if (variable == null) {
            throw new IllegalArgumentException("tools.shellEnvironmentVariables item is required");
        }
        String normalizedName = normalizeAndValidateShellEnvironmentVariableName(variable.getName());
        String normalizedValue = normalizeAndValidateShellEnvironmentVariableValue(variable.getValue(), normalizedName);
        return RuntimeConfig.ShellEnvironmentVariable.builder()
                .name(normalizedName)
                .value(normalizedValue)
                .build();
    }

    private String normalizeAndValidateShellEnvironmentVariableName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tools.shellEnvironmentVariables.name is required");
        }
        String normalized = value.trim();
        if (normalized.length() > SHELL_ENV_VAR_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("tools.shellEnvironmentVariables.name must be at most "
                    + SHELL_ENV_VAR_NAME_MAX_LENGTH + " characters");
        }
        if (!SHELL_ENV_VAR_NAME_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "tools.shellEnvironmentVariables.name must match [A-Za-z_][A-Za-z0-9_]*");
        }
        if (RESERVED_SHELL_ENV_VAR_NAMES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "tools.shellEnvironmentVariables.name must not redefine reserved variable: " + normalized);
        }
        return normalized;
    }

    private String normalizeAndValidateShellEnvironmentVariableValue(String value, String normalizedName) {
        String normalizedValue = value != null ? value : "";
        if (normalizedValue.length() > SHELL_ENV_VAR_VALUE_MAX_LENGTH) {
            throw new IllegalArgumentException("tools.shellEnvironmentVariables." + normalizedName
                    + ".value must be at most " + SHELL_ENV_VAR_VALUE_MAX_LENGTH + " characters");
        }
        return normalizedValue;
    }

    private boolean containsShellEnvironmentVariableName(List<RuntimeConfig.ShellEnvironmentVariable> variables,
            String name) {
        return findShellEnvironmentVariableIndex(variables, name) >= 0;
    }

    private int findShellEnvironmentVariableIndex(List<RuntimeConfig.ShellEnvironmentVariable> variables, String name) {
        for (int index = 0; index < variables.size(); index++) {
            RuntimeConfig.ShellEnvironmentVariable variable = variables.get(index);
            if (variable != null && name.equals(variable.getName())) {
                return index;
            }
        }
        return -1;
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
            incoming.getVoice().setWhisperSttApiKey(
                    mergeSecret(current.getVoice().getWhisperSttApiKey(), incoming.getVoice().getWhisperSttApiKey()));
        }
        mergeLlmSecrets(current.getLlm(), incoming.getLlm());
    }

    private void rejectManagedHiveMutation(RuntimeConfig current, RuntimeConfig.HiveConfig incomingHiveConfig) {
        if (current == null || !runtimeConfigService.isHiveManagedByProperties()) {
            return;
        }
        if (incomingHiveConfig == null) {
            return;
        }
        RuntimeConfig.HiveConfig currentHiveConfig = current.getHive();
        RuntimeConfig.HiveConfig normalizedCurrentHiveConfig = currentHiveConfig != null
                ? currentHiveConfig
                : RuntimeConfig.HiveConfig.builder().build();
        if (!incomingHiveConfig.equals(normalizedCurrentHiveConfig)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Hive settings are managed by bot.hive.* and are read-only");
        }
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

    private String normalizeProvider(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (LEGACY_STT_PROVIDER_ELEVENLABS.equals(normalized)) {
            return STT_PROVIDER_ELEVENLABS;
        }
        if (LEGACY_STT_PROVIDER_WHISPER.equals(normalized)) {
            return STT_PROVIDER_WHISPER;
        }
        return normalized;
    }

    private boolean isKnownSttProvider(String providerId) {
        return providerId != null && sttProviderRegistry.find(providerId).isPresent();
    }

    private boolean isKnownTtsProvider(String providerId) {
        return providerId != null && ttsProviderRegistry.find(providerId).isPresent();
    }

    private String firstLoadedSttProvider() {
        return sttProviderRegistry.listProviderIds().keySet().stream().findFirst().orElse(null);
    }

    private String firstLoadedTtsProvider() {
        return ttsProviderRegistry.listProviderIds().keySet().stream().findFirst().orElse(null);
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
