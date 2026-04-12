package me.golemcore.bot.application.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.golemcore.bot.domain.model.catalog.ModelCatalogEntry;
import me.golemcore.bot.domain.model.catalog.ModelReasoningLevel;
import me.golemcore.bot.domain.model.catalog.ModelReasoningProfile;
import me.golemcore.bot.port.outbound.ModelConfigAdminPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProviderModelImportServiceTest {

    @Test
    void shouldImportSelectedMissingModelsAndSkipExistingOnes() {
        ProviderModelDiscoveryService providerModelDiscoveryService = mock(ProviderModelDiscoveryService.class);
        ModelConfigAdminPort modelConfigAdminPort = mock(ModelConfigAdminPort.class);
        ModelCatalogEntry importedSettings = new ModelCatalogEntry("xmesh", "GPT-5.2", true, true, 128000, null);
        ModelConfigAdminPort.ModelsConfigSnapshot config = new ModelConfigAdminPort.ModelsConfigSnapshot(
                Map.of("xmesh/existing", new ModelConfigAdminPort.ModelSettingsSnapshot(
                        "xmesh", "Existing", true, true, 128000, null)),
                null);

        when(providerModelDiscoveryService.discoverModelsForProvider("xmesh"))
                .thenReturn(new ProviderModelDiscoveryService.DiscoveryResult(
                        "https://models.example.com/v1/models",
                        List.of(
                                new ProviderModelDiscoveryService.DiscoveredModel("xmesh", "gpt-5.2", "GPT-5.2",
                                        "openai", importedSettings),
                                new ProviderModelDiscoveryService.DiscoveredModel("xmesh", "existing", "Existing",
                                        "openai", importedSettings),
                                new ProviderModelDiscoveryService.DiscoveredModel("xmesh", "unselected", "Hidden",
                                        "openai", importedSettings))));
        when(modelConfigAdminPort.getConfig()).thenReturn(config);

        ProviderModelImportService service = new ProviderModelImportService(providerModelDiscoveryService,
                modelConfigAdminPort);

        ProviderModelImportService.ProviderImportResult result = service.importMissingModels("xmesh",
                List.of("xmesh/gpt-5.2", "xmesh/existing"));

        ArgumentCaptor<ModelConfigAdminPort.ModelSettingsSnapshot> settingsCaptor = ArgumentCaptor
                .forClass(ModelConfigAdminPort.ModelSettingsSnapshot.class);
        verify(modelConfigAdminPort).saveModel(eq("xmesh/gpt-5.2"), eq(null), settingsCaptor.capture());
        assertEquals("GPT-5.2", settingsCaptor.getValue().displayName());
        assertEquals("https://models.example.com/v1/models", result.resolvedEndpoint());
        assertEquals(List.of("xmesh/gpt-5.2"), result.addedModels());
        assertEquals(List.of("xmesh/existing"), result.skippedModels());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void shouldSkipDiscoveryWhenModelSelectionIsEmpty() {
        ProviderModelDiscoveryService providerModelDiscoveryService = mock(ProviderModelDiscoveryService.class);
        ModelConfigAdminPort modelConfigAdminPort = mock(ModelConfigAdminPort.class);
        ProviderModelImportService service = new ProviderModelImportService(providerModelDiscoveryService,
                modelConfigAdminPort);

        ProviderModelImportService.ProviderImportResult result = service.importMissingModels("xmesh", List.of());

        verifyNoInteractions(providerModelDiscoveryService, modelConfigAdminPort);
        assertEquals(null, result.resolvedEndpoint());
        assertTrue(result.addedModels().isEmpty());
        assertTrue(result.skippedModels().isEmpty());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void shouldUseCatalogDefaultsWhenDiscoveredModelHasNoDefaultSettings() {
        ProviderModelDiscoveryService providerModelDiscoveryService = mock(ProviderModelDiscoveryService.class);
        ModelConfigAdminPort modelConfigAdminPort = mock(ModelConfigAdminPort.class);
        ModelConfigAdminPort.ModelSettingsSnapshot defaults = new ModelConfigAdminPort.ModelSettingsSnapshot(
                "openai", "Default", false, true, 32000, null);
        ModelConfigAdminPort.ModelsConfigSnapshot config = new ModelConfigAdminPort.ModelsConfigSnapshot(
                new LinkedHashMap<>(),
                defaults);

        when(providerModelDiscoveryService.discoverModelsForProvider("xmesh"))
                .thenReturn(new ProviderModelDiscoveryService.DiscoveryResult(
                        "https://models.example.com/v1/models",
                        List.of(new ProviderModelDiscoveryService.DiscoveredModel("xmesh", "gemini-2.5-pro",
                                "Gemini 2.5 Pro", "google", null))));
        when(modelConfigAdminPort.getConfig()).thenReturn(config);

        ProviderModelImportService service = new ProviderModelImportService(providerModelDiscoveryService,
                modelConfigAdminPort);

        service.importMissingModels("xmesh", List.of("xmesh/gemini-2.5-pro"));

        ArgumentCaptor<ModelConfigAdminPort.ModelSettingsSnapshot> settingsCaptor = ArgumentCaptor.forClass(
                ModelConfigAdminPort.ModelSettingsSnapshot.class);
        verify(modelConfigAdminPort).saveModel(eq("xmesh/gemini-2.5-pro"), eq(null), settingsCaptor.capture());
        ModelConfigAdminPort.ModelSettingsSnapshot savedSettings = settingsCaptor.getValue();
        assertEquals("xmesh", savedSettings.provider());
        assertEquals("Gemini 2.5 Pro", savedSettings.displayName());
        assertEquals(32000, savedSettings.maxInputTokens());
        assertEquals(false, savedSettings.supportsVision());
        assertEquals(true, savedSettings.supportsTemperature());
    }

    @Test
    void shouldUseBuiltInDefaultsWhenConfigIsMissingAndNormalizeRawSelections() {
        ProviderModelDiscoveryService providerModelDiscoveryService = mock(ProviderModelDiscoveryService.class);
        ModelConfigAdminPort modelConfigAdminPort = mock(ModelConfigAdminPort.class);

        when(providerModelDiscoveryService.discoverModelsForProvider(" XMESH "))
                .thenReturn(new ProviderModelDiscoveryService.DiscoveryResult(
                        "https://models.example.com/v1/models",
                        List.of(new ProviderModelDiscoveryService.DiscoveredModel("xmesh", "gpt-5.2", "GPT-5.2",
                                "openai", null))));
        when(modelConfigAdminPort.getConfig()).thenReturn(null);

        ProviderModelImportService service = new ProviderModelImportService(providerModelDiscoveryService,
                modelConfigAdminPort);

        ProviderModelImportService.ProviderImportResult result = service.importMissingModels(" XMESH ",
                java.util.Arrays.asList(" ", null, "gpt-5.2"));

        ArgumentCaptor<ModelConfigAdminPort.ModelSettingsSnapshot> settingsCaptor = ArgumentCaptor.forClass(
                ModelConfigAdminPort.ModelSettingsSnapshot.class);
        verify(modelConfigAdminPort).saveModel(eq("xmesh/gpt-5.2"), eq(null), settingsCaptor.capture());
        ModelConfigAdminPort.ModelSettingsSnapshot savedSettings = settingsCaptor.getValue();
        assertEquals("xmesh", savedSettings.provider());
        assertEquals("GPT-5.2", savedSettings.displayName());
        assertEquals(128000, savedSettings.maxInputTokens());
        assertTrue(savedSettings.supportsVision());
        assertTrue(savedSettings.supportsTemperature());
        assertNull(savedSettings.reasoning());
        assertEquals(List.of("xmesh/gpt-5.2"), result.addedModels());
    }

    @Test
    void shouldCopyReasoningDefaultsFromCatalogEntry() {
        ProviderModelDiscoveryService providerModelDiscoveryService = mock(ProviderModelDiscoveryService.class);
        ModelConfigAdminPort modelConfigAdminPort = mock(ModelConfigAdminPort.class);
        Map<String, ModelReasoningLevel> levels = new LinkedHashMap<>();
        levels.put("high", new ModelReasoningLevel(200000));
        levels.put("defaulted", null);
        ModelReasoningProfile reasoning = new ModelReasoningProfile("high", levels);
        ModelCatalogEntry importedSettings = new ModelCatalogEntry(
                "upstream", "GPT-5.2", false, false, 64000, reasoning);
        ModelConfigAdminPort.ModelsConfigSnapshot config = new ModelConfigAdminPort.ModelsConfigSnapshot(
                new LinkedHashMap<>(),
                null);

        when(providerModelDiscoveryService.discoverModelsForProvider("xmesh"))
                .thenReturn(new ProviderModelDiscoveryService.DiscoveryResult(
                        "https://models.example.com/v1/models",
                        List.of(new ProviderModelDiscoveryService.DiscoveredModel("xmesh", "gpt-5.2", "GPT-5.2",
                                "openai", importedSettings))));
        when(modelConfigAdminPort.getConfig()).thenReturn(config);

        ProviderModelImportService service = new ProviderModelImportService(providerModelDiscoveryService,
                modelConfigAdminPort);

        ProviderModelImportService.ProviderImportResult result = service.importMissingModels("xmesh", null);

        ArgumentCaptor<ModelConfigAdminPort.ModelSettingsSnapshot> settingsCaptor = ArgumentCaptor.forClass(
                ModelConfigAdminPort.ModelSettingsSnapshot.class);
        verify(modelConfigAdminPort).saveModel(eq("xmesh/gpt-5.2"), eq(null), settingsCaptor.capture());
        ModelConfigAdminPort.ModelSettingsSnapshot savedSettings = settingsCaptor.getValue();
        assertEquals("xmesh", savedSettings.provider());
        assertEquals("high", savedSettings.reasoning().defaultLevel());
        assertEquals(200000, savedSettings.reasoning().levels().get("high").maxInputTokens());
        assertEquals(128000, savedSettings.reasoning().levels().get("defaulted").maxInputTokens());
        assertEquals(List.of("xmesh/gpt-5.2"), result.addedModels());
    }

    @Test
    void shouldReturnDiscoveryErrorsWithoutThrowing() {
        ProviderModelDiscoveryService providerModelDiscoveryService = mock(ProviderModelDiscoveryService.class);
        ModelConfigAdminPort modelConfigAdminPort = mock(ModelConfigAdminPort.class);

        when(providerModelDiscoveryService.discoverModelsForProvider("xmesh"))
                .thenThrow(new IllegalStateException("bad gateway"));

        ProviderModelImportService service = new ProviderModelImportService(providerModelDiscoveryService,
                modelConfigAdminPort);

        ProviderModelImportService.ProviderImportResult result = service.importMissingModels("xmesh", null);

        assertTrue(result.addedModels().isEmpty());
        assertTrue(result.skippedModels().isEmpty());
        assertEquals(List.of("bad gateway"), result.errors());
    }

    @Test
    void shouldCollectSaveErrorsWithoutReportingModelAsAdded() {
        ProviderModelDiscoveryService providerModelDiscoveryService = mock(ProviderModelDiscoveryService.class);
        ModelConfigAdminPort modelConfigAdminPort = mock(ModelConfigAdminPort.class);
        ModelCatalogEntry importedSettings = new ModelCatalogEntry("xmesh", "GPT-5.2", true, true, 128000, null);
        ModelConfigAdminPort.ModelsConfigSnapshot config = new ModelConfigAdminPort.ModelsConfigSnapshot(
                new LinkedHashMap<>(),
                null);

        when(providerModelDiscoveryService.discoverModelsForProvider("xmesh"))
                .thenReturn(new ProviderModelDiscoveryService.DiscoveryResult(
                        "https://models.example.com/v1/models",
                        List.of(new ProviderModelDiscoveryService.DiscoveredModel("xmesh", "gpt-5.2", "GPT-5.2",
                                "openai", importedSettings))));
        when(modelConfigAdminPort.getConfig()).thenReturn(config);
        doThrow(new IllegalStateException("disk full")).when(modelConfigAdminPort)
                .saveModel(eq("xmesh/gpt-5.2"), eq(null), org.mockito.ArgumentMatchers.any());

        ProviderModelImportService service = new ProviderModelImportService(providerModelDiscoveryService,
                modelConfigAdminPort);

        ProviderModelImportService.ProviderImportResult result = service.importMissingModels("xmesh",
                List.of("xmesh/gpt-5.2"));

        assertTrue(result.addedModels().isEmpty());
        assertTrue(result.skippedModels().isEmpty());
        assertEquals(List.of("xmesh/gpt-5.2: disk full"), result.errors());
    }

    @Test
    void shouldRejectBlankProviderNameBeforeDiscovery() {
        ProviderModelDiscoveryService providerModelDiscoveryService = mock(ProviderModelDiscoveryService.class);
        ModelConfigAdminPort modelConfigAdminPort = mock(ModelConfigAdminPort.class);
        ProviderModelImportService service = new ProviderModelImportService(providerModelDiscoveryService,
                modelConfigAdminPort);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.importMissingModels(" ", null));

        assertEquals("Provider name is required", error.getMessage());
        verifyNoInteractions(providerModelDiscoveryService, modelConfigAdminPort);
    }
}
