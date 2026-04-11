package me.golemcore.bot.application.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.catalog.ModelCatalogEntry;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.ModelConfigAdminPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelManagementFacadeTest {

    private ModelConfigAdminPort modelConfigAdminPort;
    private ModelSelectionService modelSelectionService;
    private ProviderModelDiscoveryService providerModelDiscoveryService;
    private ModelRegistryService modelRegistryService;
    private LlmPort llmPort;
    private ModelManagementFacade facade;

    @BeforeEach
    void setUp() {
        modelConfigAdminPort = mock(ModelConfigAdminPort.class);
        modelSelectionService = mock(ModelSelectionService.class);
        providerModelDiscoveryService = mock(ProviderModelDiscoveryService.class);
        modelRegistryService = mock(ModelRegistryService.class);
        llmPort = mock(LlmPort.class);
        facade = new ModelManagementFacade(
                modelConfigAdminPort,
                modelSelectionService,
                providerModelDiscoveryService,
                modelRegistryService,
                llmPort);
    }

    @Test
    void shouldSaveModelThroughConfigService() {
        ModelConfigAdminPort.ModelSettingsSnapshot settings = new ModelConfigAdminPort.ModelSettingsSnapshot(
                "openai",
                "GPT-5",
                true,
                true,
                128000,
                null);

        facade.saveModel("gpt-5", null, settings);

        verify(modelConfigAdminPort).saveModel("gpt-5", null, settings);
    }

    @Test
    void shouldTrimIdentifiersBeforeSavingModel() {
        ModelConfigAdminPort.ModelSettingsSnapshot settings = new ModelConfigAdminPort.ModelSettingsSnapshot(
                "openai",
                "GPT-5",
                true,
                true,
                128000,
                null);

        facade.saveModel(" gpt-5 ", " legacy ", settings);

        verify(modelConfigAdminPort).saveModel("gpt-5", "legacy", settings);
    }

    @Test
    void shouldRejectBlankModelId() {
        ModelConfigAdminPort.ModelSettingsSnapshot settings = new ModelConfigAdminPort.ModelSettingsSnapshot(
                "openai",
                "GPT-5",
                true,
                true,
                128000,
                null);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> facade.saveModel(" ", null, settings));
        assertEquals("id is required", exception.getMessage());
    }

    @Test
    void shouldRejectMissingSettings() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> facade.saveModel("gpt-5", null, null));

        assertEquals("settings is required", exception.getMessage());
    }

    @Test
    void shouldReturnModelsConfig() {
        ModelConfigAdminPort.ModelsConfigSnapshot snapshot = new ModelConfigAdminPort.ModelsConfigSnapshot(Map.of(),
                null);
        when(modelConfigAdminPort.getConfig()).thenReturn(snapshot);

        assertEquals(snapshot, facade.getModelsConfig());
    }

    @Test
    void shouldReplaceModelsConfig() {
        ModelConfigAdminPort.ModelsConfigSnapshot snapshot = new ModelConfigAdminPort.ModelsConfigSnapshot(Map.of(),
                null);
        when(modelConfigAdminPort.replaceConfig(snapshot)).thenReturn(snapshot);

        assertEquals(snapshot, facade.replaceModelsConfig(snapshot));
    }

    @Test
    void shouldReturnAvailableModels() {
        Map<String, List<ModelSelectionService.AvailableModel>> grouped = new LinkedHashMap<>();
        grouped.put("openai", List.of(
                new ModelSelectionService.AvailableModel("gpt-5", "openai", "GPT-5", true, List.of("low"), true)));
        when(modelSelectionService.getAvailableModelsGrouped()).thenReturn(grouped);

        Map<String, List<ModelSelectionService.AvailableModel>> result = facade.getAvailableModels();

        assertEquals(1, result.get("openai").size());
    }

    @Test
    void shouldResolveRegistryDefaults() {
        ModelCatalogEntry entry = new ModelCatalogEntry("openai", "GPT-5", true, false, 1000000, null);
        when(modelRegistryService.resolveDefaults("openai", "gpt-5"))
                .thenReturn(new ModelRegistryService.ResolveResult(entry, "shared", "remote-hit"));

        ModelRegistryService.ResolveResult result = facade.resolveModelRegistry("openai", "gpt-5");

        assertNotNull(result.defaultCatalogEntry());
        assertEquals("shared", result.configSource());
    }

    @Test
    void shouldRejectBlankResolveInputs() {
        IllegalArgumentException providerError = assertThrows(IllegalArgumentException.class,
                () -> facade.resolveModelRegistry(" ", "gpt-5"));
        IllegalArgumentException modelError = assertThrows(IllegalArgumentException.class,
                () -> facade.resolveModelRegistry("openai", " "));

        assertEquals("provider is required", providerError.getMessage());
        assertEquals("modelId is required", modelError.getMessage());
    }

    @Test
    void shouldDiscoverProviderModels() {
        List<ProviderModelDiscoveryService.DiscoveredModel> discoveredModels = List.of(
                new ProviderModelDiscoveryService.DiscoveredModel("openai", "gpt-5", "GPT-5", "openai", null));
        when(providerModelDiscoveryService.discoverModels("openai")).thenReturn(discoveredModels);

        assertEquals(discoveredModels, facade.discoverProviderModels("openai"));
    }

    @Test
    void shouldMapSuccessfulModelTest() throws Exception {
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder().content("I am GPT-5").build()));

        ModelManagementFacade.TestModelResult result = facade.testModel("gpt-5");

        assertTrue(result.success());
        assertEquals("I am GPT-5", result.reply());
    }

    @Test
    void shouldRejectBlankTestModelTarget() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> facade.testModel(" "));

        assertEquals("model is required", exception.getMessage());
    }

    @Test
    void shouldMapFailedModelTest() {
        CompletableFuture<LlmResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("Connection refused"));
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(failed);

        ModelManagementFacade.TestModelResult result = facade.testModel("gpt-5");

        assertFalse(result.success());
        assertEquals("Connection refused", result.error());
    }

    @Test
    void shouldRejectMissingDeleteTarget() {
        when(modelConfigAdminPort.deleteModel("missing")).thenReturn(false);

        NoSuchElementException exception = assertThrows(NoSuchElementException.class,
                () -> facade.deleteModel("missing"));
        assertEquals("Model 'missing' not found", exception.getMessage());
    }

    @Test
    void shouldDeleteExistingModel() {
        when(modelConfigAdminPort.deleteModel("gpt-5")).thenReturn(true);

        facade.deleteModel("gpt-5");

        verify(modelConfigAdminPort).deleteModel("gpt-5");
    }

    @Test
    void shouldReloadModels() {
        facade.reloadModels();

        verify(modelConfigAdminPort).reload();
    }

    @Test
    void shouldReturnErrorMessageFromDirectModelTestException() {
        when(llmPort.chat(any(LlmRequest.class))).thenThrow(new IllegalStateException("transport unavailable"));

        ModelManagementFacade.TestModelResult result = facade.testModel("gpt-5");

        assertFalse(result.success());
        assertEquals("transport unavailable", result.error());
    }
}
