package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.service.ModelRegistryService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.ProviderModelDiscoveryService;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for model definitions CRUD (models.json management).
 */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
@Slf4j
public class ModelsController {

    private final ModelConfigService modelConfigService;
    private final ModelSelectionService modelSelectionService;
    private final ProviderModelDiscoveryService providerModelDiscoveryService;
    private final ModelRegistryService modelRegistryService;

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
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Model '" + id + "' not found");
        }
        return Mono.just(ResponseEntity.ok().build());
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
                .map(m -> new AvailableModelDto(m.id(), m.displayName(), m.hasReasoning(),
                        m.reasoningLevels(), m.supportsVision()))
                .toList()));
        return Mono.just(ResponseEntity.ok(result));
    }

    /**
     * Discover live models for a specific provider profile.
     */
    @GetMapping("/discover/{provider}")
    public Mono<ResponseEntity<List<DiscoveredModelDto>>> discoverProviderModels(@PathVariable String provider) {
        try {
            List<ProviderModelDiscoveryService.DiscoveredModel> discoveredModels = providerModelDiscoveryService
                    .discoverModels(provider);
            List<DiscoveredModelDto> response = discoveredModels.stream()
                    .map(model -> new DiscoveredModelDto(model.provider(), model.id(), model.displayName(),
                            model.ownedBy()))
                    .toList();
            return Mono.just(ResponseEntity.ok(response));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    @PostMapping("/registry/resolve")
    public Mono<ResponseEntity<ResolveRegistryResponse>> resolveModelRegistry(
            @RequestBody ResolveRegistryRequest request) {
        String provider = requireValue(request != null ? request.provider() : null, "provider");
        String modelId = requireValue(request != null ? request.modelId() : null, "modelId");
        try {
            ModelRegistryService.ResolveResult result = modelRegistryService.resolveDefaults(provider, modelId);
            return Mono.just(ResponseEntity.ok(
                    new ResolveRegistryResponse(result.defaultSettings(), result.configSource(),
                            result.cacheStatus())));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    /**
     * Force reload models from workspace.
     */
    @PostMapping("/reload")
    public Mono<ResponseEntity<Void>> reloadModels() {
        modelConfigService.reload();
        return Mono.just(ResponseEntity.ok().build());
    }

    private String requireValue(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        return value.trim();
    }

    public record ResolveRegistryRequest(String provider, String modelId) {
    }

    public record ResolveRegistryResponse(ModelConfigService.ModelSettings defaultSettings, String configSource,
            String cacheStatus) {
    }

    private record AvailableModelDto(String id, String displayName, boolean hasReasoning,
            List<String> reasoningLevels, boolean supportsVision) {
    }

    private record DiscoveredModelDto(String provider, String id, String displayName, String ownedBy) {
    }
}
