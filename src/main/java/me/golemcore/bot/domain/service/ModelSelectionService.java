package me.golemcore.bot.domain.service;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralizes tier-to-model resolution with user override support. Used by
 * DefaultToolLoopSystem and AutoCompactionSystem to avoid duplicated
 * tier-to-model mapping logic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelSelectionService {

    private final BotProperties properties;
    private final ModelConfigService modelConfigService;
    private final UserPreferencesService preferencesService;

    /**
     * Resolve the model and reasoning level for a tier. Checks user overrides
     * first, falls back to router config defaults. Auto-fills default reasoning for
     * reasoning-required models.
     */
    public ModelSelection resolveForTier(String tier) {
        String effectiveTier = tier != null ? tier : "balanced";
        UserPreferences prefs = preferencesService.getPreferences();

        // 1) Check user override
        if (prefs.getTierOverrides() != null) {
            UserPreferences.TierOverride override = prefs.getTierOverrides().get(effectiveTier);
            if (override != null && override.getModel() != null) {
                String reasoning = override.getReasoning();
                // Auto-fill reasoning default for reasoning-required models if not set
                if (reasoning == null && modelConfigService.isReasoningRequired(override.getModel())) {
                    reasoning = modelConfigService.getDefaultReasoningLevel(override.getModel());
                }
                log.debug("[ModelSelection] Using user override for tier {}: model={}, reasoning={}",
                        effectiveTier, override.getModel(), reasoning);
                return new ModelSelection(override.getModel(), reasoning);
            }
        }

        // 2) Fall back to router config
        return resolveFromRouter(effectiveTier);
    }

    /**
     * Convenience method — resolve just the model name for a tier.
     */
    public String resolveModelName(String tier) {
        return resolveForTier(tier).model();
    }

    /**
     * Resolve the maximum input tokens for a tier, accounting for user overrides
     * and per-reasoning-level limits.
     */
    public int resolveMaxInputTokens(String tier) {
        ModelSelection selection = resolveForTier(tier);
        if (selection.model() == null) {
            return properties.getAutoCompact().getMaxContextTokens();
        }
        return modelConfigService.getMaxInputTokens(selection.model(), selection.reasoning());
    }

    /**
     * Get list of available models filtered by allowed providers.
     */
    public List<AvailableModel> getAvailableModels() {
        List<String> allowedProviders = properties.getModelSelection().getAllowedProviders();
        Map<String, ModelConfigService.ModelSettings> filtered = modelConfigService
                .getModelsForProviders(allowedProviders);

        List<AvailableModel> result = new ArrayList<>();
        for (Map.Entry<String, ModelConfigService.ModelSettings> entry : filtered.entrySet()) {
            ModelConfigService.ModelSettings settings = entry.getValue();
            String displayName = settings.getDisplayName() != null ? settings.getDisplayName() : entry.getKey();
            boolean hasReasoning = modelConfigService.isReasoningRequired(entry.getKey());
            List<String> reasoningLevels = hasReasoning
                    ? modelConfigService.getAvailableReasoningLevels(entry.getKey())
                    : List.of();
            result.add(new AvailableModel(entry.getKey(), settings.getProvider(), displayName,
                    hasReasoning, reasoningLevels));
        }
        return result;
    }

    /**
     * Get available models grouped by provider.
     */
    public Map<String, List<AvailableModel>> getAvailableModelsGrouped() {
        Map<String, List<AvailableModel>> grouped = new LinkedHashMap<>();
        for (AvailableModel model : getAvailableModels()) {
            grouped.computeIfAbsent(model.provider(), k -> new ArrayList<>()).add(model);
        }
        return grouped;
    }

    /**
     * Validate that a model spec (provider/model) exists and its provider is
     * allowed.
     */
    public ValidationResult validateModel(String modelSpec) {
        if (modelSpec == null || modelSpec.isBlank()) {
            return new ValidationResult(false, "model.empty");
        }

        ModelConfigService.ModelSettings settings = modelConfigService.getModelSettings(modelSpec);
        // Check that we got an actual model match, not just defaults
        Map<String, ModelConfigService.ModelSettings> allModels = modelConfigService.getAllModels();
        String name = modelSpec.contains("/") ? modelSpec.substring(modelSpec.indexOf('/') + 1) : modelSpec;
        boolean exactMatch = allModels.containsKey(modelSpec) || allModels.containsKey(name);
        if (!exactMatch) {
            // Check prefix match
            boolean prefixMatch = allModels.keySet().stream().anyMatch(name::startsWith);
            if (!prefixMatch) {
                return new ValidationResult(false, "model.not.found");
            }
        }

        List<String> allowedProviders = properties.getModelSelection().getAllowedProviders();
        if (!allowedProviders.contains(settings.getProvider())) {
            return new ValidationResult(false, "provider.not.allowed");
        }

        return new ValidationResult(true, null);
    }

    /**
     * Validate that a reasoning level is available for a given model.
     */
    public ValidationResult validateReasoning(String modelSpec, String level) {
        if (!modelConfigService.isReasoningRequired(modelSpec)) {
            return new ValidationResult(false, "no.reasoning");
        }
        List<String> available = modelConfigService.getAvailableReasoningLevels(modelSpec);
        if (!available.contains(level)) {
            return new ValidationResult(false, "level.not.available");
        }
        return new ValidationResult(true, null);
    }

    private ModelSelection resolveFromRouter(String tier) {
        BotProperties.ModelRouterProperties router = properties.getRouter();
        return switch (tier) {
        case "deep" -> new ModelSelection(router.getDeepModel(), router.getDeepModelReasoning());
        case "coding" -> new ModelSelection(router.getCodingModel(), router.getCodingModelReasoning());
        case "smart" -> new ModelSelection(router.getSmartModel(), router.getSmartModelReasoning());
        default -> new ModelSelection(router.getBalancedModel(), router.getBalancedModelReasoning());
        };
    }

    /** Resolved model + reasoning for a tier. */
    public record ModelSelection(String model, String reasoning) {
    }

    /** Available model for display in /model list. */
    public record AvailableModel(String id, String provider, String displayName,
            boolean hasReasoning, List<String> reasoningLevels) {
    }

    /** Validation result for model/reasoning checks. */
    public record ValidationResult(boolean valid, String error) {
    }
}
