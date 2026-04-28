package me.golemcore.bot.domain.runtimeconfig;

import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_BALANCED_MODEL;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_BALANCED_REASONING;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_CODING_MODEL;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_CODING_REASONING;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DEEP_MODEL;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_DEEP_REASONING;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_ROUTING_MODEL;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_ROUTING_REASONING;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SMART_MODEL;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_SMART_REASONING;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TIER_TEMPERATURE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigSupport.normalizeNonBlankString;

import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.RuntimeConfig;

public interface ModelRoutingConfigView extends RuntimeConfigSource {

    default String getBalancedModel() {
        String val = getRuntimeConfig().getModelRouter().getBalancedModel();
        return val != null ? val : DEFAULT_BALANCED_MODEL;
    }

    default String getBalancedModelReasoning() {
        String val = getRuntimeConfig().getModelRouter().getBalancedModelReasoning();
        return val != null ? val : DEFAULT_BALANCED_REASONING;
    }

    default String getRoutingModel() {
        String val = getRuntimeConfig().getModelRouter().getRoutingModel();
        return val != null ? val : DEFAULT_ROUTING_MODEL;
    }

    default String getRoutingModelReasoning() {
        String val = getRuntimeConfig().getModelRouter().getRoutingModelReasoning();
        return val != null ? val : DEFAULT_ROUTING_REASONING;
    }

    default String getSmartModel() {
        String val = getRuntimeConfig().getModelRouter().getSmartModel();
        return val != null ? val : DEFAULT_SMART_MODEL;
    }

    default String getSmartModelReasoning() {
        String val = getRuntimeConfig().getModelRouter().getSmartModelReasoning();
        return val != null ? val : DEFAULT_SMART_REASONING;
    }

    default String getCodingModel() {
        String val = getRuntimeConfig().getModelRouter().getCodingModel();
        return val != null ? val : DEFAULT_CODING_MODEL;
    }

    default String getCodingModelReasoning() {
        String val = getRuntimeConfig().getModelRouter().getCodingModelReasoning();
        return val != null ? val : DEFAULT_CODING_REASONING;
    }

    default String getDeepModel() {
        String val = getRuntimeConfig().getModelRouter().getDeepModel();
        return val != null ? val : DEFAULT_DEEP_MODEL;
    }

    default String getDeepModelReasoning() {
        String val = getRuntimeConfig().getModelRouter().getDeepModelReasoning();
        return val != null ? val : DEFAULT_DEEP_REASONING;
    }

    default boolean isDynamicTierEnabled() {
        Boolean val = getRuntimeConfig().getModelRouter().getDynamicTierEnabled();
        return val != null ? val : true;
    }

    default RuntimeConfig.TierBinding getModelTierBinding(String tier) {
        RuntimeConfig.ModelRouterConfig modelRouter = getRuntimeConfig().getModelRouter();
        if (ModelTierCatalog.ROUTING_TIER.equals(tier)) {
            return modelRouter.getRouting();
        }
        return modelRouter.getTierBinding(tier);
    }

    default double getTemperatureForModel(String tier, String model) {
        Double configuredTemperature = findConfiguredTemperature(tier, model);
        return configuredTemperature != null ? configuredTemperature : DEFAULT_TIER_TEMPERATURE;
    }

    private Double findConfiguredTemperature(String tier, String model) {
        RuntimeConfig.TierBinding binding = getModelTierBinding(tier);
        String normalizedModel = normalizeNonBlankString(model, null);
        if (binding == null) {
            return null;
        }
        if (normalizedModel == null || normalizedModel.equals(binding.getModel())) {
            return binding.getTemperature();
        }
        if (binding.getFallbacks() == null) {
            return null;
        }
        for (RuntimeConfig.TierFallback fallback : binding.getFallbacks()) {
            if (fallback != null && normalizedModel.equals(fallback.getModel())) {
                return fallback.getTemperature();
            }
        }
        return null;
    }
}
