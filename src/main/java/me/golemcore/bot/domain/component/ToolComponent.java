package me.golemcore.bot.domain.component;

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

import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Component representing an executable tool that can be invoked by the LLM.
 * Tools expose their JSON Schema definition to the LLM via function calling,
 * and implement the execution logic. Examples include FileSystemTool,
 * ShellTool, BrowserTool, and dynamically registered MCP tools.
 */
public interface ToolComponent extends Component {

    @Override
    default String getComponentType() {
        return "tool";
    }

    /**
     * Returns the tool definition with JSON Schema for function calling. The
     * definition includes the tool name, description, and parameter schema.
     *
     * @return the tool definition
     */
    ToolDefinition getDefinition();

    /**
     * Executes the tool with the specified parameters and returns the result.
     * Parameters are validated against the tool's JSON Schema before execution.
     *
     * @param parameters
     *            the execution parameters as a map
     * @return a future containing the tool execution result
     */
    CompletableFuture<ToolResult> execute(Map<String, Object> parameters);

    /**
     * Returns the unique name of this tool.
     *
     * @return the tool name
     */
    default String getToolName() {
        return getDefinition().getName();
    }
}
