package me.golemcore.bot.domain.service;

import me.golemcore.bot.infrastructure.config.ModelConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderModelImportServiceTest {

    @Test
    void shouldImportOnlyMissingModelsAndSkipExistingOnes() {
        ProviderModelDiscoveryService providerModelDiscoveryService = mock(ProviderModelDiscoveryService.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);

        ModelConfigService.ModelSettings importedSettings = new ModelConfigService.ModelSettings();
        importedSettings.setProvider("xmesh");
        importedSettings.setDisplayName("GPT-5.2");

        when(providerModelDiscoveryService.discoverModelsForProvider("xmesh"))
                .thenReturn(new ProviderModelDiscoveryService.DiscoveryResult(
                        "https://models.example.com/v1/models",
                        List.of(
                                new ProviderModelDiscoveryService.DiscoveredModel("xmesh", "gpt-5.2", "GPT-5.2",
                                        "openai", importedSettings),
                                new ProviderModelDiscoveryService.DiscoveredModel("xmesh", "existing", "Existing",
                                        "openai", importedSettings))));
        when(modelConfigService.hasModel("xmesh/gpt-5.2")).thenReturn(false);
        when(modelConfigService.hasModel("xmesh/existing")).thenReturn(true);

        ProviderModelImportService service = new ProviderModelImportService(providerModelDiscoveryService,
                modelConfigService);

        ProviderModelImportService.ProviderImportResult result = service.importMissingModels("xmesh");

        verify(modelConfigService).saveModel("xmesh/gpt-5.2", importedSettings);
        assertEquals("https://models.example.com/v1/models", result.resolvedEndpoint());
        assertEquals(List.of("xmesh/gpt-5.2"), result.addedModels());
        assertEquals(List.of("xmesh/existing"), result.skippedModels());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void shouldUseCatalogDefaultsWhenDiscoveredModelHasNoDefaultSettings() {
        ProviderModelDiscoveryService providerModelDiscoveryService = mock(ProviderModelDiscoveryService.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);

        when(providerModelDiscoveryService.discoverModelsForProvider("xmesh"))
                .thenReturn(new ProviderModelDiscoveryService.DiscoveryResult(
                        "https://models.example.com/v1/models",
                        List.of(new ProviderModelDiscoveryService.DiscoveredModel("xmesh", "gemini-2.5-pro",
                                "Gemini 2.5 Pro", "google", null))));
        when(modelConfigService.getAllModels()).thenReturn(new LinkedHashMap<>());

        ModelConfigService.ModelSettings defaults = new ModelConfigService.ModelSettings();
        defaults.setProvider("openai");
        defaults.setDisplayName("Default");
        defaults.setSupportsVision(false);
        defaults.setSupportsTemperature(true);
        defaults.setMaxInputTokens(32000);
        ModelConfigService.ModelsConfig config = new ModelConfigService.ModelsConfig();
        config.setDefaults(defaults);
        config.setModels(new LinkedHashMap<>());
        when(modelConfigService.getConfig()).thenReturn(config);
        ModelConfigService.ModelSettings copiedDefaults = new ModelConfigService.ModelSettings();
        copiedDefaults.setProvider(defaults.getProvider());
        copiedDefaults.setDisplayName(defaults.getDisplayName());
        copiedDefaults.setSupportsVision(defaults.isSupportsVision());
        copiedDefaults.setSupportsTemperature(defaults.isSupportsTemperature());
        copiedDefaults.setMaxInputTokens(defaults.getMaxInputTokens());
        when(modelConfigService.copyModelSettings(defaults)).thenReturn(copiedDefaults);

        ProviderModelImportService service = new ProviderModelImportService(providerModelDiscoveryService,
                modelConfigService);

        service.importMissingModels("xmesh");

        ArgumentCaptor<ModelConfigService.ModelSettings> settingsCaptor = ArgumentCaptor.forClass(
                ModelConfigService.ModelSettings.class);
        verify(modelConfigService).saveModel(org.mockito.ArgumentMatchers.eq("xmesh/gemini-2.5-pro"),
                settingsCaptor.capture());
        ModelConfigService.ModelSettings savedSettings = settingsCaptor.getValue();
        assertEquals("xmesh", savedSettings.getProvider());
        assertEquals("Gemini 2.5 Pro", savedSettings.getDisplayName());
        assertEquals(32000, savedSettings.getMaxInputTokens());
        assertEquals(false, savedSettings.isSupportsVision());
        assertEquals(true, savedSettings.isSupportsTemperature());
    }

    @Test
    void shouldReturnDiscoveryErrorsWithoutThrowing() {
        ProviderModelDiscoveryService providerModelDiscoveryService = mock(ProviderModelDiscoveryService.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);

        when(providerModelDiscoveryService.discoverModelsForProvider("xmesh"))
                .thenThrow(new IllegalStateException("bad gateway"));

        ProviderModelImportService service = new ProviderModelImportService(providerModelDiscoveryService,
                modelConfigService);

        ProviderModelImportService.ProviderImportResult result = service.importMissingModels("xmesh");

        assertTrue(result.addedModels().isEmpty());
        assertTrue(result.skippedModels().isEmpty());
        assertEquals(List.of("bad gateway"), result.errors());
    }
}
