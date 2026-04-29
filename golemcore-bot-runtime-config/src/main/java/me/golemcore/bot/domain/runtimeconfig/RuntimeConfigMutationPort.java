package me.golemcore.bot.domain.runtimeconfig;

import me.golemcore.bot.domain.model.RuntimeConfig;

public interface RuntimeConfigMutationPort {
    void updateRuntimeConfig(RuntimeConfig newConfig);
}
