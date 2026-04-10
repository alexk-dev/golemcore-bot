package me.golemcore.bot.adapter.inbound.web.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.hive.HivePolicyBindingState;
import me.golemcore.bot.domain.service.HiveManagedPolicyService;
import me.golemcore.bot.domain.model.catalog.ModelCatalogEntry;
import me.golemcore.bot.domain.service.ModelRegistryService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.ProviderModelDiscoveryService;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.port.outbound.LlmPort;
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
import java.util.concurrent.TimeUnit;

/**
 * REST controller for model definitions CRUD (models.json management).
 */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
@Slf4j
public class ModelsController {

    private static final String TEST_PROMPT = "Reply in one short sentence: What model are you? Include your exact model name/version.";
    private static final int TEST_TIMEOUT_SECONDS = 30;

    private final ModelConfigService modelConfigService;
    private final ModelSelectionService modelSelectionService;
    private final ProviderModelDiscoveryService providerModelDiscoveryService;
    private final ModelRegistryService modelRegistryService;
    private final LlmPort llmPort;
    private final HiveManagedPolicyService hiveManagedPolicyService;

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
        rejectManagedHivePolicyCatalogMutation();
        modelConfigService.replaceConfig(newConfig);
        return Mono.just(ResponseEntity.ok(modelConfigService.getConfig()));
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
        String id = requireValue(request.id(), "id");
        String previousId = optionalValue(request.previousId());
        ModelConfigService.ModelSettings settings = request.settings();
        if (settings == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "settings is required");
        }
        rejectManagedHivePolicyCatalogMutation();
        modelConfigService.saveModel(id, previousId, settings);
        return Mono.just(ResponseEntity.ok().build());
    }

    /**
     * Delete a model definition.
     */
    @DeleteMapping
    public Mono<ResponseEntity<Void>> deleteModel(@RequestParam String id) {
        String normalizedId = requireValue(id, "id");
        rejectManagedHivePolicyCatalogMutation();
        boolean deleted = modelConfigService.deleteModel(normalizedId);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Model '" + normalizedId + "' not found");
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
                            model.ownedBy(), model.defaultCatalogEntry()))
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
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        String provider = requireValue(request.provider(), "provider");
        String modelId = requireValue(request.modelId(), "modelId");
        try {
            ModelRegistryService.ResolveResult result = modelRegistryService.resolveDefaults(provider, modelId);
            return Mono.just(ResponseEntity.ok(
                    new ResolveRegistryResponse(result.defaultCatalogEntry(), result.configSource(),
                            result.cacheStatus())));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
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
        String model = requireValue(request.model(), "model");
        log.info("[Models] Testing model: {}", model);
        try {
            LlmRequest llmRequest = LlmRequest.builder()
                    .model(model)
                    .messages(List.of(Message.builder()
                            .role("user")
                            .content(TEST_PROMPT)
                            .build()))
                    .temperature(0.0)
                    .maxTokens(256)
                    .build();
            LlmResponse response = llmPort.chat(llmRequest).get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String reply = response.getContent() != null ? response.getContent().trim() : "";
            log.info("[Models] Test response from {}: {}", model, reply);
            return Mono.just(ResponseEntity.ok(new TestModelResponse(true, reply, null)));
        } catch (Exception e) { // NOSONAR - catching all for user-facing diagnostic
            String errorMessage = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            log.warn("[Models] Test failed for {}: {}", model, errorMessage);
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new TestModelResponse(false, null, errorMessage)));
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

    private String optionalValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private void rejectManagedHivePolicyCatalogMutation() {
        HivePolicyBindingState bindingState = hiveManagedPolicyService.getBindingState().orElse(null);
        if (bindingState == null || !bindingState.hasActiveBinding()) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Model catalog is managed by Hive policy group \"" + bindingState.getPolicyGroupId()
                        + "\" and is read-only");
    }

    public record ResolveRegistryRequest(String provider, String modelId) {
    }

    public record SaveModelRequest(String id, String previousId, ModelConfigService.ModelSettings settings) {
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
            List<String> reasoningLevels, boolean supportsVision) {
    }

    private record DiscoveredModelDto(String provider, String id, String displayName, String ownedBy,
            @JsonProperty("defaultSettings") ModelCatalogEntry defaultSettings) {
    }
}
