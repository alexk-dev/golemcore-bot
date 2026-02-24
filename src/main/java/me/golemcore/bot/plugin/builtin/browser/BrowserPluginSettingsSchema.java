package me.golemcore.bot.plugin.builtin.browser;

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
 * Declarative settings schema for headless browser plugin UI.
 */
public final class BrowserPluginSettingsSchema {

    private BrowserPluginSettingsSchema() {
    }

    public static PluginSettingsSectionSchema create() {
        return new PluginSettingsSectionSchema(
                "headless-browser-plugin",
                "tool-browser",
                "Headless Browser Plugin",
                "Headless browser runtime and execution profile.",
                List.of(
                        PluginSettingsSchemas.toggle(
                                "tools.browserEnabled",
                                "Enabled",
                                "Enable headless browser tooling."),
                        PluginSettingsSchemas.select(
                                "tools.browserType",
                                "Browser Type",
                                "Browser provider implementation.",
                                List.of(
                                        PluginSettingsSchemas.option("playwright", "Playwright"),
                                        PluginSettingsSchemas.option("chrome", "Chrome"))),
                        PluginSettingsSchemas.text(
                                "tools.browserApiProvider",
                                "API Provider",
                                "Provider used by browser-backed operations.",
                                "openai"),
                        PluginSettingsSchemas.toggle(
                                "tools.browserHeadless",
                                "Headless Mode",
                                "Run browser without visible UI."),
                        PluginSettingsSchemas.number(
                                "tools.browserTimeout",
                                "Timeout (ms)",
                                "Request timeout for browser actions.",
                                1000.0,
                                120000.0,
                                500.0,
                                null),
                        PluginSettingsSchemas.text(
                                "tools.browserUserAgent",
                                "User Agent",
                                "Custom user agent header for browser requests.",
                                "Mozilla/5.0 ...")));
    }
}
