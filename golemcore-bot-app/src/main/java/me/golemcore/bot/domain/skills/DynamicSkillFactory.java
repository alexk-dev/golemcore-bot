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

package me.golemcore.bot.domain.skills;

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.McpConfig;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Skill;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Materializes MCP catalog entries into {@link Skill} objects that can be
 * registered in the skill registry and activated via the normal skill pipeline.
 *
 * <p>
 * Generated skills use a {@code mcp-} prefix to avoid name collisions with
 * manually-authored skills. Each skill has a minimal content body describing
 * the MCP server and its purpose.
 */
@Service
@Slf4j
public class DynamicSkillFactory {

    private static final String MCP_SKILL_PREFIX = "mcp-";
    private static final Pattern ENV_PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /**
     * Materializes a single catalog entry into a Skill object.
     */
    public Skill materialize(RuntimeConfig.McpCatalogEntry entry) {
        McpConfig mcpConfig = McpConfig.builder()
                .command(entry.getCommand())
                .env(resolveEnvPlaceholders(entry.getEnv()))
                .startupTimeoutSeconds(entry.getStartupTimeoutSeconds() != null ? entry.getStartupTimeoutSeconds() : 30)
                .idleTimeoutMinutes(entry.getIdleTimeoutMinutes() != null ? entry.getIdleTimeoutMinutes() : 5)
                .build();

        String skillName = toSkillName(entry.getName());
        String description = entry.getDescription() != null
                ? entry.getDescription()
                : "MCP server: " + entry.getName();

        String content = buildContent(entry);

        return Skill.builder()
                .name(skillName)
                .description(description)
                .content(content)
                .available(true)
                .mcpConfig(mcpConfig)
                .build();
    }

    /**
     * Materializes all enabled catalog entries into Skills.
     */
    public List<Skill> materializeAll(List<RuntimeConfig.McpCatalogEntry> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return List.of();
        }
        return catalog.stream()
                .filter(entry -> entry.getEnabled() == null || entry.getEnabled())
                .filter(entry -> entry.getCommand() != null && !entry.getCommand().isBlank())
                .map(this::materialize)
                .toList();
    }

    /**
     * Converts a catalog entry name to a skill name with the {@code mcp-} prefix.
     */
    public String toSkillName(String catalogEntryName) {
        return MCP_SKILL_PREFIX + catalogEntryName;
    }

    /**
     * Checks if a skill name was generated from the MCP catalog.
     */
    public boolean isCatalogSkill(String skillName) {
        return skillName != null && skillName.startsWith(MCP_SKILL_PREFIX);
    }

    private String buildContent(RuntimeConfig.McpCatalogEntry entry) {
        StringBuilder content = new StringBuilder();
        content.append("Use the available MCP tools provided by this server to complete the task.\n\n");
        if (entry.getDescription() != null) {
            content.append(entry.getDescription()).append("\n\n");
        }
        content.append("Server: ").append(entry.getName()).append("\n");
        content.append("Command: `").append(entry.getCommand()).append("`\n");
        return content.toString();
    }

    private Map<String, String> resolveEnvPlaceholders(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, String> resolved = new HashMap<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            resolved.put(entry.getKey(), resolvePlaceholder(entry.getValue()));
        }
        return resolved;
    }

    private String resolvePlaceholder(String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        Matcher matcher = ENV_PLACEHOLDER_PATTERN.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String envValue = System.getenv(varName);
            matcher.appendReplacement(result, Matcher.quoteReplacement(envValue != null ? envValue : matcher.group(0)));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
