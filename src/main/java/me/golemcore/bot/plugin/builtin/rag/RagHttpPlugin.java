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

import me.golemcore.bot.plugin.builtin.rag.adapter.LightRagAdapter;
import me.golemcore.bot.plugin.api.PluginContext;
import me.golemcore.bot.plugin.api.settings.PluginSettingsSectionSchema;
import me.golemcore.bot.plugin.builtin.AbstractPlugin;
import me.golemcore.bot.port.outbound.RagPort;

/**
 * Built-in plugin for HTTP-based RAG integration.
 */
public final class RagHttpPlugin extends AbstractPlugin {

    public RagHttpPlugin() {
        super(
                "rag-http-plugin",
                "RAG HTTP",
                "LightRAG HTTP adapter for indexing and query retrieval.",
                "port:rag",
                "rag:lightrag");
    }

    @Override
    public void start(PluginContext context) {
        resetContributions();
        LightRagAdapter lightRagAdapter = context.requireService(LightRagAdapter.class);
        addContribution("port.rag", RagPort.class, lightRagAdapter);
        addContribution("settings.schema.rag", PluginSettingsSectionSchema.class, RagPluginSettingsSchema.create());
    }
}
