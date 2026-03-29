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

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.McpConfig;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillDocument;
import me.golemcore.bot.domain.model.SkillVariable;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final String DEFAULT_SKILLS_DIR = "skills";
    private static final String SUPPRESS_UNCHECKED = "unchecked";
    private static final String UNKNOWN = "unknown";
    private static final int MIN_PATH_PARTS_FOR_SKILL_NAME = 2;

    private final StoragePort storagePort;
    private final BotProperties properties;
    private final SkillVariableResolver variableResolver;
    private final RuntimeConfigService runtimeConfigService;
    private final SkillDocumentService skillDocumentService;

    private final Map<String, Skill> skillRegistry = new ConcurrentHashMap<>();

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
        if (!runtimeConfigService.isSkillsEnabled()) {
            return List.of();
        }
        return skillRegistry.values().stream()
                .filter(Skill::isAvailable)
                .toList();
    }

    @Override
    public Optional<Skill> findByName(String name) {
        if (!runtimeConfigService.isSkillsEnabled()) {
            return Optional.empty();
        }
        return Optional.ofNullable(skillRegistry.get(name));
    }

    public Optional<Skill> findByLocation(String location) {
        if (!runtimeConfigService.isSkillsEnabled() || location == null || location.isBlank()) {
            return Optional.empty();
        }
        return skillRegistry.values().stream()
                .filter(skill -> skill.getLocation() != null && location.equals(skill.getLocation().toString()))
                .findFirst();
    }

    @Override
    public String getSkillsSummary() {
        if (!runtimeConfigService.isSkillsEnabled() || !runtimeConfigService.isSkillsProgressiveLoadingEnabled()) {
            return "";
        }
        List<Skill> available = getAvailableSkills();
        if (available.isEmpty()) {
            return "";
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Available skills:\n");
        for (Skill skill : available) {
            summary.append(skill.toSummary()).append("\n");
        }
        return summary.toString();
    }

    @Override
    public String getSkillContent(String name) {
        if (!runtimeConfigService.isSkillsEnabled()) {
            return null;
        }
        Skill skill = skillRegistry.get(name);
        return skill != null ? skill.getContent() : null;
    }

    @Override
    public boolean registerDynamicSkill(Skill skill) {
        if (skill == null || skill.getName() == null) {
            return false;
        }
        Skill existing = skillRegistry.putIfAbsent(skill.getName(), skill);
        if (existing == null) {
            log.info("Registered dynamic skill: {}", skill.getName());
            return true;
        }
        log.debug("Dynamic skill '{}' already exists, skipping registration", skill.getName());
        return false;
    }

    @Override
    public void reload() {
        if (!runtimeConfigService.isSkillsEnabled()) {
            skillRegistry.clear();
            log.info("Skills are disabled at runtime, registry cleared");
            return;
        }

        Map<String, Skill> newRegistry = new ConcurrentHashMap<>();
        try {
            List<String> keys = storagePort.listObjects(getSkillsDirectory(), "").join();
            for (String key : keys) {
                if (key.endsWith("/SKILL.md") || "SKILL.md".equals(key)) {
                    loadSkillInto(key, newRegistry);
                }
            }
            skillRegistry.clear();
            skillRegistry.putAll(newRegistry);
            log.info("Loaded {} skills", skillRegistry.size());
        } catch (Exception ex) {
            log.warn("Failed to load skills from storage", ex);
        }
    }

    private void loadSkillInto(String key, Map<String, Skill> target) {
        try {
            String content = storagePort.getText(getSkillsDirectory(), key).join();
            if (content == null || content.isBlank()) {
                return;
            }

            Skill skill = parseSkill(content, key);
            target.put(skill.getName(), skill);
            log.debug("Loaded skill: {}", skill.getName());
        } catch (Exception ex) {
            log.warn("Failed to load skill: {}", key, ex);
        }
    }

    private Skill parseSkill(String content, String path) {
        SkillDocument document = skillDocumentService.parseNormalizedDocument(content);

        String name = extractNameFromPath(path);
        String description = "";
        String body = document.body();
        Map<String, Object> metadata = new LinkedHashMap<>(document.metadata());

        Object metadataName = metadata.get("name");
        if (metadataName instanceof String metadataNameString && !metadataNameString.isBlank()) {
            name = metadataNameString;
        }

        Object metadataDescription = metadata.get("description");
        if (metadataDescription instanceof String metadataDescriptionString) {
            description = metadataDescriptionString;
        }

        boolean available = checkRequirements(metadata);

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

        McpConfig mcpConfig = parseMcpConfig(metadata, resolvedVariables);
        String nextSkill = (String) metadata.get("next_skill");
        Map<String, String> conditionalNextSkills = parseConditionalNextSkills(metadata);
        String modelTier = (String) metadata.get("model_tier");
        String reflectionTier = (String) metadata.get("reflection_tier");
        Skill.SkillRequirements requirements = parseRequirements(metadata);

        return Skill.builder()
                .name(name)
                .description(description)
                .content(body)
                .location(java.nio.file.Path.of(path))
                .metadata(metadata)
                .requirements(requirements)
                .available(available)
                .variableDefinitions(new ArrayList<>(variableDefinitions))
                .resolvedVariables(new HashMap<>(resolvedVariables))
                .mcpConfig(mcpConfig)
                .modelTier(modelTier)
                .reflectionTier(reflectionTier)
                .nextSkill(nextSkill)
                .conditionalNextSkills(conditionalNextSkills)
                .build();
    }

    private String extractNameFromPath(String path) {
        String[] parts = path.split("/");
        if (parts.length >= MIN_PATH_PARTS_FOR_SKILL_NAME) {
            return parts[parts.length - 2];
        }
        return UNKNOWN;
    }

    @SuppressWarnings(SUPPRESS_UNCHECKED)
    private Map<String, String> parseConditionalNextSkills(Map<String, Object> metadata) {
        Object conditionalNextSkillsObject = metadata.get("conditional_next_skills");
        if (!(conditionalNextSkillsObject instanceof Map)) {
            return new HashMap<>();
        }
        Map<String, Object> conditionalNextSkillsMap = (Map<String, Object>) conditionalNextSkillsObject;
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : conditionalNextSkillsMap.entrySet()) {
            if (entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue().toString());
            }
        }
        return result;
    }

    @SuppressWarnings(SUPPRESS_UNCHECKED)
    private McpConfig parseMcpConfig(Map<String, Object> metadata, Map<String, String> resolvedVariables) {
        Object mcpObject = metadata.get("mcp");
        if (!(mcpObject instanceof Map)) {
            return null;
        }

        Map<String, Object> mcpMap = (Map<String, Object>) mcpObject;
        String command = (String) mcpMap.get("command");
        if (command == null || command.isBlank()) {
            return null;
        }

        Map<String, String> env = new HashMap<>();
        Object envObject = mcpMap.get("env");
        if (envObject instanceof Map) {
            Map<String, Object> envMap = (Map<String, Object>) envObject;
            for (Map.Entry<String, Object> entry : envMap.entrySet()) {
                String value = entry.getValue() != null ? entry.getValue().toString() : "";
                value = resolveEnvPlaceholders(value, resolvedVariables);
                env.put(entry.getKey(), value);
            }
        }

        int startupTimeout = 30;
        Object startupTimeoutObject = mcpMap.get("startup_timeout");
        if (startupTimeoutObject instanceof Number) {
            startupTimeout = ((Number) startupTimeoutObject).intValue();
        }

        int idleTimeout = 5;
        Object idleTimeoutObject = mcpMap.get("idle_timeout");
        if (idleTimeoutObject instanceof Number) {
            idleTimeout = ((Number) idleTimeoutObject).intValue();
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

        Matcher matcher = Pattern.compile("\\$\\{([^}]+)}").matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String variableName = matcher.group(1);
            String replacement = resolvedVariables.getOrDefault(variableName, System.getenv(variableName));
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(replacement != null ? replacement : ""));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    @SuppressWarnings(SUPPRESS_UNCHECKED)
    private boolean checkRequirements(Map<String, Object> metadata) {
        Object requirementsObject = metadata.get("requires");
        if (requirementsObject == null) {
            return true;
        }

        if (requirementsObject instanceof Map) {
            Map<String, Object> requirements = (Map<String, Object>) requirementsObject;

            Object envVars = requirements.get("env");
            if (envVars instanceof List) {
                for (Object envVar : (List<?>) envVars) {
                    if (System.getenv(envVar.toString()) == null) {
                        log.debug("Skill requirement not met: env var {} missing", envVar);
                        return false;
                    }
                }
            }

            Object binaries = requirements.get("binary");
            if (binaries instanceof List) {
                for (Object binary : (List<?>) binaries) {
                    log.debug("Skill requires binary: {}", binary);
                }
            }
        }

        return true;
    }

    @SuppressWarnings(SUPPRESS_UNCHECKED)
    private Skill.SkillRequirements parseRequirements(Map<String, Object> metadata) {
        Object requirementsObject = metadata.get("requires");
        if (!(requirementsObject instanceof Map<?, ?> requirementsMap)) {
            return null;
        }

        List<String> envVars = toStringList(((Map<String, Object>) requirementsMap).get("env"));
        List<String> binaries = toStringList(((Map<String, Object>) requirementsMap).get("binary"));
        List<String> skills = toStringList(((Map<String, Object>) requirementsMap).get("skills"));

        if (envVars.isEmpty() && binaries.isEmpty() && skills.isEmpty()) {
            return null;
        }

        return Skill.SkillRequirements.builder()
                .envVars(envVars)
                .binaries(binaries)
                .skills(skills)
                .build();
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .toList();
    }

    private String getSkillsDirectory() {
        String configured = properties.getSkills().getDirectory();
        if (configured == null || configured.isBlank()) {
            return DEFAULT_SKILLS_DIR;
        }
        return configured;
    }
}
