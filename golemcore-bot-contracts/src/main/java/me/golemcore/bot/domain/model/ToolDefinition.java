package me.golemcore.bot.domain.model;

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

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Defines a function/tool that the LLM can call during execution. Contains the
 * tool name, description, and JSON Schema for input parameters.
 */
@Data
@Builder
public class ToolDefinition {

    private String name;
    private String description;
    private Map<String, Object> inputSchema; // JSON Schema

    /**
     * Creates a simple tool definition without input parameters.
     */
    public static ToolDefinition simple(String name, String description) {
        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .build();
    }
}
