package me.golemcore.bot.application.models;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.ModelConfigAdminPort;

@Slf4j
public class ModelManagementFacade {

    private static final String TEST_PROMPT = "Reply in one short sentence: What model are you? Include your exact model name/version.";
    private static final int TEST_TIMEOUT_SECONDS = 30;

    private final ModelConfigAdminPort modelConfigAdminPort;
    private final ModelSelectionService modelSelectionService;
    private final ProviderModelDiscoveryService providerModelDiscoveryService;
    private final ModelRegistryService modelRegistryService;
    private final LlmPort llmPort;

    public ModelManagementFacade(
            ModelConfigAdminPort modelConfigAdminPort,
            ModelSelectionService modelSelectionService,
            ProviderModelDiscoveryService providerModelDiscoveryService,
            ModelRegistryService modelRegistryService,
            LlmPort llmPort) {
        this.modelConfigAdminPort = modelConfigAdminPort;
        this.modelSelectionService = modelSelectionService;
        this.providerModelDiscoveryService = providerModelDiscoveryService;
        this.modelRegistryService = modelRegistryService;
        this.llmPort = llmPort;
    }

    public ModelConfigAdminPort.ModelsConfigSnapshot getModelsConfig() {
        return modelConfigAdminPort.getConfig();
    }

    public ModelConfigAdminPort.ModelsConfigSnapshot replaceModelsConfig(
            ModelConfigAdminPort.ModelsConfigSnapshot newConfig) {
        return modelConfigAdminPort.replaceConfig(newConfig);
    }

    public void saveModel(String id, String previousId, ModelConfigAdminPort.ModelSettingsSnapshot settings) {
        modelConfigAdminPort.saveModel(requireValue(id, "id"), optionalValue(previousId), requireSettings(settings));
    }

    public void deleteModel(String id) {
        String normalizedId = requireValue(id, "id");
        if (!modelConfigAdminPort.deleteModel(normalizedId)) {
            throw new NoSuchElementException("Model '" + normalizedId + "' not found");
        }
    }

    public Map<String, List<ModelSelectionService.AvailableModel>> getAvailableModels() {
        return modelSelectionService.getAvailableModelsGrouped();
    }

    public List<ProviderModelDiscoveryService.DiscoveredModel> discoverProviderModels(String provider) {
        return providerModelDiscoveryService.discoverModels(provider);
    }

    public ModelRegistryService.ResolveResult resolveModelRegistry(String provider, String modelId) {
        return modelRegistryService.resolveDefaults(requireValue(provider, "provider"),
                requireValue(modelId, "modelId"));
    }

    public TestModelResult testModel(String model) {
        String normalizedModel = requireValue(model, "model");
        log.info("[Models] Testing model: {}", normalizedModel);
        try {
            LlmRequest llmRequest = LlmRequest.builder()
                    .model(normalizedModel)
                    .messages(List.of(Message.builder()
                            .role("user")
                            .content(TEST_PROMPT)
                            .build()))
                    .temperature(0.0)
                    .maxTokens(256)
                    .build();
            LlmResponse response = llmPort.chat(llmRequest).get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String reply = response.getContent() != null ? response.getContent().trim() : "";
            log.info("[Models] Test response from {}: {}", normalizedModel, reply);
            return new TestModelResult(true, reply, null);
        } catch (Exception exception) { // NOSONAR - user-facing diagnostic
            String errorMessage = exception.getCause() != null ? exception.getCause().getMessage()
                    : exception.getMessage();
            log.warn("[Models] Test failed for {}: {}", normalizedModel, errorMessage);
            return new TestModelResult(false, null, errorMessage);
        }
    }

    public void reloadModels() {
        modelConfigAdminPort.reload();
    }

    private String requireValue(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private String optionalValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private ModelConfigAdminPort.ModelSettingsSnapshot requireSettings(
            ModelConfigAdminPort.ModelSettingsSnapshot settings) {
        if (settings == null) {
            throw new IllegalArgumentException("settings is required");
        }
        return settings;
    }

    public record TestModelResult(boolean success, String reply, String error) {
    }
}
