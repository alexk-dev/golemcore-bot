package me.golemcore.bot.adapter.inbound.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.hive.HivePolicyBindingState;
import me.golemcore.bot.domain.service.HiveManagedPolicyService;
import me.golemcore.bot.domain.service.ModelRegistryService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.ProviderModelDiscoveryService;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelsControllerTest {

    private ModelConfigService modelConfigService;
    private ModelSelectionService modelSelectionService;
    private ProviderModelDiscoveryService providerModelDiscoveryService;
    private ModelRegistryService modelRegistryService;
    private LlmPort llmPort;
    private HiveManagedPolicyService hiveManagedPolicyService;
    private ObjectMapper objectMapper;
    private ModelsController controller;

    @BeforeEach
    void setUp() {
        modelConfigService = mock(ModelConfigService.class);
        modelSelectionService = mock(ModelSelectionService.class);
        providerModelDiscoveryService = mock(ProviderModelDiscoveryService.class);
        modelRegistryService = mock(ModelRegistryService.class);
        llmPort = mock(LlmPort.class);
        hiveManagedPolicyService = mock(HiveManagedPolicyService.class);
        objectMapper = new ObjectMapper();
        controller = new ModelsController(modelConfigService, modelSelectionService, providerModelDiscoveryService,
                modelRegistryService, llmPort, hiveManagedPolicyService);
        when(hiveManagedPolicyService.getBindingState()).thenReturn(java.util.Optional.empty());
    }

    @Test
    void shouldReturnModelsConfig() {
        ModelConfigService.ModelsConfig config = mock(ModelConfigService.ModelsConfig.class);
        when(modelConfigService.getConfig()).thenReturn(config);

        ResponseEntity<ModelConfigService.ModelsConfig> result = controller.getModelsConfig().block();

        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(config, result.getBody());
    }

    @Test
    void shouldReplaceModelsConfig() {
        ModelConfigService.ModelsConfig newConfig = mock(ModelConfigService.ModelsConfig.class);
        ModelConfigService.ModelsConfig savedConfig = mock(ModelConfigService.ModelsConfig.class);
        when(modelConfigService.getConfig()).thenReturn(savedConfig);

        ResponseEntity<ModelConfigService.ModelsConfig> result = controller.replaceModelsConfig(newConfig).block();

        verify(modelConfigService).replaceConfig(newConfig);
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(savedConfig, result.getBody());
    }

    @Test
    void shouldRejectReplacingModelsConfigWhenManagedByHivePolicy() {
        when(hiveManagedPolicyService.getBindingState())
                .thenReturn(java.util.Optional.of(HivePolicyBindingState.builder()
                        .policyGroupId("pg-1")
                        .build()));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.replaceModelsConfig(mock(ModelConfigService.ModelsConfig.class)).block());

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("Model catalog is managed by Hive policy group \"pg-1\" and is read-only", error.getReason());
    }

    @Test
    void shouldSaveModel() {
        ModelConfigService.ModelSettings settings = mock(ModelConfigService.ModelSettings.class);

        ResponseEntity<Void> result = controller
                .saveModel(new ModelsController.SaveModelRequest("gpt-5", null, settings))
                .block();

        verify(modelConfigService).saveModel("gpt-5", null, settings);
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void shouldSaveModelWithSlashHeavyId() {
        ModelConfigService.ModelSettings settings = mock(ModelConfigService.ModelSettings.class);

        ResponseEntity<Void> result = controller
                .saveModel(new ModelsController.SaveModelRequest(
                        "openrouter/qwen/model-name:version",
                        null,
                        settings))
                .block();

        verify(modelConfigService).saveModel("openrouter/qwen/model-name:version", null, settings);
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void shouldSaveModelWithPreviousIdForRename() {
        ModelConfigService.ModelSettings settings = mock(ModelConfigService.ModelSettings.class);

        ResponseEntity<Void> result = controller
                .saveModel(new ModelsController.SaveModelRequest(
                        "openrouter/qwen/model-name:version",
                        "qwen/model-name:version",
                        settings))
                .block();

        verify(modelConfigService).saveModel(
                "openrouter/qwen/model-name:version",
                "qwen/model-name:version",
                settings);
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void shouldDeleteModelSuccessfully() {
        when(modelConfigService.deleteModel("gpt-5")).thenReturn(true);

        ResponseEntity<Void> result = controller.deleteModel("gpt-5").block();

        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void shouldReturnNotFoundWhenDeletingNonexistentModel() {
        when(modelConfigService.deleteModel("nonexistent")).thenReturn(false);

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
        when(modelSelectionService.getAvailableModelsGrouped()).thenReturn(grouped);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, List<?>>> result = (ResponseEntity<Map<String, List<?>>>) (ResponseEntity<?>) controller
                .getAvailableModels().block();

        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertTrue(result.getBody().containsKey("openai"));
        assertTrue(result.getBody().containsKey("anthropic"));
        assertEquals(1, result.getBody().get("openai").size());
    }

    @Test
    void shouldReloadModels() {
        ResponseEntity<Void> result = controller.reloadModels().block();

        verify(modelConfigService).reload();
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void shouldDiscoverProviderModels() {
        ModelConfigService.ModelSettings defaultSettings = new ModelConfigService.ModelSettings();
        defaultSettings.setProvider("xmesh");
        defaultSettings.setDisplayName("GPT-5.2");
        defaultSettings.setSupportsVision(true);
        defaultSettings.setSupportsTemperature(false);
        defaultSettings.setMaxInputTokens(200000);
        List<ProviderModelDiscoveryService.DiscoveredModel> discovered = List.of(
                new ProviderModelDiscoveryService.DiscoveredModel("xmesh", "gpt-5.2", "GPT-5.2", "openai",
                        defaultSettings),
                new ProviderModelDiscoveryService.DiscoveredModel("xmesh", "gemini-2.5-pro", "Gemini 2.5 Pro",
                        "google", null));
        when(providerModelDiscoveryService.discoverModels("xmesh")).thenReturn(discovered);

        @SuppressWarnings("unchecked")
        ResponseEntity<List<?>> result = (ResponseEntity<List<?>>) (ResponseEntity<?>) controller
                .discoverProviderModels("xmesh").block();

        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals(2, result.getBody().size());
        Map<?, ?> firstModel = objectMapper.convertValue(result.getBody().getFirst(), Map.class);
        Map<?, ?> firstDefaults = objectMapper.convertValue(firstModel.get("defaultSettings"), Map.class);
        assertEquals("xmesh", firstDefaults.get("provider"));
        assertEquals(false, firstDefaults.get("supportsTemperature"));
    }

    @Test
    void shouldReturnBadRequestWhenProviderDiscoveryRequestIsInvalid() {
        when(providerModelDiscoveryService.discoverModels("missing"))
                .thenThrow(new IllegalArgumentException("Provider 'missing' is not configured"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.discoverProviderModels("missing").block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Provider 'missing' is not configured", ex.getReason());
    }

    @Test
    void shouldResolveModelRegistryDefaults() {
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider("openai");
        settings.setDisplayName("GPT-5.1");
        settings.setSupportsVision(true);
        settings.setSupportsTemperature(false);
        settings.setMaxInputTokens(1000000);
        when(modelRegistryService.resolveDefaults("openai", "gpt-5.1"))
                .thenReturn(new ModelRegistryService.ResolveResult(settings, "provider", "remote-hit"));

        ResponseEntity<ModelsController.ResolveRegistryResponse> result = controller
                .resolveModelRegistry(new ModelsController.ResolveRegistryRequest("openai", "gpt-5.1"))
                .block();

        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        Map<?, ?> body = objectMapper.convertValue(result.getBody(), Map.class);
        assertEquals("provider", body.get("configSource"));
        assertEquals("remote-hit", body.get("cacheStatus"));
        Map<?, ?> defaultSettings = objectMapper.convertValue(body.get("defaultSettings"), Map.class);
        assertEquals("openai", defaultSettings.get("provider"));
        assertEquals("GPT-5.1", defaultSettings.get("displayName"));
    }

    @Test
    void shouldReturnMissWhenModelRegistryDefaultsAreUnavailable() {
        when(modelRegistryService.resolveDefaults("openai", "unknown-model"))
                .thenReturn(new ModelRegistryService.ResolveResult(null, null, "miss"));

        ResponseEntity<ModelsController.ResolveRegistryResponse> result = controller
                .resolveModelRegistry(new ModelsController.ResolveRegistryRequest("openai", "unknown-model"))
                .block();

        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        Map<?, ?> body = objectMapper.convertValue(result.getBody(), Map.class);
        assertNull(body.get("defaultSettings"));
        assertNull(body.get("configSource"));
        assertEquals("miss", body.get("cacheStatus"));
    }

    @Test
    void shouldRejectNullModelRegistryResolveRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.resolveModelRegistry(null).block());

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("request body is required", ex.getReason());
        verifyNoInteractions(modelRegistryService);
    }

    @Test
    void shouldRejectModelRegistryResolveRequestWithBlankProvider() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.resolveModelRegistry(new ModelsController.ResolveRegistryRequest("  ", "gpt-5.1"))
                        .block());

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("provider is required", ex.getReason());
    }

    @Test
    void shouldRejectModelRegistryResolveRequestWithBlankModelId() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.resolveModelRegistry(new ModelsController.ResolveRegistryRequest("openai", "  "))
                        .block());

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("modelId is required", ex.getReason());
    }

    @Test
    void shouldTestModelSuccessfully() {
        // Arrange
        LlmResponse llmResponse = LlmResponse.builder()
                .content("I am GPT-5, version 5.0.")
                .build();
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(llmResponse));

        // Act
        ResponseEntity<ModelsController.TestModelResponse> result = controller
                .testModel(new ModelsController.TestModelRequest("gpt-5"))
                .block();

        // Assert
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        ModelsController.TestModelResponse body = result.getBody();
        assertNotNull(body);
        assertTrue(body.success());
        assertEquals("I am GPT-5, version 5.0.", body.reply());
        assertNull(body.error());
    }

    @Test
    void shouldReturnErrorWhenTestModelFails() {
        // Arrange
        CompletableFuture<LlmResponse> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Connection refused"));
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(failedFuture);

        // Act
        ResponseEntity<ModelsController.TestModelResponse> result = controller
                .testModel(new ModelsController.TestModelRequest("gpt-5"))
                .block();

        // Assert
        assertNotNull(result);
        assertEquals(HttpStatus.BAD_GATEWAY, result.getStatusCode());
        ModelsController.TestModelResponse body = result.getBody();
        assertNotNull(body);
        assertFalse(body.success());
        assertNull(body.reply());
        assertEquals("Connection refused", body.error());
    }

    @Test
    void shouldRejectTestModelWithBlankModel() {
        // Arrange & Act & Assert
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.testModel(new ModelsController.TestModelRequest("  ")).block());

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("model is required", ex.getReason());
        verifyNoInteractions(llmPort);
    }
}
