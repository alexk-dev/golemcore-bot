package me.golemcore.bot.plugin.api;

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

import java.nio.file.Path;
import java.util.Map;

/**
 * Host-provided context for plugins.
 */
public interface PluginContext {

    /**
     * Resolve required host service/bean by type.
     */
    <T> T requireService(Class<T> type);

    /**
     * Read-only plugin configuration section.
     */
    Map<String, Object> pluginConfig(String pluginId);

    /**
     * Resolve a secret value for plugin usage.
     */
    String secret(String key);

    /**
     * Plugin-specific storage path.
     */
    Path pluginDataDir(String pluginId);
}
