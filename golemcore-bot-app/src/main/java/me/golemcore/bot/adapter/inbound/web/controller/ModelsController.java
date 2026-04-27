package me.golemcore.bot.adapter.inbound.web.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import me.golemcore.bot.application.models.ModelManagementFacade;
import me.golemcore.bot.application.models.ModelRegistryService;
import me.golemcore.bot.application.models.ProviderModelDiscoveryService;
import me.golemcore.bot.domain.model.catalog.ModelCatalogEntry;
import me.golemcore.bot.port.outbound.ModelConfigAdminPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * REST controller for model definitions CRUD (models.json management).
 */
@RestController
@RequestMapping("/api/models")
public class ModelsController {

    private final ModelManagementFacade modelManagementFacade;

    public ModelsController(ModelManagementFacade modelManagementFacade) {
        this.modelManagementFacade = modelManagementFacade;
    }

    /**
     * Get full models config (all models + defaults).
     */
    @GetMapping
    public Mono<ResponseEntity<ModelsConfigDto>> getModelsConfig() {
        return Mono.just(ResponseEntity.ok(toDto(modelManagementFacade.getModelsConfig())));
    }

    /**
     * Replace entire models config.
     */
    @PutMapping
    public Mono<ResponseEntity<ModelsConfigDto>> replaceModelsConfig(
            @RequestBody ModelsConfigDto newConfig) {
        try {
            return Mono
                    .just(ResponseEntity.ok(toDto(modelManagementFacade.replaceModelsConfig(toSnapshot(newConfig)))));
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    /**
     * Add or update a single model definition.
     */
    @PostMapping
    public Mono<ResponseEntity<Void>> saveModel(
            @RequestBody SaveModelRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        try {
            modelManagementFacade.saveModel(request.id(), request.previousId(), toSnapshot(request.settings()));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
        return Mono.just(ResponseEntity.ok().build());
    }

    /**
     * Delete a model definition.
     */
    @DeleteMapping
    public Mono<ResponseEntity<Void>> deleteModel(@RequestParam String id) {
        try {
            modelManagementFacade.deleteModel(id);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
        return Mono.just(ResponseEntity.ok().build());
    }

    /**
     * Get models filtered by allowed providers (for tier dropdowns).
     */
    @GetMapping("/available")
    public Mono<ResponseEntity<Map<String, List<AvailableModelDto>>>> getAvailableModels() {
        Map<String, List<me.golemcore.bot.domain.service.ModelSelectionService.AvailableModel>> grouped = modelManagementFacade
                .getAvailableModels();
        Map<String, List<AvailableModelDto>> result = new LinkedHashMap<>();
        grouped.forEach((provider, models) -> result.put(provider, models.stream()
                .map(model -> new AvailableModelDto(model.id(), model.displayName(), model.hasReasoning(),
                        model.reasoningLevels(), model.supportsVision(), model.supportsTemperature()))
                .toList()));
        return Mono.just(ResponseEntity.ok(result));
    }

    /**
     * Discover live models for a specific provider profile.
     */
    @GetMapping("/discover/{provider}")
    public Mono<ResponseEntity<List<DiscoveredModelDto>>> discoverProviderModels(@PathVariable String provider) {
        try {
            List<ProviderModelDiscoveryService.DiscoveredModel> discoveredModels = modelManagementFacade
                    .discoverProviderModels(provider);
            List<DiscoveredModelDto> response = discoveredModels.stream()
                    .map(model -> new DiscoveredModelDto(model.provider(), model.id(), model.displayName(),
                            model.ownedBy(), model.defaultCatalogEntry()))
                    .toList();
            return Mono.just(ResponseEntity.ok(response));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage());
        }
    }

    @PostMapping("/registry/resolve")
    public Mono<ResponseEntity<ResolveRegistryResponse>> resolveModelRegistry(
            @RequestBody ResolveRegistryRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        try {
            ModelRegistryService.ResolveResult result = modelManagementFacade
                    .resolveModelRegistry(request.provider(), request.modelId());
            return Mono.just(ResponseEntity.ok(
                    new ResolveRegistryResponse(result.defaultCatalogEntry(), result.configSource(),
                            result.cacheStatus())));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    /**
     * Send a fixed identification prompt to a model and return the raw response.
     */
    @PostMapping("/test")
    public Mono<ResponseEntity<TestModelResponse>> testModel(@RequestBody TestModelRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        try {
            ModelManagementFacade.TestModelResult result = modelManagementFacade.testModel(request.model());
            if (result.success()) {
                return Mono.just(ResponseEntity.ok(new TestModelResponse(true, result.reply(), null)));
            }
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new TestModelResponse(false, null, result.error())));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    /**
     * Force reload models from workspace.
     */
    @PostMapping("/reload")
    public Mono<ResponseEntity<Void>> reloadModels() {
        modelManagementFacade.reloadModels();
        return Mono.just(ResponseEntity.ok().build());
    }

    public record ResolveRegistryRequest(String provider, String modelId) {
    }

    public record SaveModelRequest(String id, String previousId, ModelSettingsDto settings) {
    }

    public record ResolveRegistryResponse(@JsonProperty("defaultSettings") ModelCatalogEntry defaultSettings,
            String configSource,
            String cacheStatus) {
    }

    public record TestModelRequest(String model) {
    }

    public record TestModelResponse(boolean success, String reply, String error) {
    }

    private record AvailableModelDto(String id, String displayName, boolean hasReasoning,
            List<String> reasoningLevels, boolean supportsVision, boolean supportsTemperature) {
    }

    private record DiscoveredModelDto(String provider, String id, String displayName, String ownedBy,
            @JsonProperty("defaultSettings") ModelCatalogEntry defaultSettings) {
    }

    public record ModelsConfigDto(Map<String, ModelSettingsDto> models, ModelSettingsDto defaults) {
    }

    public record ModelSettingsDto(
            String provider,
            String displayName,
            boolean supportsVision,
            boolean supportsTemperature,
            int maxInputTokens,
            ReasoningConfigDto reasoning) {
    }

    public record ReasoningConfigDto(@JsonProperty("default") String defaultLevel,
            Map<String, ReasoningLevelDto> levels) {
    }

    public record ReasoningLevelDto(int maxInputTokens) {
    }

    private ModelsConfigDto toDto(ModelConfigAdminPort.ModelsConfigSnapshot snapshot) {
        Map<String, ModelSettingsDto> models = new LinkedHashMap<>();
        snapshot.models().forEach((key, value) -> models.put(key, toModelSettingsDto(value)));
        return new ModelsConfigDto(models, toModelSettingsDto(snapshot.defaults()));
    }

    private ModelSettingsDto toModelSettingsDto(ModelConfigAdminPort.ModelSettingsSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new ModelSettingsDto(
                snapshot.provider(),
                snapshot.displayName(),
                snapshot.supportsVision(),
                snapshot.supportsTemperature(),
                snapshot.maxInputTokens(),
                toReasoningConfigDto(snapshot.reasoning()));
    }

    private ReasoningConfigDto toReasoningConfigDto(ModelConfigAdminPort.ReasoningConfigSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        Map<String, ReasoningLevelDto> levels = new LinkedHashMap<>();
        snapshot.levels().forEach((key, value) -> levels.put(key, new ReasoningLevelDto(value.maxInputTokens())));
        return new ReasoningConfigDto(snapshot.defaultLevel(), levels);
    }

    private ModelConfigAdminPort.ModelsConfigSnapshot toSnapshot(ModelsConfigDto dto) {
        Map<String, ModelConfigAdminPort.ModelSettingsSnapshot> models = new LinkedHashMap<>();
        if (dto != null && dto.models() != null) {
            dto.models().forEach((key, value) -> models.put(key, toSnapshot(value)));
        }
        return new ModelConfigAdminPort.ModelsConfigSnapshot(models, dto != null ? toSnapshot(dto.defaults()) : null);
    }

    private ModelConfigAdminPort.ModelSettingsSnapshot toSnapshot(ModelSettingsDto dto) {
        if (dto == null) {
            return null;
        }
        return new ModelConfigAdminPort.ModelSettingsSnapshot(
                dto.provider(),
                dto.displayName(),
                dto.supportsVision(),
                dto.supportsTemperature(),
                dto.maxInputTokens(),
                toSnapshot(dto.reasoning()));
    }

    private ModelConfigAdminPort.ReasoningConfigSnapshot toSnapshot(ReasoningConfigDto dto) {
        if (dto == null) {
            return null;
        }
        Map<String, ModelConfigAdminPort.ReasoningLevelSnapshot> levels = new LinkedHashMap<>();
        if (dto.levels() != null) {
            dto.levels().forEach((key, value) -> levels.put(key,
                    new ModelConfigAdminPort.ReasoningLevelSnapshot(value.maxInputTokens())));
        }
        return new ModelConfigAdminPort.ReasoningConfigSnapshot(dto.defaultLevel(), levels);
    }
}
