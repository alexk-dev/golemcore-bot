package me.golemcore.bot.application.settings;

import me.golemcore.bot.application.models.ProviderModelDiscoveryService;
import me.golemcore.bot.application.models.ProviderModelImportService;
import me.golemcore.bot.domain.model.MemoryPreset;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.model.hive.HivePolicyBindingState;
import me.golemcore.bot.domain.service.HiveManagedPolicyService;
import me.golemcore.bot.domain.service.MemoryPresetService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;

public class RuntimeSettingsFacade {

    private final RuntimeConfigService runtimeConfigService;
    private final UserPreferencesService preferencesService;
    private final MemoryPresetService memoryPresetService;
    private final HiveManagedPolicyService hiveManagedPolicyService;
    private final RuntimeSettingsValidator validator;
    private final RuntimeSettingsMergeService mergeService;
    private final ProviderModelImportService providerModelImportService;
    private final ProviderModelDiscoveryService providerModelDiscoveryService;

    public RuntimeSettingsFacade(RuntimeConfigService runtimeConfigService,
            UserPreferencesService preferencesService,
            MemoryPresetService memoryPresetService,
            HiveManagedPolicyService hiveManagedPolicyService,
            RuntimeSettingsValidator validator,
            RuntimeSettingsMergeService mergeService,
            ProviderModelImportService providerModelImportService,
            ProviderModelDiscoveryService providerModelDiscoveryService) {
        this.runtimeConfigService = runtimeConfigService;
        this.preferencesService = preferencesService;
        this.memoryPresetService = memoryPresetService;
        this.hiveManagedPolicyService = hiveManagedPolicyService;
        this.validator = validator;
        this.mergeService = mergeService;
        this.providerModelImportService = providerModelImportService;
        this.providerModelDiscoveryService = providerModelDiscoveryService;
    }

