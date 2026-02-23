package me.golemcore.bot.plugin.context;

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

import me.golemcore.bot.plugin.api.BotPlugin;
import me.golemcore.bot.plugin.api.PluginContribution;
import me.golemcore.bot.plugin.builtin.BuiltinPluginCatalog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility component for loading and indexing built-in plugin contributions.
 */
@Component
public class BuiltinPluginRuntime {

    private final SpringPluginContext pluginContext;
    private final BuiltinPluginCatalog builtinPluginCatalog;

    public BuiltinPluginRuntime(SpringPluginContext pluginContext) {
        this.pluginContext = pluginContext;
        this.builtinPluginCatalog = new BuiltinPluginCatalog();
    }

    public List<BotPlugin> loadBuiltinPlugins() {
        List<BotPlugin> plugins = builtinPluginCatalog.createPlugins();
        for (BotPlugin plugin : plugins) {
            plugin.start(pluginContext);
        }
        return plugins;
    }

    public Map<Class<?>, List<PluginContribution<?>>> indexContributionsByContract(List<BotPlugin> plugins) {
        Map<Class<?>, List<PluginContribution<?>>> index = new HashMap<>();
        for (BotPlugin plugin : plugins) {
            for (PluginContribution<?> contribution : plugin.contributions()) {
                index.computeIfAbsent(contribution.contract(), c -> new ArrayList<>()).add(contribution);
            }
        }
        return index;
    }
}
