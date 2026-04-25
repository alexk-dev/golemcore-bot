package me.golemcore.bot.port.outbound;

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
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.ToolDefinition;

import java.util.List;

/**
 * Port for managing MCP (Model Context Protocol) server clients. Abstracts the
 * lifecycle of MCP server processes from the domain layer.
 */
public interface McpPort {

    /**
     * Get or start an MCP client for the given skill. Returns the list of tool
     * definitions exposed by the MCP server.
     */
    List<ToolDefinition> getOrStartClient(Skill skill);

    /**
     * Create a tool adapter wrapping an MCP tool as a ToolComponent.
     */
    ToolComponent createToolAdapter(String skillName, ToolDefinition definition);

    /**
     * Stop a client and return its tool names for cleanup.
     */
    List<String> stopClient(String skillName);

    /**
     * Get the tool names belonging to a skill's MCP server.
     */
    List<String> getToolNames(String skillName);
}
