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

import jakarta.annotation.PostConstruct;
import me.golemcore.bot.domain.component.ToolComponent;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified tool catalog where plugin-managed tools are strict and have no
 * fallback.
 */
@Component
public class PluginToolCatalog {

    private static final Map<String, String> MANAGED_TOOLS = Map.of(
            "brave_search", "tool.brave_search",
            "browse", "tool.browse",
            "send_voice", "tool.send_voice",
            "imap", "tool.imap",
            "smtp", "tool.smtp");

    private final PluginRegistryService pluginRegistryService;
    private final List<ToolComponent> springTools;
    private final Map<String, ToolComponent> toolRegistry = new ConcurrentHashMap<>();

    public PluginToolCatalog(PluginRegistryService pluginRegistryService, List<ToolComponent> springTools) {
        this.pluginRegistryService = pluginRegistryService;
        this.springTools = springTools != null ? springTools : List.of();
    }

    public static PluginToolCatalog forTesting(List<ToolComponent> toolComponents) {
        PluginToolCatalog catalog = new PluginToolCatalog(null, List.of());
        catalog.seedRegistryFromTools(toolComponents);
        return catalog;
    }

    @PostConstruct
    void init() {
        if (pluginRegistryService == null) {
            return;
        }
        pluginRegistryService.ensureInitialized();
        rebuildRegistry();
    }

    public List<ToolComponent> getAllTools() {
        return List.copyOf(toolRegistry.values());
    }

    public ToolComponent getTool(String toolName) {
        return toolRegistry.get(toolName);
    }

    public void registerTool(ToolComponent toolComponent) {
        toolRegistry.put(toolComponent.getToolName(), toolComponent);
    }

    public void unregisterTools(Collection<String> toolNames) {
        if (toolNames == null) {
            return;
        }
        for (String toolName : toolNames) {
            toolRegistry.remove(toolName);
        }
    }

    private void seedRegistryFromTools(List<ToolComponent> toolComponents) {
        if (toolComponents == null) {
            return;
        }
        int fallbackIndex = 0;
        for (ToolComponent toolComponent : toolComponents) {
            if (toolComponent == null) {
                continue;
            }
            String toolName = toolComponent.getToolName();
            if (toolName == null || toolName.isBlank()) {
                if (toolComponent.getDefinition() != null
                        && toolComponent.getDefinition().getName() != null
                        && !toolComponent.getDefinition().getName().isBlank()) {
                    toolName = toolComponent.getDefinition().getName();
                } else {
                    toolName = "compat-tool-" + fallbackIndex;
                    fallbackIndex++;
                }
            }
            toolRegistry.put(toolName, toolComponent);
        }
    }

    private void rebuildRegistry() {
        Map<String, ToolComponent> rebuilt = new LinkedHashMap<>();

        for (ToolComponent tool : springTools) {
            if (!MANAGED_TOOLS.containsKey(tool.getToolName())) {
                rebuilt.put(tool.getToolName(), tool);
            }
        }

        for (Map.Entry<String, String> entry : MANAGED_TOOLS.entrySet()) {
            String expectedToolName = entry.getKey();
            String contributionId = entry.getValue();
            ToolComponent pluginTool = pluginRegistryService.requireContribution(contributionId, ToolComponent.class);
            if (!expectedToolName.equals(pluginTool.getToolName())) {
                throw new IllegalStateException("Plugin contribution " + contributionId
                        + " exposed unexpected tool name: " + pluginTool.getToolName()
                        + ", expected: " + expectedToolName);
            }
            rebuilt.put(expectedToolName, pluginTool);
        }

        toolRegistry.clear();
        toolRegistry.putAll(rebuilt);
    }
}
