package me.golemcore.bot.domain.system.toolloop.resilience;

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

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FallbackModes;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.RuntimeConfigService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selects the next L2 provider/model fallback from the configured model-router
 * chain for the current tier.
 */
public class ProviderFallbackSelector {

    private static final String WEIGHTED_STATE_KEY = "resilience.l2.weighted.state";
    private static final String ATTEMPT_COUNT_KEY = "resilience.l2.attempts";

    private final RuntimeConfigService runtimeConfigService;

    public ProviderFallbackSelector(RuntimeConfigService runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    public FallbackSelection selectNext(AgentContext context) {
        if (context == null) {
            return null;
        }
        int attemptCount = readAttemptCount(context);
        if (attemptCount >= resolveMaxAttempts()) {
            clearOverride(context);
            return null;
        }

        RuntimeConfig.TierBinding binding = runtimeConfigService.getModelTierBinding(resolveTier(context));
        if (binding == null || binding.getFallbacks() == null || binding.getFallbacks().isEmpty()) {
            clearOverride(context);
            return null;
        }

        String currentModel = context.getAttribute(ContextAttributes.LLM_MODEL);
        List<RuntimeConfig.TierFallback> candidates = resolveCandidates(binding.getFallbacks(), currentModel);
        if (candidates.isEmpty()) {
            clearOverride(context);
            return null;
        }

        String fallbackMode = FallbackModes.normalize(binding.getFallbackMode());
        RuntimeConfig.TierFallback selected = switch (fallbackMode) {
        case FallbackModes.ROUND_ROBIN -> selectRoundRobin(binding.getFallbacks(), currentModel);
        case FallbackModes.WEIGHTED -> selectWeighted(context, candidates);
        default -> selectSequential(binding.getFallbacks(), currentModel);
        };
        String selectedModel = selected != null ? selected.getModel() : null;
        if (selectedModel == null) {
            clearOverride(context);
            return null;
        }

        context.setAttribute(ContextAttributes.RESILIENCE_L2_FALLBACK_MODEL, selectedModel);
        context.setAttribute(ContextAttributes.RESILIENCE_L2_FALLBACK_REASONING, selected.getReasoning());
        context.setAttribute(ATTEMPT_COUNT_KEY, attemptCount + 1);
        return new FallbackSelection(selectedModel, selected.getReasoning(), fallbackMode);
    }

    public void clearOverride(AgentContext context) {
        if (context == null) {
            return;
        }
        context.setAttribute(ContextAttributes.RESILIENCE_L2_FALLBACK_MODEL, null);
        context.setAttribute(ContextAttributes.RESILIENCE_L2_FALLBACK_REASONING, null);
        context.setAttribute(WEIGHTED_STATE_KEY, null);
    }

    private int resolveMaxAttempts() {
        RuntimeConfig.ResilienceConfig config = runtimeConfigService.getResilienceConfig();
        Integer configured = config.getL2ProviderFallbackMaxAttempts();
        return configured != null && configured > 0 ? configured : 5;
    }

    private int readAttemptCount(AgentContext context) {
        Object value = context.getAttribute(ATTEMPT_COUNT_KEY);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String resolveTier(AgentContext context) {
        String rawTier = context.getModelTier();
        if (rawTier == null || rawTier.isBlank() || "default".equalsIgnoreCase(rawTier)) {
            return "balanced";
        }
        return ModelTierCatalog.normalizeTierId(rawTier);
    }

    private List<RuntimeConfig.TierFallback> resolveCandidates(List<RuntimeConfig.TierFallback> configuredFallbacks,
            String currentModel) {
        List<RuntimeConfig.TierFallback> candidates = new ArrayList<>();
        for (RuntimeConfig.TierFallback fallback : configuredFallbacks) {
            String model = fallback != null ? fallback.getModel() : null;
            if (model == null || model.isBlank()) {
                continue;
            }
            if (currentModel != null && currentModel.equals(model)) {
                continue;
            }
            candidates.add(fallback);
        }
        return candidates;
    }

    private RuntimeConfig.TierFallback selectSequential(List<RuntimeConfig.TierFallback> configuredFallbacks,
            String currentModel) {
        List<RuntimeConfig.TierFallback> normalizedFallbacks = resolveCandidates(configuredFallbacks, null);
        if (normalizedFallbacks.isEmpty()) {
            return null;
        }
        if (currentModel == null || currentModel.isBlank()) {
            return normalizedFallbacks.get(0);
        }
        for (int index = 0; index < normalizedFallbacks.size(); index++) {
            RuntimeConfig.TierFallback fallback = normalizedFallbacks.get(index);
            if (currentModel.equals(fallback.getModel())) {
                return index + 1 < normalizedFallbacks.size() ? normalizedFallbacks.get(index + 1) : null;
            }
        }
        return normalizedFallbacks.get(0);
    }

    private RuntimeConfig.TierFallback selectRoundRobin(List<RuntimeConfig.TierFallback> configuredFallbacks,
            String currentModel) {
        List<RuntimeConfig.TierFallback> normalizedFallbacks = resolveCandidates(configuredFallbacks, null);
        if (normalizedFallbacks.isEmpty()) {
            return null;
        }
        if (currentModel == null || currentModel.isBlank()) {
            return normalizedFallbacks.get(0);
        }
        for (int index = 0; index < normalizedFallbacks.size(); index++) {
            RuntimeConfig.TierFallback fallback = normalizedFallbacks.get(index);
            if (currentModel.equals(fallback.getModel())) {
                return normalizedFallbacks.get((index + 1) % normalizedFallbacks.size());
            }
        }
        return normalizedFallbacks.get(0);
    }

    @SuppressWarnings("unchecked")
    private RuntimeConfig.TierFallback selectWeighted(AgentContext context,
            List<RuntimeConfig.TierFallback> candidates) {
        Map<String, Double> state = context.getAttribute(WEIGHTED_STATE_KEY);
        if (state == null) {
            state = new LinkedHashMap<>();
        }
        double totalWeight = 0.0d;
        RuntimeConfig.TierFallback best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (RuntimeConfig.TierFallback candidate : candidates) {
            double weight = normalizeWeight(candidate.getWeight());
            totalWeight += weight;
            double score = state.getOrDefault(candidate.getModel(), 0.0d) + weight;
            state.put(candidate.getModel(), score);
            if (best == null || score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best == null) {
            return null;
        }
        state.put(best.getModel(), state.get(best.getModel()) - totalWeight);
        context.setAttribute(WEIGHTED_STATE_KEY, state);
        return best;
    }

    private double normalizeWeight(Double weight) {
        if (weight == null || weight <= 0.0d) {
            return 1.0d;
        }
        return weight;
    }

    public record FallbackSelection(String model, String reasoning, String mode) {
    }
}
