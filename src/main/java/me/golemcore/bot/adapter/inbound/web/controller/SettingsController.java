package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.PreferencesUpdateRequest;
import me.golemcore.bot.adapter.inbound.web.dto.SettingsResponse;
import me.golemcore.bot.application.settings.RuntimeSettingsFacade;
import me.golemcore.bot.domain.model.MemoryPreset;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * Settings and preferences management endpoints.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final UserPreferencesService preferencesService;
    private final ModelSelectionService modelSelectionService;
    private final RuntimeSettingsFacade runtimeSettingsFacade;

    public SettingsController(UserPreferencesService preferencesService,
            ModelSelectionService modelSelectionService,
            RuntimeSettingsFacade runtimeSettingsFacade) {
        this.preferencesService = preferencesService;
        this.modelSelectionService = modelSelectionService;
        this.runtimeSettingsFacade = runtimeSettingsFacade;
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
            prefs.setModelTier(normalizeOptionalSelectableTier(request.getModelTier(), "modelTier"));
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
        overrides.forEach((tier, dto) -> tierOverrides.put(
                normalizeRequiredSelectableTier(tier, "tierOverrides key"),
                new UserPreferences.TierOverride(dto.getModel(), dto.getReasoning())));
        prefs.setTierOverrides(tierOverrides);
        preferencesService.savePreferences(prefs);
        return getSettings();
    }

    @GetMapping("/runtime")
    public Mono<ResponseEntity<RuntimeConfig>> getRuntimeConfig() {
        return Mono.just(ResponseEntity.ok(runtimeSettingsFacade.getRuntimeConfigForApi()));
    }

    @PutMapping("/runtime")
    public Mono<ResponseEntity<RuntimeConfig>> updateRuntimeConfig(@RequestBody RuntimeConfig config) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateRuntimeConfig(config));
    }

    @PutMapping("/runtime/models")
    public Mono<ResponseEntity<RuntimeConfig>> updateModelRouterConfig(
            @RequestBody RuntimeConfig.ModelRouterConfig modelRouterConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateModelRouterConfig(modelRouterConfig));
    }

    @PutMapping("/runtime/llm")
    public Mono<ResponseEntity<RuntimeConfig>> updateLlmConfig(@RequestBody RuntimeConfig.LlmConfig llmConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateLlmConfig(llmConfig));
    }

    @PostMapping("/runtime/llm/providers/{name}")
    public Mono<ResponseEntity<RuntimeConfig>> addLlmProvider(
            @PathVariable String name,
            @RequestBody RuntimeConfig.LlmProviderConfig providerConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.addLlmProvider(name, providerConfig));
    }

    @PutMapping("/runtime/llm/providers/{name}")
    public Mono<ResponseEntity<RuntimeConfig>> updateLlmProvider(
            @PathVariable String name,
            @RequestBody RuntimeConfig.LlmProviderConfig providerConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateLlmProvider(name, providerConfig));
    }

    @DeleteMapping("/runtime/llm/providers/{name}")
    public Mono<ResponseEntity<Void>> removeLlmProvider(@PathVariable String name) {
        return runtimeVoidResponse(() -> runtimeSettingsFacade.removeLlmProvider(name));
    }

    @PutMapping("/runtime/tools")
    public Mono<ResponseEntity<RuntimeConfig>> updateToolsConfig(@RequestBody RuntimeConfig.ToolsConfig toolsConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateToolsConfig(toolsConfig));
    }

    @GetMapping("/runtime/tools/shell/env")
    public Mono<ResponseEntity<List<RuntimeConfig.ShellEnvironmentVariable>>> getShellEnvironmentVariables() {
        return runtimeListResponse(runtimeSettingsFacade::getShellEnvironmentVariables);
    }

    @PostMapping("/runtime/tools/shell/env")
    public Mono<ResponseEntity<RuntimeConfig>> createShellEnvironmentVariable(
            @RequestBody RuntimeConfig.ShellEnvironmentVariable variable) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.createShellEnvironmentVariable(variable));
    }

    @PutMapping("/runtime/tools/shell/env/{name}")
    public Mono<ResponseEntity<RuntimeConfig>> updateShellEnvironmentVariable(
            @PathVariable String name,
            @RequestBody RuntimeConfig.ShellEnvironmentVariable variable) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateShellEnvironmentVariable(name, variable));
    }

    @DeleteMapping("/runtime/tools/shell/env/{name}")
    public Mono<ResponseEntity<RuntimeConfig>> deleteShellEnvironmentVariable(@PathVariable String name) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.deleteShellEnvironmentVariable(name));
    }

    @PutMapping("/runtime/voice")
    public Mono<ResponseEntity<RuntimeConfig>> updateVoiceConfig(@RequestBody RuntimeConfig.VoiceConfig voiceConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateVoiceConfig(voiceConfig));
    }

    @PutMapping("/runtime/turn")
    public Mono<ResponseEntity<RuntimeConfig>> updateTurnConfig(@RequestBody RuntimeConfig.TurnConfig turnConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateTurnConfig(turnConfig));
    }

    @PutMapping("/runtime/memory")
    public Mono<ResponseEntity<RuntimeConfig>> updateMemoryConfig(
            @RequestBody RuntimeConfig.MemoryConfig memoryConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateMemoryConfig(memoryConfig));
    }

    @GetMapping("/runtime/memory/presets")
    public Mono<ResponseEntity<List<MemoryPreset>>> getMemoryPresets() {
        return runtimeListResponse(runtimeSettingsFacade::getMemoryPresets);
    }

    @PutMapping("/runtime/skills")
    public Mono<ResponseEntity<RuntimeConfig>> updateSkillsConfig(
            @RequestBody RuntimeConfig.SkillsConfig skillsConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateSkillsConfig(skillsConfig));
    }

    @PutMapping("/runtime/usage")
    public Mono<ResponseEntity<RuntimeConfig>> updateUsageConfig(@RequestBody RuntimeConfig.UsageConfig usageConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateUsageConfig(usageConfig));
    }

    @PutMapping("/runtime/telemetry")
    public Mono<ResponseEntity<RuntimeConfig>> updateTelemetryConfig(
            @RequestBody RuntimeConfig.TelemetryConfig telemetryConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateTelemetryConfig(telemetryConfig));
    }

    @PutMapping("/runtime/mcp")
    public Mono<ResponseEntity<RuntimeConfig>> updateMcpConfig(@RequestBody RuntimeConfig.McpConfig mcpConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateMcpConfig(mcpConfig));
    }

    @GetMapping("/runtime/mcp/catalog")
    public Mono<ResponseEntity<List<RuntimeConfig.McpCatalogEntry>>> getMcpCatalog() {
        return runtimeListResponse(runtimeSettingsFacade::getMcpCatalog);
    }

    @PostMapping("/runtime/mcp/catalog")
    public Mono<ResponseEntity<RuntimeConfig>> addMcpCatalogEntry(@RequestBody RuntimeConfig.McpCatalogEntry entry) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.addMcpCatalogEntry(entry));
    }

    @PutMapping("/runtime/mcp/catalog/{name}")
    public Mono<ResponseEntity<RuntimeConfig>> updateMcpCatalogEntry(
            @PathVariable String name,
            @RequestBody RuntimeConfig.McpCatalogEntry entry) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateMcpCatalogEntry(name, entry));
    }

    @DeleteMapping("/runtime/mcp/catalog/{name}")
    public Mono<ResponseEntity<Void>> removeMcpCatalogEntry(@PathVariable String name) {
        return runtimeVoidResponse(() -> runtimeSettingsFacade.removeMcpCatalogEntry(name));
    }

    @PutMapping("/runtime/hive")
    public Mono<ResponseEntity<RuntimeConfig>> updateHiveConfig(@RequestBody RuntimeConfig.HiveConfig hiveConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateHiveConfig(hiveConfig));
    }

    @PutMapping("/runtime/plan")
    public Mono<ResponseEntity<RuntimeConfig>> updatePlanConfig(@RequestBody RuntimeConfig.PlanConfig planConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updatePlanConfig(planConfig));
    }

    @PutMapping("/runtime/webhooks")
    public Mono<ResponseEntity<Void>> updateWebhooksConfig(@RequestBody UserPreferences.WebhookConfig webhookConfig) {
        return runtimeVoidResponse(() -> runtimeSettingsFacade.updateWebhooksConfig(webhookConfig));
    }

    @PutMapping("/runtime/auto")
    public Mono<ResponseEntity<RuntimeConfig>> updateAutoConfig(@RequestBody RuntimeConfig.AutoModeConfig autoConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateAutoConfig(autoConfig));
    }

    @PutMapping("/runtime/tracing")
    public Mono<ResponseEntity<RuntimeConfig>> updateTracingConfig(
            @RequestBody RuntimeConfig.TracingConfig tracingConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateTracingConfig(tracingConfig));
    }

    @PutMapping("/runtime/advanced")
    public Mono<ResponseEntity<RuntimeConfig>> updateAdvancedConfig(@RequestBody AdvancedConfigRequest request) {
        return runtimeConfigResponse(() -> runtimeSettingsFacade.updateAdvancedConfig(
                request.rateLimit(),
                request.security(),
                request.compaction()));
    }

    private Mono<ResponseEntity<RuntimeConfig>> runtimeConfigResponse(Supplier<RuntimeConfig> supplier) {
        return Mono.just(ResponseEntity.ok(invokeRuntime(supplier)));
    }

    private <T> Mono<ResponseEntity<List<T>>> runtimeListResponse(Supplier<List<T>> supplier) {
        return Mono.just(ResponseEntity.ok(invokeRuntime(supplier)));
    }

    private Mono<ResponseEntity<Void>> runtimeVoidResponse(Runnable runnable) {
        invokeRuntime(() -> {
            runnable.run();
            return null;
        });
        return Mono.just(ResponseEntity.ok().build());
    }

    private <T> T invokeRuntime(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    private String normalizeOptionalSelectableTier(String tier, String fieldName) {
        String normalizedTier = ModelTierCatalog.normalizeTierId(tier);
        if (normalizedTier == null) {
            return null;
        }
        if ("default".equals(normalizedTier)) {
            return null;
        }
        return normalizeRequiredSelectableTier(normalizedTier, fieldName);
    }

    private String normalizeRequiredSelectableTier(String tier, String fieldName) {
        String normalizedTier = ModelTierCatalog.normalizeTierId(tier);
        if (!ModelTierCatalog.isExplicitSelectableTier(normalizedTier)) {
            throw new IllegalArgumentException(fieldName + " must be a known tier id");
        }
        return normalizedTier;
    }

    private record ModelDto(String id, String displayName, boolean hasReasoning,
            List<String> reasoningLevels, boolean supportsVision) {
    }

    private record AdvancedConfigRequest(
            RuntimeConfig.RateLimitConfig rateLimit,
            RuntimeConfig.SecurityConfig security,
            RuntimeConfig.CompactionConfig compaction) {
    }
}
