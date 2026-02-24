package me.golemcore.bot.plugin.builtin.brave;

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

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.plugin.api.PluginContext;
import me.golemcore.bot.plugin.api.settings.PluginSettingsSectionSchema;
import me.golemcore.bot.plugin.builtin.AbstractPlugin;
import me.golemcore.bot.plugin.builtin.brave.tool.BraveSearchTool;

/**
 * Built-in plugin for Brave Search tool integration.
 */
public final class BraveSearchPlugin extends AbstractPlugin {

    public BraveSearchPlugin() {
        super(
                "brave-search-plugin",
                "Brave Search",
                "Web search integration via Brave Search API.",
                "tool:brave_search",
                "search:web");
    }

    @Override
    public void start(PluginContext context) {
        resetContributions();
        BraveSearchTool braveSearchTool = context.requireService(BraveSearchTool.class);
        addContribution("tool.brave_search", ToolComponent.class, braveSearchTool);
        addContribution("settings.schema.tool-brave", PluginSettingsSectionSchema.class,
                BravePluginSettingsSchema.create());
    }
}
