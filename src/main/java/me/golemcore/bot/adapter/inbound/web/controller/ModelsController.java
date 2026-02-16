package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for model definitions CRUD (models.json management).
 */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelsController {

    private final ModelConfigService modelConfigService;
    private final ModelSelectionService modelSelectionService;

    /**
     * Get full models config (all models + defaults).
     */
    @GetMapping
    public Mono<ResponseEntity<ModelConfigService.ModelsConfig>> getModelsConfig() {
        return Mono.just(ResponseEntity.ok(modelConfigService.getConfig()));
    }

    /**
     * Replace entire models config.
     */
    @PutMapping
    public Mono<ResponseEntity<ModelConfigService.ModelsConfig>> replaceModelsConfig(
            @RequestBody ModelConfigService.ModelsConfig newConfig) {
        modelConfigService.replaceConfig(newConfig);
        return Mono.just(ResponseEntity.ok(modelConfigService.getConfig()));
    }

    /**
     * Add or update a single model definition.
     */
    @PostMapping("/{id}")
    public Mono<ResponseEntity<Void>> saveModel(
            @PathVariable String id,
            @RequestBody ModelConfigService.ModelSettings settings) {
        modelConfigService.saveModel(id, settings);
        return Mono.just(ResponseEntity.ok().build());
    }

    /**
     * Delete a model definition.
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteModel(@PathVariable String id) {
        boolean deleted = modelConfigService.deleteModel(id);
        if (deleted) {
            return Mono.just(ResponseEntity.ok().build());
        }
        return Mono.just(ResponseEntity.notFound().build());
    }

    /**
     * Get models filtered by allowed providers (for tier dropdowns).
     */
    @GetMapping("/available")
    public Mono<ResponseEntity<Map<String, List<AvailableModelDto>>>> getAvailableModels() {
        Map<String, List<ModelSelectionService.AvailableModel>> grouped = modelSelectionService
                .getAvailableModelsGrouped();
        Map<String, List<AvailableModelDto>> result = new LinkedHashMap<>();
        grouped.forEach((provider, models) -> result.put(provider, models.stream()
                .map(m -> new AvailableModelDto(m.id(), m.displayName(), m.hasReasoning(), m.reasoningLevels()))
                .toList()));
        return Mono.just(ResponseEntity.ok(result));
    }

    /**
     * Force reload models from workspace.
     */
    @PostMapping("/reload")
    public Mono<ResponseEntity<Void>> reloadModels() {
        modelConfigService.reload();
        return Mono.just(ResponseEntity.ok().build());
    }

    private record AvailableModelDto(String id, String displayName, boolean hasReasoning,
            List<String> reasoningLevels) {
    }
}
