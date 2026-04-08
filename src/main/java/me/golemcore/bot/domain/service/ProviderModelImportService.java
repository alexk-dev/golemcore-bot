package me.golemcore.bot.domain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProviderModelImportService {

    private final ProviderModelDiscoveryService providerModelDiscoveryService;
    private final ModelConfigService modelConfigService;

    public ProviderImportResult importMissingModels(String providerName) {
        List<String> addedModels = new ArrayList<>();
        List<String> skippedModels = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            ProviderModelDiscoveryService.DiscoveryResult discoveryResult = providerModelDiscoveryService
                    .discoverModelsForProvider(providerName);
            String normalizedProvider = providerName.trim().toLowerCase(Locale.ROOT);
            for (ProviderModelDiscoveryService.DiscoveredModel model : discoveryResult.models()) {
                String modelId = normalizedProvider + "/" + model.id();
                if (modelConfigService.hasModel(modelId)) {
                    skippedModels.add(modelId);
                    continue;
                }
                try {
                    modelConfigService.saveModelStrict(modelId, resolveSettings(normalizedProvider, model));
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

    private ModelConfigService.ModelSettings resolveSettings(String providerName,
            ProviderModelDiscoveryService.DiscoveredModel model) {
        if (model.defaultSettings() != null) {
            return model.defaultSettings();
        }
        ModelConfigService.ModelSettings defaults = modelConfigService.copyModelSettings(modelConfigService.getConfig()
                .getDefaults());
        defaults.setProvider(providerName);
        defaults.setDisplayName(model.displayName());
        return defaults;
    }

    public record ProviderImportResult(String resolvedEndpoint, List<String> addedModels, List<String> skippedModels,
            List<String> errors) {
    }
}
