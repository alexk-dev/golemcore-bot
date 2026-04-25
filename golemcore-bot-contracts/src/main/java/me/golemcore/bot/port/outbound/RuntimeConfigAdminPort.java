package me.golemcore.bot.port.outbound;

import me.golemcore.bot.domain.model.RuntimeConfig;

/**
 * Generic runtime configuration read/write access for capability modules.
 */
public interface RuntimeConfigAdminPort {

    RuntimeConfig getRuntimeConfig();

    void updateRuntimeConfig(RuntimeConfig runtimeConfig);
}
