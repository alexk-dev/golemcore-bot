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

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.plugin.api.BotPlugin;
import me.golemcore.bot.plugin.api.PluginContribution;
import me.golemcore.bot.plugin.api.PluginDescriptor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime plugin registry with strict contribution lookup.
 */
@Component
@Slf4j
public class PluginRegistryService {

    private final BuiltinPluginRuntime builtinPluginRuntime;

    private boolean initialized;
    private Map<String, BotPlugin> pluginsById = Map.of();
    private Map<Class<?>, Map<String, Object>> contributionsByContract = Map.of();

    public PluginRegistryService(BuiltinPluginRuntime builtinPluginRuntime) {
        this.builtinPluginRuntime = builtinPluginRuntime;
    }

    public synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        initializeInternal();
        initialized = true;
    }

    public List<PluginDescriptor> getActivePluginDescriptors() {
        ensureInitialized();
        return pluginsById.values().stream()
                .map(BotPlugin::descriptor)
                .toList();
    }

    public <T> T requireContribution(String id, Class<T> contract) {
        ensureInitialized();
        Map<String, Object> byId = contributionsByContract.get(contract);
        if (byId == null) {
            throw new IllegalStateException("No contributions registered for contract: " + contract.getName());
        }
        Object instance = byId.get(id);
        if (instance == null) {
            throw new IllegalStateException(
                    "Missing required plugin contribution: id=" + id + ", contract=" + contract.getName());
        }
        return contract.cast(instance);
    }

    public <T> List<T> listContributions(Class<T> contract) {
        ensureInitialized();
        Map<String, Object> byId = contributionsByContract.get(contract);
        if (byId == null || byId.isEmpty()) {
            return List.of();
        }
        List<T> result = new ArrayList<>();
        for (Object instance : byId.values()) {
            result.add(contract.cast(instance));
        }
        return List.copyOf(result);
    }

    public <T> Map<String, T> listContributionsById(Class<T> contract) {
        ensureInitialized();
        Map<String, Object> byId = contributionsByContract.get(contract);
        if (byId == null || byId.isEmpty()) {
            return Map.of();
        }
        Map<String, T> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : byId.entrySet()) {
            result.put(entry.getKey(), contract.cast(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    private void initializeInternal() {
        List<BotPlugin> loadedPlugins = builtinPluginRuntime.loadBuiltinPlugins();
        Map<String, BotPlugin> pluginMap = new LinkedHashMap<>();
        Map<Class<?>, Map<String, Object>> contributionMap = new LinkedHashMap<>();

        for (BotPlugin plugin : loadedPlugins) {
            String pluginId = plugin.descriptor().id();
            if (pluginMap.containsKey(pluginId)) {
                throw new IllegalStateException("Duplicate plugin id: " + pluginId);
            }
            pluginMap.put(pluginId, plugin);

            for (PluginContribution<?> contribution : plugin.contributions()) {
                registerContribution(pluginId, contribution, contributionMap);
            }
        }

        this.pluginsById = Collections.unmodifiableMap(pluginMap);
        this.contributionsByContract = freezeContributionMap(contributionMap);

        log.info("[Plugins] Loaded {} plugins, {} contracts",
                pluginsById.size(), contributionsByContract.size());
        log.info("[Plugins] Active plugins: {}", pluginsById.keySet());
    }

    private void registerContribution(String pluginId, PluginContribution<?> contribution,
            Map<Class<?>, Map<String, Object>> contributionMap) {
        Map<String, Object> byId = contributionMap.computeIfAbsent(
                contribution.contract(), key -> new LinkedHashMap<>());

        if (byId.containsKey(contribution.id())) {
            throw new IllegalStateException("Duplicate contribution id: " + contribution.id()
                    + " for contract " + contribution.contract().getName());
        }
        byId.put(contribution.id(), contribution.instance());

        log.debug("[Plugins] Registered contribution {} ({}) from {}",
                contribution.id(), contribution.contract().getSimpleName(), pluginId);
    }

    private Map<Class<?>, Map<String, Object>> freezeContributionMap(
            Map<Class<?>, Map<String, Object>> source) {
        Map<Class<?>, Map<String, Object>> frozen = new LinkedHashMap<>();
        for (Map.Entry<Class<?>, Map<String, Object>> entry : source.entrySet()) {
            frozen.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(frozen);
    }
}
