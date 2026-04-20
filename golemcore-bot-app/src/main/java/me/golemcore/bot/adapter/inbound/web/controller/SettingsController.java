package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.client.dto.PreferencesUpdateRequest;
import me.golemcore.bot.client.dto.SettingsResponse;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.AutoModeConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.CompactionConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.HiveConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.LlmConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.LlmProviderConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.McpCatalogEntryDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.McpConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.MemoryConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.ModelRouterConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.PlanConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.RateLimitConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.ResilienceConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.RuntimeConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.SecurityConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.SessionRetentionConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.ShellEnvironmentVariableDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.SkillsConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.TelemetryConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.ToolsConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.TracingConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.TurnConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.UsageConfigDto;
import me.golemcore.bot.client.dto.settings.RuntimeSettingsWebDtos.VoiceConfigDto;
import me.golemcore.bot.client.mapper.RuntimeSettingsWebMapper;
import me.golemcore.bot.application.models.ProviderModelImportService;
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
    private final RuntimeSettingsWebMapper runtimeSettingsWebMapper;

    public SettingsController(UserPreferencesService preferencesService,
            ModelSelectionService modelSelectionService,
            RuntimeSettingsFacade runtimeSettingsFacade,
            RuntimeSettingsWebMapper runtimeSettingsWebMapper) {
        this.preferencesService = preferencesService;
        this.modelSelectionService = modelSelectionService;
        this.runtimeSettingsFacade = runtimeSettingsFacade;
        this.runtimeSettingsWebMapper = runtimeSettingsWebMapper;
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
                .memoryPreset(prefs.getMemoryPreset())
                .tierOverrides(overrideDtos)
                .webhooks(prefs.getWebhooks())
                .build();
        return Mono.just(ResponseEntity.ok(response));
    }

    @PutMapping("/preferences")
    public Mono<ResponseEntity<SettingsResponse>> updatePreferences(
            @RequestBody PreferencesUpdateRequest request) {
        boolean hasModelTier = request.getModelTier() != null;
        boolean hasMemoryPreset = request.getMemoryPreset() != null;
        String normalizedModelTier = hasModelTier
                ? normalizeOptionalSelectableTier(request.getModelTier(), "modelTier")
                : null;
        String normalizedMemoryPreset = hasMemoryPreset
                ? normalizeOptionalMemoryPreset(request.getMemoryPreset(), "memoryPreset")
                : null;

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
        if (hasModelTier) {
            prefs.setModelTier(normalizedModelTier);
        }
        if (request.getTierForce() != null) {
            prefs.setTierForce(request.getTierForce());
        }
        if (hasMemoryPreset) {
            prefs.setMemoryPreset(normalizedMemoryPreset);
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
    public Mono<ResponseEntity<RuntimeConfigDto>> getRuntimeConfig() {
        return Mono.just(ResponseEntity.ok(runtimeSettingsWebMapper
                .toRuntimeConfigDto(runtimeSettingsFacade.getRuntimeConfigForApi())));
    }

    @PutMapping("/runtime")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateRuntimeConfig(@RequestBody RuntimeConfigDto config) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateRuntimeConfig(runtimeSettingsWebMapper.toRuntimeConfig(config))));
    }

    @PutMapping("/runtime/models")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateModelRouterConfig(
            @RequestBody ModelRouterConfigDto modelRouterConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateModelRouterConfig(runtimeSettingsWebMapper.toModelRouterConfig(
                        modelRouterConfig))));
    }

    @PutMapping("/runtime/llm")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateLlmConfig(@RequestBody LlmConfigDto llmConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateLlmConfig(runtimeSettingsWebMapper.toLlmConfig(llmConfig))));
    }

    @PostMapping("/runtime/llm/providers/{name}")
    public Mono<ResponseEntity<RuntimeConfigDto>> addLlmProvider(
            @PathVariable String name,
            @RequestBody LlmProviderConfigDto providerConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.addLlmProvider(name,
                        runtimeSettingsWebMapper.toLlmProviderConfig(providerConfig))));
    }

    @PostMapping("/runtime/llm/providers/{name}/import-models")
    public Mono<ResponseEntity<LlmProviderImportResponse>> addLlmProviderAndImportModels(
            @PathVariable String name,
            @RequestBody LlmProviderImportRequest request) {
        if (request == null || request.config() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "config is required");
        }
        return Mono.just(ResponseEntity.ok(invokeRuntime(() -> {
            ProviderModelImportService.ProviderImportResult importResult = runtimeSettingsFacade
                    .addLlmProviderAndImportModels(name, request.config(), request.selectedModelIds());
            return new LlmProviderImportResponse(
                    true,
                    name.trim().toLowerCase(Locale.ROOT),
                    importResult.resolvedEndpoint(),
                    importResult.addedModels(),
                    importResult.skippedModels(),
                    importResult.errors());
        })));
    }

    @PutMapping("/runtime/llm/providers/{name}")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateLlmProvider(
            @PathVariable String name,
            @RequestBody LlmProviderConfigDto providerConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateLlmProvider(name,
                        runtimeSettingsWebMapper.toLlmProviderConfig(providerConfig))));
    }

    @PostMapping("/runtime/llm/provider-tests")
    public Mono<ResponseEntity<LlmProviderTestResponse>> testLlmProvider(@RequestBody LlmProviderTestRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        String mode = requireRequestValue(request.mode(), "mode").toLowerCase(Locale.ROOT);
        String providerName = requireRequestValue(request.providerName(), "providerName");
        try {
            RuntimeSettingsFacade.LlmProviderTestResult testResult;
            if ("saved".equals(mode)) {
                testResult = runtimeSettingsFacade.testSavedLlmProvider(providerName);
            } else if ("draft".equals(mode)) {
                if (request.config() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "config is required");
                }
                testResult = runtimeSettingsFacade.testDraftLlmProvider(providerName, request.config());
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be one of [saved, draft]");
            }
            return Mono.just(ResponseEntity.ok(new LlmProviderTestResponse(
                    testResult.mode(),
                    testResult.providerName(),
                    testResult.resolvedEndpoint(),
                    testResult.models(),
                    testResult.success(),
                    testResult.error())));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/runtime/llm/providers/{name}")
    public Mono<ResponseEntity<Void>> removeLlmProvider(@PathVariable String name) {
        return runtimeVoidResponse(() -> runtimeSettingsFacade.removeLlmProvider(name));
    }

    @PutMapping("/runtime/tools")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateToolsConfig(@RequestBody ToolsConfigDto toolsConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateToolsConfig(runtimeSettingsWebMapper.toToolsConfig(toolsConfig))));
    }

    @GetMapping("/runtime/tools/shell/env")
    public Mono<ResponseEntity<List<ShellEnvironmentVariableDto>>> getShellEnvironmentVariables() {
        return runtimeListResponse(() -> runtimeSettingsFacade.getShellEnvironmentVariables().stream()
                .map(runtimeSettingsWebMapper::toShellEnvironmentVariableDto)
                .toList());
    }

    @PostMapping("/runtime/tools/shell/env")
    public Mono<ResponseEntity<RuntimeConfigDto>> createShellEnvironmentVariable(
            @RequestBody ShellEnvironmentVariableDto variable) {
        return Mono.just(ResponseEntity.ok(runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.createShellEnvironmentVariable(
                        runtimeSettingsWebMapper.toShellEnvironmentVariable(variable)))));
    }

    @PutMapping("/runtime/tools/shell/env/{name}")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateShellEnvironmentVariable(
            @PathVariable String name,
            @RequestBody ShellEnvironmentVariableDto variable) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateShellEnvironmentVariable(name,
                        runtimeSettingsWebMapper.toShellEnvironmentVariable(variable))));
    }

    @DeleteMapping("/runtime/tools/shell/env/{name}")
    public Mono<ResponseEntity<RuntimeConfigDto>> deleteShellEnvironmentVariable(@PathVariable String name) {
        try {
            return Mono.just(ResponseEntity.ok(runtimeSettingsWebMapper.toRuntimeConfigDto(
                    runtimeSettingsFacade.deleteShellEnvironmentVariable(name))));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PutMapping("/runtime/voice")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateVoiceConfig(@RequestBody VoiceConfigDto voiceConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateVoiceConfig(runtimeSettingsWebMapper.toVoiceConfig(voiceConfig))));
    }

    @PutMapping("/runtime/turn")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateTurnConfig(@RequestBody TurnConfigDto turnConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateTurnConfig(runtimeSettingsWebMapper.toTurnConfig(turnConfig))));
    }

    @PutMapping("/runtime/memory")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateMemoryConfig(
            @RequestBody MemoryConfigDto memoryConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateMemoryConfig(runtimeSettingsWebMapper.toMemoryConfig(memoryConfig))));
    }

    @PutMapping("/runtime/session-retention")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateSessionRetentionConfig(
            @RequestBody SessionRetentionConfigDto sessionRetentionConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateSessionRetentionConfig(
                        runtimeSettingsWebMapper.toSessionRetentionConfig(sessionRetentionConfig))));
    }

    @GetMapping("/runtime/memory/presets")
    public Mono<ResponseEntity<List<MemoryPreset>>> getMemoryPresets() {
        return runtimeListResponse(runtimeSettingsFacade::getMemoryPresets);
    }

    @PutMapping("/runtime/skills")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateSkillsConfig(
            @RequestBody SkillsConfigDto skillsConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateSkillsConfig(runtimeSettingsWebMapper.toSkillsConfig(skillsConfig))));
    }

    @PutMapping("/runtime/usage")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateUsageConfig(@RequestBody UsageConfigDto usageConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateUsageConfig(runtimeSettingsWebMapper.toUsageConfig(usageConfig))));
    }

    @PutMapping("/runtime/telemetry")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateTelemetryConfig(
            @RequestBody TelemetryConfigDto telemetryConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade
                        .updateTelemetryConfig(runtimeSettingsWebMapper.toTelemetryConfig(telemetryConfig))));
    }

    @PutMapping("/runtime/mcp")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateMcpConfig(@RequestBody McpConfigDto mcpConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateMcpConfig(runtimeSettingsWebMapper.toMcpConfig(mcpConfig))));
    }

    @GetMapping("/runtime/mcp/catalog")
    public Mono<ResponseEntity<List<McpCatalogEntryDto>>> getMcpCatalog() {
        return runtimeListResponse(() -> runtimeSettingsFacade.getMcpCatalog().stream()
                .map(runtimeSettingsWebMapper::toMcpCatalogEntryDto)
                .toList());
    }

    @PostMapping("/runtime/mcp/catalog")
    public Mono<ResponseEntity<RuntimeConfigDto>> addMcpCatalogEntry(@RequestBody McpCatalogEntryDto entry) {
        return Mono.just(ResponseEntity.ok(runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.addMcpCatalogEntry(runtimeSettingsWebMapper.toMcpCatalogEntry(entry)))));
    }

    @PutMapping("/runtime/mcp/catalog/{name}")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateMcpCatalogEntry(
            @PathVariable String name,
            @RequestBody McpCatalogEntryDto entry) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateMcpCatalogEntry(name, runtimeSettingsWebMapper.toMcpCatalogEntry(entry))));
    }

    @DeleteMapping("/runtime/mcp/catalog/{name}")
    public Mono<ResponseEntity<Void>> removeMcpCatalogEntry(@PathVariable String name) {
        try {
            runtimeSettingsFacade.removeMcpCatalogEntry(name);
            return Mono.just(ResponseEntity.ok().build());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PutMapping("/runtime/hive")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateHiveConfig(@RequestBody HiveConfigDto hiveConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateHiveConfig(runtimeSettingsWebMapper.toHiveConfig(hiveConfig))));
    }

    @PutMapping("/runtime/plan")
    public Mono<ResponseEntity<RuntimeConfigDto>> updatePlanConfig(@RequestBody PlanConfigDto planConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updatePlanConfig(runtimeSettingsWebMapper.toPlanConfig(planConfig))));
    }

    @PutMapping("/runtime/webhooks")
    public Mono<ResponseEntity<Void>> updateWebhooksConfig(@RequestBody UserPreferences.WebhookConfig webhookConfig) {
        runtimeSettingsFacade.updateWebhooksConfig(webhookConfig);
        return Mono.just(ResponseEntity.ok().build());
    }

    @PutMapping("/runtime/auto")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateAutoConfig(@RequestBody AutoModeConfigDto autoConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateAutoConfig(runtimeSettingsWebMapper.toAutoModeConfig(autoConfig))));
    }

    @PutMapping("/runtime/tracing")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateTracingConfig(
            @RequestBody TracingConfigDto tracingConfig) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateTracingConfig(runtimeSettingsWebMapper.toTracingConfig(tracingConfig))));
    }

    @PutMapping("/runtime/advanced")
    public Mono<ResponseEntity<RuntimeConfigDto>> updateAdvancedConfig(@RequestBody AdvancedConfigRequest request) {
        return runtimeConfigResponse(() -> runtimeSettingsWebMapper.toRuntimeConfigDto(
                runtimeSettingsFacade.updateAdvancedConfig(
                        runtimeSettingsWebMapper.toRateLimitConfig(request.rateLimit()),
                        runtimeSettingsWebMapper.toSecurityConfig(request.security()),
                        runtimeSettingsWebMapper.toCompactionConfig(request.compaction()),
                        runtimeSettingsWebMapper.toResilienceConfig(request.resilience()))));
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateRuntimeConfig(RuntimeConfig config) {
        try {
            return Mono.just(ResponseEntity.ok(
                    runtimeSettingsWebMapper.toRuntimeConfigDto(runtimeSettingsFacade.updateRuntimeConfig(config))));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateModelRouterConfig(
            RuntimeConfig.ModelRouterConfig modelRouterConfig) {
        try {
            return Mono.just(ResponseEntity.ok(runtimeSettingsWebMapper
                    .toRuntimeConfigDto(runtimeSettingsFacade.updateModelRouterConfig(modelRouterConfig))));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateLlmConfig(RuntimeConfig.LlmConfig llmConfig) {
        try {
            return Mono.just(ResponseEntity
                    .ok(runtimeSettingsWebMapper.toRuntimeConfigDto(runtimeSettingsFacade.updateLlmConfig(llmConfig))));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> addLlmProvider(String name,
            RuntimeConfig.LlmProviderConfig providerConfig) {
        return Mono.just(ResponseEntity.ok(runtimeSettingsWebMapper
                .toRuntimeConfigDto(runtimeSettingsFacade.addLlmProvider(name, providerConfig))));
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateLlmProvider(String name,
            RuntimeConfig.LlmProviderConfig providerConfig) {
        return Mono.just(ResponseEntity.ok(runtimeSettingsWebMapper
                .toRuntimeConfigDto(runtimeSettingsFacade.updateLlmProvider(name, providerConfig))));
    }

    public Mono<ResponseEntity<Void>> removeLlmProvider(String name, boolean unused) {
        runtimeSettingsFacade.removeLlmProvider(name);
        return Mono.just(ResponseEntity.ok().build());
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateToolsConfig(RuntimeConfig.ToolsConfig toolsConfig) {
        return Mono.just(ResponseEntity
                .ok(runtimeSettingsWebMapper.toRuntimeConfigDto(runtimeSettingsFacade.updateToolsConfig(toolsConfig))));
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> createShellEnvironmentVariable(
            RuntimeConfig.ShellEnvironmentVariable variable) {
        return Mono.just(ResponseEntity.ok(runtimeSettingsWebMapper
                .toRuntimeConfigDto(runtimeSettingsFacade.createShellEnvironmentVariable(variable))));
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateShellEnvironmentVariable(String name,
            RuntimeConfig.ShellEnvironmentVariable variable) {
        try {
            return Mono.just(ResponseEntity.ok(runtimeSettingsWebMapper
                    .toRuntimeConfigDto(runtimeSettingsFacade.updateShellEnvironmentVariable(name, variable))));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> deleteShellEnvironmentVariable(String name, boolean unused) {
        try {
            return Mono.just(ResponseEntity.ok(runtimeSettingsWebMapper
                    .toRuntimeConfigDto(runtimeSettingsFacade.deleteShellEnvironmentVariable(name))));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateVoiceConfig(RuntimeConfig.VoiceConfig voiceConfig) {
        return Mono.just(ResponseEntity
                .ok(runtimeSettingsWebMapper.toRuntimeConfigDto(runtimeSettingsFacade.updateVoiceConfig(voiceConfig))));
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateTurnConfig(RuntimeConfig.TurnConfig turnConfig) {
        return Mono.just(ResponseEntity
                .ok(runtimeSettingsWebMapper.toRuntimeConfigDto(runtimeSettingsFacade.updateTurnConfig(turnConfig))));
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateMemoryConfig(RuntimeConfig.MemoryConfig memoryConfig) {
        return Mono.just(ResponseEntity.ok(
                runtimeSettingsWebMapper.toRuntimeConfigDto(runtimeSettingsFacade.updateMemoryConfig(memoryConfig))));
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateSkillsConfig(RuntimeConfig.SkillsConfig skillsConfig) {
        return Mono.just(ResponseEntity.ok(
                runtimeSettingsWebMapper.toRuntimeConfigDto(runtimeSettingsFacade.updateSkillsConfig(skillsConfig))));
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateUsageConfig(RuntimeConfig.UsageConfig usageConfig) {
        return Mono.just(ResponseEntity
                .ok(runtimeSettingsWebMapper.toRuntimeConfigDto(runtimeSettingsFacade.updateUsageConfig(usageConfig))));
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateTelemetryConfig(RuntimeConfig.TelemetryConfig telemetryConfig) {
        return Mono.just(ResponseEntity.ok(runtimeSettingsWebMapper
                .toRuntimeConfigDto(runtimeSettingsFacade.updateTelemetryConfig(telemetryConfig))));
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateMcpConfig(RuntimeConfig.McpConfig mcpConfig) {
        return Mono.just(ResponseEntity
                .ok(runtimeSettingsWebMapper.toRuntimeConfigDto(runtimeSettingsFacade.updateMcpConfig(mcpConfig))));
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> addMcpCatalogEntry(RuntimeConfig.McpCatalogEntry entry) {
        return Mono.just(ResponseEntity
                .ok(runtimeSettingsWebMapper.toRuntimeConfigDto(runtimeSettingsFacade.addMcpCatalogEntry(entry))));
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateMcpCatalogEntry(String name,
            RuntimeConfig.McpCatalogEntry entry) {
        try {
            return Mono.just(ResponseEntity.ok(runtimeSettingsWebMapper
                    .toRuntimeConfigDto(runtimeSettingsFacade.updateMcpCatalogEntry(name, entry))));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    public Mono<ResponseEntity<Void>> removeMcpCatalogEntry(String name, int unused) {
        runtimeSettingsFacade.removeMcpCatalogEntry(name);
        return Mono.just(ResponseEntity.ok().build());
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateHiveConfig(RuntimeConfig.HiveConfig hiveConfig) {
        try {
            return Mono.just(ResponseEntity.ok(
                    runtimeSettingsWebMapper.toRuntimeConfigDto(runtimeSettingsFacade.updateHiveConfig(hiveConfig))));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updatePlanConfig(RuntimeConfig.PlanConfig planConfig) {
        return Mono.just(ResponseEntity
                .ok(runtimeSettingsWebMapper.toRuntimeConfigDto(runtimeSettingsFacade.updatePlanConfig(planConfig))));
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateAutoConfig(RuntimeConfig.AutoModeConfig autoConfig) {
        return Mono.just(ResponseEntity
                .ok(runtimeSettingsWebMapper.toRuntimeConfigDto(runtimeSettingsFacade.updateAutoConfig(autoConfig))));
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateTracingConfig(RuntimeConfig.TracingConfig tracingConfig) {
        return Mono.just(ResponseEntity.ok(
                runtimeSettingsWebMapper.toRuntimeConfigDto(runtimeSettingsFacade.updateTracingConfig(tracingConfig))));
    }

    public Mono<ResponseEntity<RuntimeConfigDto>> updateAdvancedConfig(RuntimeConfig.RateLimitConfig rateLimit,
            RuntimeConfig.SecurityConfig security, RuntimeConfig.CompactionConfig compaction,
            RuntimeConfig.ResilienceConfig resilience) {
        return Mono.just(ResponseEntity.ok(
                runtimeSettingsWebMapper.toRuntimeConfigDto(
                        runtimeSettingsFacade.updateAdvancedConfig(rateLimit, security, compaction, resilience))));
    }

    @GetMapping("/memory-presets")
    public Mono<ResponseEntity<List<MemoryPreset>>> getMemoryPresetsLegacy() {
        return getMemoryPresets();
    }

    private Mono<ResponseEntity<RuntimeConfigDto>> runtimeConfigResponse(Supplier<RuntimeConfigDto> supplier) {
        return Mono.fromSupplier(() -> ResponseEntity.ok(invokeRuntime(supplier)));
    }

    private Mono<ResponseEntity<Void>> runtimeVoidResponse(Runnable action) {
        return Mono.fromRunnable(() -> invokeRuntime(() -> {
            action.run();
            return null;
        })).thenReturn(ResponseEntity.ok().build());
    }

    private <T> Mono<ResponseEntity<List<T>>> runtimeListResponse(Supplier<List<T>> supplier) {
        return Mono.fromSupplier(() -> ResponseEntity.ok(invokeRuntime(supplier)));
    }

    private <T> T invokeRuntime(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        }
    }

    private String normalizeRequiredSelectableTier(String tier, String fieldName) {
        if (tier == null || tier.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        String normalized = tier.trim().toLowerCase(Locale.ROOT);
        if (!ModelTierCatalog.isExplicitSelectableTier(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    fieldName + " must be one of " + ModelTierCatalog.explicitTierListForDisplay());
        }
        return normalized;
    }

    private String normalizeOptionalSelectableTier(String tier, String fieldName) {
        if (tier == null) {
            return null;
        }
        if (tier.isBlank()) {
            return null;
        }
        return normalizeRequiredSelectableTier(tier, fieldName);
    }

    private String normalizeOptionalMemoryPreset(String presetId, String fieldName) {
        if (presetId == null || presetId.isBlank()) {
            return null;
        }
        String normalized = presetId.trim();
        if ("default".equalsIgnoreCase(normalized)) {
            return null;
        }
        boolean exists = runtimeSettingsFacade.getMemoryPresets().stream()
                .map(MemoryPreset::getId)
                .anyMatch(id -> id != null && id.equalsIgnoreCase(normalized));
        if (!exists) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String requireRequestValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        return value.trim();
    }

    public record ModelDto(String id, String displayName, boolean hasReasoning, List<String> reasoningLevels,
            boolean supportsVision) {
    }

    public record LlmProviderImportRequest(RuntimeConfig.LlmProviderConfig config, List<String> selectedModelIds) {
    }

    public record LlmProviderImportResponse(boolean providerSaved, String providerName, String resolvedEndpoint,
            List<String> addedModels, List<String> skippedModels, List<String> errors) {
    }

    public record LlmProviderTestRequest(String mode, String providerName, RuntimeConfig.LlmProviderConfig config) {
    }

    public record LlmProviderTestResponse(String mode, String providerName, String resolvedEndpoint,
            List<String> models, boolean success, String error) {
    }

    public record AdvancedConfigRequest(RateLimitConfigDto rateLimit, SecurityConfigDto security,
            CompactionConfigDto compaction, ResilienceConfigDto resilience) {

        public AdvancedConfigRequest(RuntimeConfig.RateLimitConfig rateLimit,
                RuntimeConfig.SecurityConfig security,
                RuntimeConfig.CompactionConfig compaction,
                RuntimeConfig.ResilienceConfig resilience) {
            this(null, null, null, null);
        }
    }
}
