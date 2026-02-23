package me.golemcore.bot.plugin.builtin;

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
import me.golemcore.bot.plugin.api.PluginDescriptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class AbstractPlugin implements BotPlugin {

    private static final String PLUGIN_API_VERSION = "1.0";
    private static final String CORE_VERSION_RANGE = "[0.5,1.0)";
    private static final String DEFAULT_PLUGIN_VERSION = "1.0.0";

    private final PluginDescriptor pluginDescriptor;
    private final List<PluginContribution<?>> pluginContributions = new ArrayList<>();

    protected AbstractPlugin(String id, String name, String description, String... capabilities) {
        this.pluginDescriptor = new PluginDescriptor(
                id,
                name,
                DEFAULT_PLUGIN_VERSION,
                PLUGIN_API_VERSION,
                CORE_VERSION_RANGE,
                description,
                Arrays.asList(capabilities));
    }

    @Override
    public PluginDescriptor descriptor() {
        return pluginDescriptor;
    }

    @Override
    public List<PluginContribution<?>> contributions() {
        return List.copyOf(pluginContributions);
    }

    @Override
    public void stop() {
        pluginContributions.clear();
    }

    protected <T> void addContribution(String id, Class<T> contract, T instance) {
        pluginContributions.add(PluginContribution.of(id, contract, instance));
    }

    protected void resetContributions() {
        pluginContributions.clear();
    }
}