    public RuntimeConfig getRuntimeConfigForApi() {
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig updateRuntimeConfig(RuntimeConfig config) {
        RuntimeConfig current = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig merged = mergeService.mergeRuntimeConfigSections(current, config);
        mergeService.mergeRuntimeSecrets(current, merged);
        rejectManagedHivePolicyMutation(current, merged);
        validator.validateRuntimeConfigUpdate(current, merged, runtimeConfigService.isHiveManagedByProperties());
        runtimeConfigService.updateRuntimeConfig(merged);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig updateModelRouterConfig(RuntimeConfig.ModelRouterConfig modelRouterConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        rejectManagedHivePolicyModelRouterMutation(config.getModelRouter(), modelRouterConfig);
        validator.validateModelRouterConfig(modelRouterConfig, config.getLlm());
        config.setModelRouter(modelRouterConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig updateLlmConfig(RuntimeConfig.LlmConfig llmConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        rejectManagedHivePolicyLlmMutation(config.getLlm(), llmConfig);
        mergeService.mergeLlmSecrets(config.getLlm(), llmConfig);
        validator.validateLlmConfig(llmConfig, config.getModelRouter());
        validator.validateModelRouterConfig(config.getModelRouter(), llmConfig);
        config.setLlm(llmConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig addLlmProvider(String name, RuntimeConfig.LlmProviderConfig providerConfig) {
        String normalizedName = validator.normalizeProviderName(name);
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.LlmConfig llmConfig = ensureLlmConfig(config);
        rejectManagedHivePolicyLlmWrite();
        if (llmConfig.getProviders().containsKey(normalizedName)) {
            throw new IllegalArgumentException("Provider '" + normalizedName + "' already exists");
        }
        validator.validateProviderConfig(normalizedName, providerConfig);
        runtimeConfigService.addLlmProvider(normalizedName, providerConfig);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public ProviderModelImportService.ProviderImportResult addLlmProviderAndImportModels(String name,
            RuntimeConfig.LlmProviderConfig providerConfig,
            List<String> selectedModelIds) {
        String normalizedName = validator.normalizeProviderName(name);
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.LlmConfig llmConfig = ensureLlmConfig(config);
        rejectManagedHivePolicyLlmWrite();
        if (llmConfig.getProviders().containsKey(normalizedName)) {
            throw new IllegalArgumentException("Provider '" + normalizedName + "' already exists");
        }
        validator.validateProviderConfig(normalizedName, providerConfig);
        runtimeConfigService.addLlmProvider(normalizedName, providerConfig);
        return providerModelImportService.importMissingModels(normalizedName, selectedModelIds);
    }

    public RuntimeConfig updateLlmProvider(String name, RuntimeConfig.LlmProviderConfig providerConfig) {
        String normalizedName = validator.normalizeProviderName(name);
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.LlmConfig llmConfig = ensureLlmConfig(config);
        rejectManagedHivePolicyLlmWrite();
        if (!llmConfig.getProviders().containsKey(normalizedName)) {
            throw new IllegalArgumentException("Provider '" + normalizedName + "' does not exist");
        }
        validator.validateProviderConfig(normalizedName, providerConfig);
        runtimeConfigService.updateLlmProvider(normalizedName, providerConfig);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public LlmProviderTestResult testSavedLlmProvider(String providerName) {
        String normalizedName = validator.normalizeProviderName(providerName);
        try {
            ProviderModelDiscoveryService.DiscoveryResult discoveryResult = providerModelDiscoveryService
                    .discoverModelsForProvider(normalizedName);
            return toProviderTestResult("saved", normalizedName, discoveryResult, null);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new LlmProviderTestResult("saved", normalizedName, null, List.of(), false, e.getMessage());
        }
    }

    public LlmProviderTestResult testDraftLlmProvider(String providerName,
            RuntimeConfig.LlmProviderConfig providerConfig) {
        String normalizedName = validator.normalizeProviderName(providerName);
        RuntimeConfig.LlmProviderConfig effectiveConfig = buildDraftProviderTestConfig(normalizedName, providerConfig);
        try {
            validator.validateProviderConfig(normalizedName, effectiveConfig);
            ProviderModelDiscoveryService.DiscoveryResult discoveryResult = providerModelDiscoveryService
                    .discoverModelsForConfig(normalizedName, effectiveConfig);
            return toProviderTestResult("draft", normalizedName, discoveryResult, null);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new LlmProviderTestResult("draft", normalizedName, null, List.of(), false, e.getMessage());
        }
    }

    public void removeLlmProvider(String name) {
        String normalizedName = validator.normalizeProviderName(name);
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        rejectManagedHivePolicyLlmWrite();
        validator.validateProviderRemoval(config.getModelRouter(), normalizedName);
        boolean removed = runtimeConfigService.removeLlmProvider(normalizedName);
        if (!removed) {
            throw new NoSuchElementException("Provider '" + normalizedName + "' not found");
        }
    }

    public RuntimeConfig updateToolsConfig(RuntimeConfig.ToolsConfig toolsConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        validator.normalizeAndValidateShellEnvironmentVariables(toolsConfig);
        config.setTools(toolsConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public List<RuntimeConfig.ShellEnvironmentVariable> getShellEnvironmentVariables() {
        RuntimeConfig.ToolsConfig toolsConfig = runtimeConfigService.getRuntimeConfigForApi().getTools();
        return toolsConfig != null && toolsConfig.getShellEnvironmentVariables() != null
                ? toolsConfig.getShellEnvironmentVariables()
                : List.of();
    }

    public RuntimeConfig createShellEnvironmentVariable(RuntimeConfig.ShellEnvironmentVariable variable) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.ToolsConfig toolsConfig = validator.ensureToolsConfig(config);
        List<RuntimeConfig.ShellEnvironmentVariable> variables = validator.ensureShellEnvironmentVariables(toolsConfig);

        RuntimeConfig.ShellEnvironmentVariable normalized = validator.normalizeAndValidateShellEnvironmentVariable(
                variable);
        if (validator.containsShellEnvironmentVariableName(variables, normalized.getName())) {
            throw new IllegalArgumentException(
                    "tools.shellEnvironmentVariables contains duplicate name: " + normalized.getName());
        }
        variables.add(normalized);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig updateShellEnvironmentVariable(String name, RuntimeConfig.ShellEnvironmentVariable variable) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.ToolsConfig toolsConfig = validator.ensureToolsConfig(config);
        List<RuntimeConfig.ShellEnvironmentVariable> variables = validator.ensureShellEnvironmentVariables(toolsConfig);
        String normalizedCurrentName = validator.normalizeAndValidateShellEnvironmentVariableName(name);

        int index = validator.findShellEnvironmentVariableIndex(variables, normalizedCurrentName);
        if (index < 0) {
            throw new NoSuchElementException(
                    "Shell environment variable '" + normalizedCurrentName + "' not found");
        }

        RuntimeConfig.ShellEnvironmentVariable source = variable != null ? variable
                : RuntimeConfig.ShellEnvironmentVariable.builder().build();
        String updatedName = source.getName() == null || source.getName().isBlank()
                ? normalizedCurrentName
                : source.getName();
        RuntimeConfig.ShellEnvironmentVariable normalizedUpdated = validator
                .normalizeAndValidateShellEnvironmentVariable(
                        RuntimeConfig.ShellEnvironmentVariable.builder()
                                .name(updatedName)
                                .value(source.getValue())
                                .build());

        if (!normalizedCurrentName.equals(normalizedUpdated.getName())
                && validator.containsShellEnvironmentVariableName(variables, normalizedUpdated.getName())) {
            throw new IllegalArgumentException(
                    "tools.shellEnvironmentVariables contains duplicate name: " + normalizedUpdated.getName());
        }

        variables.set(index, normalizedUpdated);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig deleteShellEnvironmentVariable(String name) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.ToolsConfig toolsConfig = validator.ensureToolsConfig(config);
        List<RuntimeConfig.ShellEnvironmentVariable> variables = validator.ensureShellEnvironmentVariables(toolsConfig);
        String normalizedName = validator.normalizeAndValidateShellEnvironmentVariableName(name);

        int index = validator.findShellEnvironmentVariableIndex(variables, normalizedName);
        if (index < 0) {
            throw new NoSuchElementException("Shell environment variable '" + normalizedName + "' not found");
        }

        variables.remove(index);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig updateVoiceConfig(RuntimeConfig.VoiceConfig voiceConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.VoiceConfig currentVoiceConfig = config.getVoice() != null
                ? config.getVoice()
                : RuntimeConfig.VoiceConfig.builder().build();
        voiceConfig.setApiKey(mergeService.mergeSecret(currentVoiceConfig.getApiKey(), voiceConfig.getApiKey()));
        voiceConfig.setWhisperSttApiKey(
                mergeService.mergeSecret(currentVoiceConfig.getWhisperSttApiKey(), voiceConfig.getWhisperSttApiKey()));
        validator.validateVoiceConfig(voiceConfig);
        config.setVoice(voiceConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig updateTurnConfig(RuntimeConfig.TurnConfig turnConfig) {
        validator.validateTurnConfig(turnConfig);
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setTurn(turnConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig updateToolLoopConfig(RuntimeConfig.ToolLoopConfig toolLoopConfig) {
        validator.validateToolLoopConfig(toolLoopConfig);
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setToolLoop(toolLoopConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig updateMemoryConfig(RuntimeConfig.MemoryConfig memoryConfig) {
        validator.validateMemoryConfig(memoryConfig);
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setMemory(memoryConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig updateSessionRetentionConfig(RuntimeConfig.SessionRetentionConfig sessionRetentionConfig) {
        validator.validateSessionRetentionConfig(sessionRetentionConfig);
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setSessionRetention(sessionRetentionConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public List<MemoryPreset> getMemoryPresets() {
        return memoryPresetService.getPresets();
    }

    public RuntimeConfig updateSkillsConfig(RuntimeConfig.SkillsConfig skillsConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setSkills(skillsConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig updateUsageConfig(RuntimeConfig.UsageConfig usageConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setUsage(usageConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig updateTelemetryConfig(RuntimeConfig.TelemetryConfig telemetryConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setTelemetry(telemetryConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig updateMcpConfig(RuntimeConfig.McpConfig mcpConfig) {
        if (mcpConfig == null) {
            throw new IllegalArgumentException("mcp config is required");
        }
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.McpConfig existing = config.getMcp();
        if (existing != null && (mcpConfig.getCatalog() == null || mcpConfig.getCatalog().isEmpty())) {
            mcpConfig.setCatalog(existing.getCatalog());
        }
        config.setMcp(mcpConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public List<RuntimeConfig.McpCatalogEntry> getMcpCatalog() {
        return runtimeConfigService.getMcpCatalog();
    }

    public RuntimeConfig addMcpCatalogEntry(RuntimeConfig.McpCatalogEntry entry) {
        validator.validateMcpCatalogEntry(entry);
        String normalizedName = validator.normalizeCatalogEntryName(entry.getName());
        entry.setName(normalizedName);
        List<RuntimeConfig.McpCatalogEntry> catalog = runtimeConfigService.getMcpCatalog();
        boolean exists = catalog.stream().anyMatch(existingEntry -> normalizedName.equals(existingEntry.getName()));
        if (exists) {
            throw new IllegalArgumentException("MCP catalog entry '" + normalizedName + "' already exists");
        }
        runtimeConfigService.addMcpCatalogEntry(entry);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig updateMcpCatalogEntry(String name, RuntimeConfig.McpCatalogEntry entry) {
        String normalizedName = validator.normalizeCatalogEntryName(name);
        validator.validateMcpCatalogEntry(entry);
        entry.setName(normalizedName);
        boolean updated = runtimeConfigService.updateMcpCatalogEntry(normalizedName, entry);
        if (!updated) {
            throw new NoSuchElementException("MCP catalog entry '" + normalizedName + "' not found");
        }
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public void removeMcpCatalogEntry(String name) {
        String normalizedName = validator.normalizeCatalogEntryName(name);
        boolean removed = runtimeConfigService.removeMcpCatalogEntry(normalizedName);
        if (!removed) {
            throw new NoSuchElementException("MCP catalog entry '" + normalizedName + "' not found");
        }
    }

    public RuntimeConfig updateHiveConfig(RuntimeConfig.HiveConfig hiveConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        validator.rejectManagedHiveMutation(config, hiveConfig, runtimeConfigService.isHiveManagedByProperties());
        validator.validateHiveConfig(hiveConfig);
        config.setHive(hiveConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig updatePlanConfig(RuntimeConfig.PlanConfig planConfig) {
        validator.validatePlanConfig(planConfig);
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        config.setPlan(planConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public void updateWebhooksConfig(UserPreferences.WebhookConfig webhookConfig) {
        UserPreferences prefs = preferencesService.getPreferences();
        mergeService.mergeWebhookSecrets(prefs.getWebhooks(), webhookConfig);
        validator.validateWebhookConfig(webhookConfig);
        prefs.setWebhooks(webhookConfig);
        preferencesService.savePreferences(prefs);
    }

    public RuntimeConfig updateAutoConfig(RuntimeConfig.AutoModeConfig autoConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        validator.validateAndNormalizeAutoModeConfig(autoConfig);
        config.setAutoMode(autoConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig updateTracingConfig(RuntimeConfig.TracingConfig tracingConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        validator.validateAndNormalizeTracingConfig(tracingConfig);
        config.setTracing(tracingConfig);
        runtimeConfigService.updateRuntimeConfig(config);
        return runtimeConfigService.getRuntimeConfigForApi();
    }

    public RuntimeConfig updateAdvancedConfig(RuntimeConfig.RateLimitConfig rateLimitConfig,
            RuntimeConfig.SecurityConfig securityConfig,
            RuntimeConfig.CompactionConfig compactionConfig,
            RuntimeConfig.ResilienceConfig resilienceConfig) {
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        if (rateLimitConfig != null) {
            config.setRateLimit(rateLimitConfig);
        }
        if (securityConfig != null) {
            config.setSecurity(securityConfig);
        }
        if (compactionConfig != null) {
            validator.validateCompactionConfig(compactionConfig);
            config.setCompaction(compactionConfig);
        }
        if (resilienceConfig != null) {
            config.setResilience(resilienceConfig);
        }
        runtimeConfigService.updateRuntimeConfig(config);
        RuntimeConfig apiView = runtimeConfigService.getRuntimeConfigForApi();
        return apiView != null ? apiView : config;
    }

    private RuntimeConfig.LlmConfig ensureLlmConfig(RuntimeConfig config) {
        RuntimeConfig.LlmConfig llmConfig = config.getLlm();
        if (llmConfig == null) {
            llmConfig = RuntimeConfig.LlmConfig.builder()
                    .providers(new LinkedHashMap<>())
                    .build();
            config.setLlm(llmConfig);
        }
        if (llmConfig.getProviders() == null) {
            llmConfig.setProviders(new LinkedHashMap<>());
        }
        return llmConfig;
    }

    private void rejectManagedHivePolicyMutation(RuntimeConfig current, RuntimeConfig incoming) {
        if (incoming == null) {
            return;
        }
        rejectManagedHivePolicyLlmMutation(current != null ? current.getLlm() : null, incoming.getLlm());
        rejectManagedHivePolicyModelRouterMutation(
                current != null ? current.getModelRouter() : null,
                incoming.getModelRouter());
    }

    private void rejectManagedHivePolicyLlmMutation(RuntimeConfig.LlmConfig currentLlmConfig,
            RuntimeConfig.LlmConfig incomingLlmConfig) {
        HivePolicyBindingState bindingState = hiveManagedPolicyService.getBindingState().orElse(null);
        if (bindingState == null || !bindingState.hasActiveBinding() || incomingLlmConfig == null) {
            return;
        }
        if (currentLlmConfig == null || !incomingLlmConfig.equals(currentLlmConfig)) {
            throw new IllegalStateException("LLM settings are managed by Hive policy group \""
                    + bindingState.getPolicyGroupId() + "\" and are read-only");
        }
    }

    private void rejectManagedHivePolicyModelRouterMutation(RuntimeConfig.ModelRouterConfig currentModelRouterConfig,
            RuntimeConfig.ModelRouterConfig incomingModelRouterConfig) {
        HivePolicyBindingState bindingState = hiveManagedPolicyService.getBindingState().orElse(null);
        if (bindingState == null || !bindingState.hasActiveBinding() || incomingModelRouterConfig == null) {
            return;
        }
        if (currentModelRouterConfig == null || !incomingModelRouterConfig.equals(currentModelRouterConfig)) {
            throw new IllegalStateException("Model router settings are managed by Hive policy group \""
                    + bindingState.getPolicyGroupId() + "\" and are read-only");
        }
    }

    private void rejectManagedHivePolicyLlmWrite() {
        HivePolicyBindingState bindingState = hiveManagedPolicyService.getBindingState().orElse(null);
        if (bindingState == null || !bindingState.hasActiveBinding()) {
            return;
        }
        throw new IllegalStateException("LLM settings are managed by Hive policy group \""
                + bindingState.getPolicyGroupId() + "\" and are read-only");
    }

    private RuntimeConfig.LlmProviderConfig buildDraftProviderTestConfig(String providerName,
            RuntimeConfig.LlmProviderConfig draftConfig) {
        if (draftConfig == null) {
            throw new IllegalArgumentException("config is required");
        }
        RuntimeConfig.LlmProviderConfig savedProviderConfig = null;
        RuntimeConfig runtimeConfig = runtimeConfigService.getRuntimeConfig();
        if (runtimeConfig != null && runtimeConfig.getLlm() != null && runtimeConfig.getLlm().getProviders() != null) {
            savedProviderConfig = runtimeConfig.getLlm().getProviders().get(providerName);
        }

        return RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(mergeService.mergeSecret(savedProviderConfig != null ? savedProviderConfig.getApiKey() : null,
                        draftConfig.getApiKey()))
                .baseUrl(draftConfig.getBaseUrl())
                .requestTimeoutSeconds(draftConfig.getRequestTimeoutSeconds())
                .apiType(draftConfig.getApiType())
                .legacyApi(draftConfig.getLegacyApi())
                .build();
    }

    private LlmProviderTestResult toProviderTestResult(String mode, String providerName,
            ProviderModelDiscoveryService.DiscoveryResult discoveryResult,
            String error) {
        return new LlmProviderTestResult(
                mode,
                providerName,
                discoveryResult != null ? discoveryResult.resolvedEndpoint() : null,
                discoveryResult != null
                        ? discoveryResult.models().stream()
                                .map(model -> providerName + "/" + model.id())
                                .toList()
                        : List.of(),
                error == null,
                error);
    }

    public record LlmProviderTestResult(String mode, String providerName, String resolvedEndpoint,
            List<String> models, boolean success, String error) {
    }
}
