package me.golemcore.bot.domain.service;

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

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillVariable;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.golemcore.bot.domain.model.McpConfig;

/**
 * Service for loading and managing skills from SKILL.md files with YAML
 * frontmatter. Implements {@link SkillComponent} to provide skill lookup,
 * progressive loading (summaries vs full content), variable resolution,
 * requirement checking, and MCP server configuration parsing. Skills are stored
 * in the skills/ directory and can be dynamically created or modified at
 * runtime.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillService implements SkillComponent {

    private final StoragePort storagePort;
    private final BotProperties properties;
    private final SkillVariableResolver variableResolver;

    private static final String SKILLS_DIR = "skills";
    private static final String SUPPRESS_UNCHECKED = "unchecked";
    private static final String UNKNOWN = "unknown";
    private static final int MIN_PATH_PARTS_FOR_SKILL_NAME = 2;
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);

    private final Map<String, Skill> skillRegistry = new ConcurrentHashMap<>();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @PostConstruct
    public void init() {
        reload();
    }

    @Override
    public String getComponentType() {
        return "skill";
    }

    @Override
    public List<Skill> getAllSkills() {
        return new ArrayList<>(skillRegistry.values());
    }

    @Override
    public List<Skill> getAvailableSkills() {
        return skillRegistry.values().stream()
                .filter(Skill::isAvailable)
                .toList();
    }

    @Override
    public Optional<Skill> findByName(String name) {
        return Optional.ofNullable(skillRegistry.get(name));
    }

    @Override
    public String getSkillsSummary() {
        List<Skill> available = getAvailableSkills();
        if (available.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Available skills:\n");
        for (Skill skill : available) {
            sb.append(skill.toSummary()).append("\n");
        }
        return sb.toString();
    }

    @Override
    public String getSkillContent(String name) {
        Skill skill = skillRegistry.get(name);
        return skill != null ? skill.getContent() : null;
    }

    @Override
    public void reload() {
        // Copy-on-write: load into temp map, then swap atomically
        Map<String, Skill> newRegistry = new ConcurrentHashMap<>();

        try {
            List<String> keys = storagePort.listObjects(SKILLS_DIR, "").join();
            for (String key : keys) {
                if (key.endsWith("/SKILL.md") || "SKILL.md".equals(key)) {
                    loadSkillInto(key, newRegistry);
                }
            }

            // Atomic swap: clear and copy
            skillRegistry.clear();
            skillRegistry.putAll(newRegistry);
            log.info("Loaded {} skills", skillRegistry.size());
        } catch (Exception e) {
            log.warn("Failed to load skills from storage", e);
        }
    }

    private void loadSkillInto(String key, Map<String, Skill> target) {
        try {
            String content = storagePort.getText(SKILLS_DIR, key).join();
            if (content == null || content.isBlank()) {
                return;
            }

            Skill skill = parseSkill(content, key);
            target.put(skill.getName(), skill);
            log.debug("Loaded skill: {}", skill.getName());
        } catch (Exception e) {
            log.warn("Failed to load skill: {}", key, e);
        }
    }

    private Skill parseSkill(String content, String path) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);

        String name = extractNameFromPath(path);
        String description = "";
        String body = content;
        Map<String, Object> metadata = new HashMap<>();

        if (matcher.matches()) {
            String frontmatter = matcher.group(1);
            body = matcher.group(2);

            try {
                @SuppressWarnings(SUPPRESS_UNCHECKED)
                Map<String, Object> yaml = yamlMapper.readValue(frontmatter, Map.class);

                name = (String) yaml.getOrDefault("name", name);
                description = (String) yaml.getOrDefault("description", "");
                metadata = yaml;

            } catch (IOException | RuntimeException e) {
                log.warn("Failed to parse skill frontmatter: {}", path, e);
            }
        }

        // Check requirements
        boolean available = checkRequirements(metadata);

        // Parse and resolve variables
        List<SkillVariable> variableDefinitions = List.of();
        Map<String, String> resolvedVariables = Map.of();

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        Map<String, Object> varsSection = (Map<String, Object>) metadata.get("vars");
        if (varsSection != null) {
            variableDefinitions = variableResolver.parseVariableDefinitions(varsSection);
            resolvedVariables = variableResolver.resolveVariables(name, variableDefinitions);

            List<String> missing = variableResolver.findMissingRequired(variableDefinitions, resolvedVariables);
            if (!missing.isEmpty()) {
                log.warn("Skill {} missing required variables: {}", name, missing);
                available = false;
            }

            if (!resolvedVariables.isEmpty()) {
                Map<String, String> masked = variableResolver.maskSecrets(variableDefinitions, resolvedVariables);
                log.debug("Skill {} variables: {}", name, masked);
            }
        }

        // Parse MCP configuration
        McpConfig mcpConfig = parseMcpConfig(metadata, resolvedVariables);

        // Parse pipeline configuration
        String nextSkill = (String) metadata.get("next_skill");
        Map<String, String> conditionalNextSkills = parseConditionalNextSkills(metadata);

        return Skill.builder()
                .name(name)
                .description(description)
                .content(body.trim())
                .metadata(metadata)
                .available(available)
                .variableDefinitions(new ArrayList<>(variableDefinitions))
                .resolvedVariables(new HashMap<>(resolvedVariables))
                .mcpConfig(mcpConfig)
                .nextSkill(nextSkill)
                .conditionalNextSkills(conditionalNextSkills)
                .build();
    }

    private String extractNameFromPath(String path) {
        // Extract skill name from path like "skills/summarize/SKILL.md"
        String[] parts = path.split("/");
        if (parts.length >= MIN_PATH_PARTS_FOR_SKILL_NAME) {
            return parts[parts.length - 2];
        }
        return UNKNOWN;
    }

    @SuppressWarnings(SUPPRESS_UNCHECKED)
    private Map<String, String> parseConditionalNextSkills(Map<String, Object> metadata) {
        Object cnsObj = metadata.get("conditional_next_skills");
        if (!(cnsObj instanceof Map)) {
            return new HashMap<>();
        }
        Map<String, Object> cnsMap = (Map<String, Object>) cnsObj;
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : cnsMap.entrySet()) {
            if (entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue().toString());
            }
        }
        return result;
    }

    @SuppressWarnings(SUPPRESS_UNCHECKED)
    private McpConfig parseMcpConfig(Map<String, Object> metadata, Map<String, String> resolvedVariables) {
        Object mcpObj = metadata.get("mcp");
        if (!(mcpObj instanceof Map)) {
            return null;
        }

        Map<String, Object> mcpMap = (Map<String, Object>) mcpObj;
        String command = (String) mcpMap.get("command");
        if (command == null || command.isBlank()) {
            return null;
        }

        // Parse env and resolve ${VAR} references
        Map<String, String> env = new HashMap<>();
        Object envObj = mcpMap.get("env");
        if (envObj instanceof Map) {
            Map<String, Object> envMap = (Map<String, Object>) envObj;
            for (Map.Entry<String, Object> entry : envMap.entrySet()) {
                String value = entry.getValue() != null ? entry.getValue().toString() : "";
                value = resolveEnvPlaceholders(value, resolvedVariables);
                env.put(entry.getKey(), value);
            }
        }

        int startupTimeout = 30;
        Object startupObj = mcpMap.get("startup_timeout");
        if (startupObj instanceof Number) {
            startupTimeout = ((Number) startupObj).intValue();
        }

        int idleTimeout = 5;
        Object idleObj = mcpMap.get("idle_timeout");
        if (idleObj instanceof Number) {
            idleTimeout = ((Number) idleObj).intValue();
        }

        log.debug("Parsed MCP config: command='{}', env keys={}", command, env.keySet());

        return McpConfig.builder()
                .command(command)
                .env(env)
                .startupTimeoutSeconds(startupTimeout)
                .idleTimeoutMinutes(idleTimeout)
                .build();
    }

    private String resolveEnvPlaceholders(String value, Map<String, String> resolvedVariables) {
        if (value == null || !value.contains("${")) {
            return value;
        }

        Matcher m = Pattern.compile("\\$\\{([^}]+)}").matcher(value);
        StringBuilder result = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            // Try resolved skill variables first, then system env
            String replacement = resolvedVariables.getOrDefault(varName, System.getenv(varName));
            m.appendReplacement(result,
                    Matcher.quoteReplacement(replacement != null ? replacement : ""));
        }
        m.appendTail(result);
        return result.toString();
    }

    @SuppressWarnings(SUPPRESS_UNCHECKED)
    private boolean checkRequirements(Map<String, Object> metadata) {
        Object reqObj = metadata.get("requires");
        if (reqObj == null) {
            return true;
        }

        if (reqObj instanceof Map) {
            Map<String, Object> requires = (Map<String, Object>) reqObj;

            // Check env vars
            Object envVars = requires.get("env");
            if (envVars instanceof List) {
                for (Object envVar : (List<?>) envVars) {
                    if (System.getenv(envVar.toString()) == null) {
                        log.debug("Skill requirement not met: env var {} missing", envVar);
                        return false;
                    }
                }
            }

            // Check binaries (simplified check)
            Object binaries = requires.get("binary");
            if (binaries instanceof List) {
                for (Object binary : (List<?>) binaries) {
                    // Could add actual binary check here
                    log.debug("Skill requires binary: {}", binary);
                }
            }
        }

        return true;
    }
}
