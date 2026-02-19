package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelsControllerTest {

    private ModelConfigService modelConfigService;
    private ModelSelectionService modelSelectionService;
    private ModelsController controller;

    @BeforeEach
    void setUp() {
        modelConfigService = mock(ModelConfigService.class);
        modelSelectionService = mock(ModelSelectionService.class);
        controller = new ModelsController(modelConfigService, modelSelectionService);
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
    void shouldSaveModel() {
        ModelConfigService.ModelSettings settings = mock(ModelConfigService.ModelSettings.class);

        ResponseEntity<Void> result = controller.saveModel("gpt-5", settings).block();

        verify(modelConfigService).saveModel("gpt-5", settings);
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
                        List.of("low", "high"))));
        grouped.put("anthropic", List.of(
                new ModelSelectionService.AvailableModel("claude-opus-4-6", "anthropic", "Claude Opus", false,
                        List.of())));
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
}
