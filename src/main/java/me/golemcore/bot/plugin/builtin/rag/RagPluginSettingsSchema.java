package me.golemcore.bot.plugin.builtin.rag;

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
 * Declarative settings schema for RAG plugin UI.
 */
public final class RagPluginSettingsSchema {

    private RagPluginSettingsSchema() {
    }

    public static PluginSettingsSectionSchema create() {
        return new PluginSettingsSectionSchema(
                "rag-http-plugin",
                "rag",
                "RAG Plugin",
                "RAG endpoint and retrieval behavior.",
                List.of(
                        PluginSettingsSchemas.toggle(
                                "rag.enabled",
                                "Enabled",
                                "Enable RAG integration."),
                        PluginSettingsSchemas.url(
                                "rag.url",
                                "RAG URL",
                                "Base URL of RAG service.",
                                "http://localhost:9621"),
                        PluginSettingsSchemas.password(
                                "rag.apiKey",
                                "API Key",
                                "RAG service API key.",
                                "Enter API key"),
                        PluginSettingsSchemas.select(
                                "rag.queryMode",
                                "Query Mode",
                                "Default retrieval mode.",
                                List.of(
                                        PluginSettingsSchemas.option("hybrid", "Hybrid"),
                                        PluginSettingsSchemas.option("local", "Local"),
                                        PluginSettingsSchemas.option("global", "Global"),
                                        PluginSettingsSchemas.option("naive", "Naive"))),
                        PluginSettingsSchemas.number(
                                "rag.timeoutSeconds",
                                "Timeout (seconds)",
                                "Request timeout for RAG API.",
                                1.0,
                                120.0,
                                1.0,
                                null),
                        PluginSettingsSchemas.number(
                                "rag.indexMinLength",
                                "Index Min Length",
                                "Minimum answer length required for indexing.",
                                1.0,
                                20000.0,
                                1.0,
                                null)));
    }
}
