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

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for an MCP (Model Context Protocol) server declared in a
 * skill's YAML frontmatter. Specifies the command to start the server,
 * environment variables, and timeout settings.
 */
@Data
@Builder
public class McpConfig {

    private String command;

    @Builder.Default
    private Map<String, String> env = new HashMap<>();

    @Builder.Default
    private int startupTimeoutSeconds = 30;

    @Builder.Default
    private int idleTimeoutMinutes = 5;
}
