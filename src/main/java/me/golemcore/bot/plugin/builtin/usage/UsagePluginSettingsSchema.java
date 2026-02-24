package me.golemcore.bot.plugin.builtin.usage;

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
 * Declarative settings schema for Usage Tracker plugin UI.
 */
public final class UsagePluginSettingsSchema {

    private UsagePluginSettingsSchema() {
    }

    public static PluginSettingsSectionSchema create() {
        return new PluginSettingsSectionSchema(
                "usage-tracker-plugin",
                "usage",
                "Usage Tracker Plugin",
                "Usage analytics tracking controls.",
                List.of(
                        PluginSettingsSchemas.toggle(
                                "usage.enabled",
                                "Enabled",
                                "Enable usage tracking plugin.")));
    }
}
