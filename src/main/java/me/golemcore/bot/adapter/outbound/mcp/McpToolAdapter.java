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

package me.golemcore.bot.adapter.outbound.mcp;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Wraps a single MCP tool as a ToolComponent.
 *
 * <p>
 * This adapter bridges MCP tools to the agent's tool execution system. Created
 * dynamically when an MCP server starts — NOT a Spring bean.
 *
 * <p>
 * Lifecycle:
 * <ol>
 * <li>{@link McpClientManager} starts MCP server and gets tool definitions
 * <li>For each tool, creates McpToolAdapter instance
 * <li>{@link me.golemcore.bot.domain.system.ContextBuildingSystem} registers
 * adapters in ToolExecutionSystem
 * <li>LLM calls tool → adapter delegates to {@link McpClient#callTool}
 * <li>When skill changes, ToolExecutionSystem unregisters adapters
 * </ol>
 *
 * @see McpClient
 * @see McpClientManager
 * @see me.golemcore.bot.domain.system.ToolExecutionSystem
 */
public class McpToolAdapter implements ToolComponent {

    private final String skillName;
    private final ToolDefinition definition;
    private final McpClientManager clientManager;

    public McpToolAdapter(String skillName, ToolDefinition definition, McpClientManager clientManager) {
        this.skillName = skillName;
        this.definition = definition;
        this.clientManager = clientManager;
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return clientManager.getClient(skillName)
                .map(client -> client.callTool(definition.getName(), parameters))
                .orElse(CompletableFuture.completedFuture(
                        ToolResult.failure("MCP server not running for skill: " + skillName)));
    }

    @Override
    public boolean isEnabled() {
        return clientManager.getClient(skillName)
                .map(McpClient::isRunning)
                .orElse(false);
    }

    public String getSkillName() {
        return skillName;
    }
}
