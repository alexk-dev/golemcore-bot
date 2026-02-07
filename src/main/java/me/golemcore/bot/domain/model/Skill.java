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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a skill loaded from a SKILL.md file with YAML frontmatter. Skills
 * provide specialized instructions to the agent for specific tasks, support
 * variable resolution, MCP server integration, and pipeline chaining.
 */
@Data
@Builder
public class Skill {

    private String name;
    private String description;
    private String content; // Full instructions for the agent
    private Path location; // Path to SKILL.md

    private Map<String, Object> metadata;
    private SkillRequirements requirements;

    @Builder.Default
    private boolean available = true; // Whether requirements are met

    @Builder.Default
    private List<SkillVariable> variableDefinitions = new ArrayList<>();

    @Builder.Default
    private Map<String, String> resolvedVariables = new HashMap<>();

    private McpConfig mcpConfig;

    // Pipeline support
    private String nextSkill;

    @Builder.Default
    private Map<String, String> conditionalNextSkills = new HashMap<>();

    /**
     * Checks if this skill has pipeline configuration for automatic transitions.
     */
    public boolean hasPipeline() {
        return nextSkill != null || (conditionalNextSkills != null && !conditionalNextSkills.isEmpty());
    }

    /**
     * Checks if this skill declares an MCP server to be started.
     */
    public boolean hasMcp() {
        return mcpConfig != null && mcpConfig.getCommand() != null && !mcpConfig.getCommand().isBlank();
    }

    /**
     * Declares system requirements for a skill. Skills with unmet requirements are
     * marked as unavailable.
     */
    @Data
    @Builder
    public static class SkillRequirements {
        private List<String> envVars; // Required environment variables
        private List<String> binaries; // Required binaries
        private List<String> skills; // Required other skills
    }

    /**
     * Returns a brief summary of the skill for progressive loading (when full
     * content not needed).
     */
    public String toSummary() {
        return String.format("- **%s**: %s%s",
                name,
                description,
                available ? "" : " (unavailable)");
    }
}
