package me.golemcore.bot.domain.runtimeconfig;

import me.golemcore.bot.domain.model.RuntimeConfig;

/**
 * Shared source for typed runtime configuration views.
 */
public interface RuntimeConfigSource {

    RuntimeConfig getRuntimeConfig();
}
