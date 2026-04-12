package me.golemcore.bot.application.models;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.catalog.ModelCatalogEntry;
import me.golemcore.bot.domain.model.catalog.ModelReasoningLevel;
import me.golemcore.bot.domain.model.catalog.ModelReasoningProfile;
import me.golemcore.bot.port.outbound.ModelConfigAdminPort;

@RequiredArgsConstructor
@Slf4j
public class ProviderModelImportService {

    private static final int DEFAULT_MAX_INPUT_TOKENS = 128000;

    private final ProviderModelDiscoveryService providerModelDiscoveryService;
    private final ModelConfigAdminPort modelConfigAdminPort;

    public ProviderImportResult importMissingModels(String providerName, List<String> selectedModelIds) {
        List<String> addedModels = new ArrayList<>();
        List<String> skippedModels = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String normalizedProvider = normalizeProvider(providerName);
        Set<String> selectedModels = normalizeSelectedModels(normalizedProvider, selectedModelIds);
        if (selectedModels != null && selectedModels.isEmpty()) {
            return new ProviderImportResult(null, addedModels, skippedModels, errors);
        }

        try {
            ProviderModelDiscoveryService.DiscoveryResult discoveryResult = providerModelDiscoveryService
                    .discoverModelsForProvider(providerName);
            ModelConfigAdminPort.ModelsConfigSnapshot config = modelConfigAdminPort.getConfig();
            Map<String, ModelConfigAdminPort.ModelSettingsSnapshot> configuredModels = config != null
                    ? config.models()
                    : Map.of();
            ModelConfigAdminPort.ModelSettingsSnapshot defaults = config != null ? config.defaults() : null;
            Set<String> existingModelIds = new LinkedHashSet<>(configuredModels.keySet());
            for (ProviderModelDiscoveryService.DiscoveredModel model : discoveryResult.models()) {
                String modelId = normalizedProvider + "/" + model.id();
                if (selectedModels != null && !selectedModels.contains(modelId)) {
                    continue;
                }
                if (existingModelIds.contains(modelId)) {
                    skippedModels.add(modelId);
                    continue;
                }
                try {
                    modelConfigAdminPort.saveModel(modelId, null,
                            resolveSettings(normalizedProvider, model, defaults));
                    existingModelIds.add(modelId);
                    addedModels.add(modelId);
                } catch (RuntimeException e) { // NOSONAR - collecting partial import failures by design
                    log.warn("[ProviderImport] Failed to save model {}: {}", modelId, e.getMessage());
                    errors.add(modelId + ": " + e.getMessage());
                }
            }
            return new ProviderImportResult(discoveryResult.resolvedEndpoint(), addedModels, skippedModels, errors);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("[ProviderImport] Discovery failed for provider {}: {}", providerName, e.getMessage());
            errors.add(e.getMessage());
            return new ProviderImportResult(null, addedModels, skippedModels, errors);
        }
    }

    private ModelConfigAdminPort.ModelSettingsSnapshot resolveSettings(
            String providerName,
            ProviderModelDiscoveryService.DiscoveredModel model,
            ModelConfigAdminPort.ModelSettingsSnapshot defaults) {
        if (model.defaultSettings() != null) {
            return toSnapshot(model.defaultSettings().withProvider(providerName));
        }
        ModelConfigAdminPort.ModelSettingsSnapshot baseline = defaults != null
                ? defaults
                : new ModelConfigAdminPort.ModelSettingsSnapshot(
                        providerName,
                        model.displayName(),
                        true,
                        true,
                        DEFAULT_MAX_INPUT_TOKENS,
                        null);
        return new ModelConfigAdminPort.ModelSettingsSnapshot(
                providerName,
                model.displayName(),
                baseline.supportsVision(),
                baseline.supportsTemperature(),
                baseline.maxInputTokens(),
                baseline.reasoning());
    }

    private ModelConfigAdminPort.ModelSettingsSnapshot toSnapshot(ModelCatalogEntry entry) {
        return new ModelConfigAdminPort.ModelSettingsSnapshot(
                entry.getProvider(),
                entry.getDisplayName(),
                entry.isSupportsVision(),
                entry.isSupportsTemperature(),
                entry.getMaxInputTokens(),
                toReasoningSnapshot(entry.getReasoning()));
    }

    private ModelConfigAdminPort.ReasoningConfigSnapshot toReasoningSnapshot(ModelReasoningProfile reasoning) {
        if (reasoning == null) {
            return null;
        }
        Map<String, ModelConfigAdminPort.ReasoningLevelSnapshot> levels = reasoning.getLevels() != null
                ? reasoning.getLevels().entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> toReasoningLevelSnapshot(entry.getValue()),
                                (left, right) -> left,
                                LinkedHashMap::new))
                : Map.of();
        return new ModelConfigAdminPort.ReasoningConfigSnapshot(reasoning.getDefaultLevel(), levels);
    }

    private ModelConfigAdminPort.ReasoningLevelSnapshot toReasoningLevelSnapshot(ModelReasoningLevel level) {
        return new ModelConfigAdminPort.ReasoningLevelSnapshot(
                level != null ? level.getMaxInputTokens() : DEFAULT_MAX_INPUT_TOKENS);
    }

    private Set<String> normalizeSelectedModels(String providerName, List<String> selectedModelIds) {
        if (selectedModelIds == null) {
            return null;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String modelId : selectedModelIds) {
            if (modelId == null || modelId.isBlank()) {
                continue;
            }
            String trimmed = modelId.trim();
            if (trimmed.startsWith(providerName + "/")) {
                normalized.add(trimmed);
            } else {
                normalized.add(providerName + "/" + trimmed);
            }
        }
        return normalized;
    }

    private String normalizeProvider(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalArgumentException("Provider name is required");
        }
        return providerName.trim().toLowerCase(Locale.ROOT);
    }

    public record ProviderImportResult(String resolvedEndpoint, List<String> addedModels, List<String> skippedModels,
            List<String> errors) {
    }
}
