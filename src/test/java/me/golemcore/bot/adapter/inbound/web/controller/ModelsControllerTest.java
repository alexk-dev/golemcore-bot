package me.golemcore.bot.adapter.inbound.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import me.golemcore.bot.application.models.ModelManagementFacade;
import me.golemcore.bot.application.models.ModelRegistryService;
import me.golemcore.bot.application.models.ProviderModelDiscoveryService;
import me.golemcore.bot.domain.model.catalog.ModelCatalogEntry;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.port.outbound.ModelConfigAdminPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class ModelsControllerTest {

    private ModelManagementFacade modelManagementFacade;
    private ObjectMapper objectMapper;
    private ModelsController controller;

    @BeforeEach
    void setUp() {
        modelManagementFacade = mock(ModelManagementFacade.class);
        objectMapper = new ObjectMapper();
        controller = new ModelsController(modelManagementFacade);
    }

    @Test
    void shouldReturnModelsConfig() {
        ModelConfigAdminPort.ModelsConfigSnapshot config = new ModelConfigAdminPort.ModelsConfigSnapshot(
                Map.of(),
                null);
        when(modelManagementFacade.getModelsConfig()).thenReturn(config);

        ResponseEntity<ModelsController.ModelsConfigDto> result = controller.getModelsConfig().block();

        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(0, result.getBody().models().size());
    }

    @Test
    void shouldReplaceModelsConfig() {
        ModelsController.ModelsConfigDto newConfig = new ModelsController.ModelsConfigDto(Map.of(), null);
        ModelConfigAdminPort.ModelsConfigSnapshot savedConfig = new ModelConfigAdminPort.ModelsConfigSnapshot(
                Map.of(),
                null);
        when(modelManagementFacade.replaceModelsConfig(new ModelConfigAdminPort.ModelsConfigSnapshot(Map.of(), null)))
                .thenReturn(savedConfig);

        ResponseEntity<ModelsController.ModelsConfigDto> result = controller.replaceModelsConfig(newConfig).block();

        verify(modelManagementFacade)
                .replaceModelsConfig(new ModelConfigAdminPort.ModelsConfigSnapshot(Map.of(), null));
        assertEquals(0, result.getBody().models().size());
    }

    @Test
    void shouldSaveModel() {
        ModelsController.ModelSettingsDto settings = new ModelsController.ModelSettingsDto(
                "openai",
                "GPT-5",
                true,
                true,
                128000,
                null);

        ResponseEntity<Void> result = controller
                .saveModel(new ModelsController.SaveModelRequest("gpt-5", null, settings))
                .block();

        verify(modelManagementFacade).saveModel("gpt-5", null, new ModelConfigAdminPort.ModelSettingsSnapshot(
                "openai", "GPT-5", true, true, 128000, null));
        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void shouldMapSaveModelBadRequest() {
        ModelsController.ModelSettingsDto settings = new ModelsController.ModelSettingsDto(
                "openai",
                "GPT-5",
                true,
                true,
                128000,
                null);
        doThrow(new IllegalArgumentException("id is required"))
                .when(modelManagementFacade).saveModel(" ", null,
                        new ModelConfigAdminPort.ModelSettingsSnapshot("openai", "GPT-5", true, true, 128000, null));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.saveModel(new ModelsController.SaveModelRequest(" ", null, settings)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("id is required", ex.getReason());
    }

    @Test
    void shouldDeleteModelSuccessfully() {
        ResponseEntity<Void> result = controller.deleteModel("gpt-5").block();

        verify(modelManagementFacade).deleteModel("gpt-5");
        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void shouldReturnNotFoundWhenDeletingNonexistentModel() {
        doThrow(new NoSuchElementException("Model 'nonexistent' not found"))
                .when(modelManagementFacade).deleteModel("nonexistent");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteModel("nonexistent").block());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("Model 'nonexistent' not found", ex.getReason());
    }

    @Test
    void shouldReturnAvailableModelsGroupedByProvider() {
        Map<String, List<ModelSelectionService.AvailableModel>> grouped = new LinkedHashMap<>();
        grouped.put("openai", List.of(
                new ModelSelectionService.AvailableModel("gpt-5", "openai", "GPT-5", true,
                        List.of("low", "high"), true)));
        grouped.put("anthropic", List.of(
                new ModelSelectionService.AvailableModel("claude-opus-4-6", "anthropic", "Claude Opus", false,
                        List.of(), false)));
        when(modelManagementFacade.getAvailableModels()).thenReturn(grouped);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, List<?>>> result = (ResponseEntity<Map<String, List<?>>>) (ResponseEntity<?>) controller
                .getAvailableModels().block();

        assertNotNull(result.getBody());
        assertTrue(result.getBody().containsKey("openai"));
        assertEquals(1, result.getBody().get("openai").size());
    }

    @Test
    void shouldReloadModels() {
        ResponseEntity<Void> result = controller.reloadModels().block();

        verify(modelManagementFacade).reloadModels();
        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void shouldDiscoverProviderModels() {
        ModelCatalogEntry defaultSettings = new ModelCatalogEntry("xmesh", "GPT-5.2", true, false, 200000, null);
        List<ProviderModelDiscoveryService.DiscoveredModel> discovered = List.of(
                new ProviderModelDiscoveryService.DiscoveredModel("xmesh", "gpt-5.2", "GPT-5.2", "openai",
                        defaultSettings),
                new ProviderModelDiscoveryService.DiscoveredModel("xmesh", "gemini-2.5-pro", "Gemini 2.5 Pro",
                        "google", null));
        when(modelManagementFacade.discoverProviderModels("xmesh")).thenReturn(discovered);

        @SuppressWarnings("unchecked")
        ResponseEntity<List<?>> result = (ResponseEntity<List<?>>) (ResponseEntity<?>) controller
                .discoverProviderModels("xmesh").block();

        assertEquals(2, result.getBody().size());
        Map<?, ?> firstModel = objectMapper.convertValue(result.getBody().getFirst(), Map.class);
        Map<?, ?> firstDefaults = objectMapper.convertValue(firstModel.get("defaultSettings"), Map.class);
        assertEquals("xmesh", firstDefaults.get("provider"));
        assertEquals(false, firstDefaults.get("supportsTemperature"));
    }

    @Test
    void shouldReturnBadRequestWhenProviderDiscoveryRequestIsInvalid() {
        when(modelManagementFacade.discoverProviderModels("missing"))
                .thenThrow(new IllegalArgumentException("Provider 'missing' is not configured"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.discoverProviderModels("missing").block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Provider 'missing' is not configured", ex.getReason());
    }

    @Test
    void shouldResolveModelRegistryDefaults() {
        ModelCatalogEntry settings = new ModelCatalogEntry("openai", "GPT-5.1", true, false, 1000000, null);
        when(modelManagementFacade.resolveModelRegistry("openai", "gpt-5.1"))
                .thenReturn(new ModelRegistryService.ResolveResult(settings, "provider", "remote-hit"));

        ResponseEntity<ModelsController.ResolveRegistryResponse> result = controller
                .resolveModelRegistry(new ModelsController.ResolveRegistryRequest("openai", "gpt-5.1"))
                .block();

        Map<?, ?> body = objectMapper.convertValue(result.getBody(), Map.class);
        assertEquals("provider", body.get("configSource"));
        assertEquals("remote-hit", body.get("cacheStatus"));
        Map<?, ?> defaultSettings = objectMapper.convertValue(body.get("defaultSettings"), Map.class);
        assertEquals("openai", defaultSettings.get("provider"));
        assertEquals("GPT-5.1", defaultSettings.get("displayName"));
    }

    @Test
    void shouldRejectNullModelRegistryResolveRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.resolveModelRegistry(null).block());

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("request body is required", ex.getReason());
        verifyNoInteractions(modelManagementFacade);
    }

    @Test
    void shouldRejectModelRegistryResolveRequestWithBlankProvider() {
        when(modelManagementFacade.resolveModelRegistry("  ", "gpt-5.1"))
                .thenThrow(new IllegalArgumentException("provider is required"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.resolveModelRegistry(new ModelsController.ResolveRegistryRequest("  ", "gpt-5.1"))
                        .block());

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("provider is required", ex.getReason());
    }

    @Test
    void shouldTestModelSuccessfully() {
        when(modelManagementFacade.testModel("gpt-5"))
                .thenReturn(new ModelManagementFacade.TestModelResult(true, "I am GPT-5, version 5.0.", null));

        ResponseEntity<ModelsController.TestModelResponse> result = controller
                .testModel(new ModelsController.TestModelRequest("gpt-5"))
                .block();

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertTrue(result.getBody().success());
        assertEquals("I am GPT-5, version 5.0.", result.getBody().reply());
        assertNull(result.getBody().error());
    }

    @Test
    void shouldReturnErrorWhenTestModelFails() {
        when(modelManagementFacade.testModel("gpt-5"))
                .thenReturn(new ModelManagementFacade.TestModelResult(false, null, "Connection refused"));

        ResponseEntity<ModelsController.TestModelResponse> result = controller
                .testModel(new ModelsController.TestModelRequest("gpt-5"))
                .block();

        assertEquals(HttpStatus.BAD_GATEWAY, result.getStatusCode());
        assertFalse(result.getBody().success());
        assertEquals("Connection refused", result.getBody().error());
    }
}
