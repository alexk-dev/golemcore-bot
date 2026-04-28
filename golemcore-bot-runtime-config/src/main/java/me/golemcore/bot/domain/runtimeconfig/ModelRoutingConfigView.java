package me.golemcore.bot.domain.runtimeconfig;

import me.golemcore.bot.domain.model.RuntimeConfig;

public interface ModelRoutingConfigView {
    String getBalancedModel();

    String getBalancedModelReasoning();

    String getRoutingModel();

    String getRoutingModelReasoning();

    String getSmartModel();

    String getSmartModelReasoning();

    String getCodingModel();

    String getCodingModelReasoning();

    String getDeepModel();

    String getDeepModelReasoning();

    RuntimeConfig.TierBinding getModelTierBinding(String tier);
}
