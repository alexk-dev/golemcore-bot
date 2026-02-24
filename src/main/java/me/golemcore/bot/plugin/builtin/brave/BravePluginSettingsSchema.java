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

import me.golemcore.bot.plugin.api.settings.PluginSettingsSchemas;
import me.golemcore.bot.plugin.api.settings.PluginSettingsSectionSchema;

import java.util.List;

/**
 * Declarative settings schema for Brave Search plugin UI.
 */
public final class BravePluginSettingsSchema {

    private BravePluginSettingsSchema() {
    }

    public static PluginSettingsSectionSchema create() {
        return new PluginSettingsSectionSchema(
                "brave-search-plugin",
                "tool-brave",
                "Brave Search Plugin",
                "Brave search API integration settings.",
                List.of(
                        PluginSettingsSchemas.toggle(
                                "tools.braveSearchEnabled",
                                "Enabled",
                                "Enable Brave Search tool."),
                        PluginSettingsSchemas.password(
                                "tools.braveSearchApiKey",
                                "API Key",
                                "Brave Search API key.",
                                "Enter Brave API key")));
    }
}
