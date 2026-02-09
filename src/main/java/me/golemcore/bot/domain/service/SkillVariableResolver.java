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

import me.golemcore.bot.domain.model.SkillVariable;
import me.golemcore.bot.port.outbound.StoragePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * Resolves skill variable values from multiple configuration sources with
 * priority ordering. Variables can be defined in skill YAML frontmatter and
 * resolved from:
 * <ol>
 * <li>Per-skill vars.json (skills/{name}/vars.json)</li>
 * <li>Global variables.json skill-specific section</li>
 * <li>Global variables.json _global section</li>
 * <li>Environment variables</li>
 * <li>Defaults from frontmatter</li>
 * </ol>
 * Supports required variables, secret masking, and validation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillVariableResolver {

    private static final String SKILLS_DIR = "skills";
    private static final String GLOBAL_SECTION = "_global";
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE_REF = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Map<String, String>>> NESTED_MAP_TYPE_REF = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse variable definitions from the 'vars' section of YAML frontmatter.
     * Supports both map format and shorthand string format.
     */
    @SuppressWarnings("unchecked")
    public List<SkillVariable> parseVariableDefinitions(Map<String, Object> varsMap) {
        if (varsMap == null || varsMap.isEmpty()) {
            return Collections.emptyList();
        }

        List<SkillVariable> definitions = new ArrayList<>();

        for (Map.Entry<String, Object> entry : varsMap.entrySet()) {
            String varName = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                Map<String, Object> varDef = (Map<String, Object>) value;
                definitions.add(SkillVariable.builder()
                        .name(varName)
                        .description((String) varDef.getOrDefault("description", ""))
                        .defaultValue(varDef.get("default") != null ? varDef.get("default").toString() : null)
                        .required(Boolean.TRUE.equals(varDef.get("required")))
                        .secret(Boolean.TRUE.equals(varDef.get("secret")))
                        .build());
            } else if (value instanceof String) {
                // Shorthand: VAR_NAME: "default value"
                definitions.add(SkillVariable.builder()
                        .name(varName)
                        .defaultValue((String) value)
                        .build());
            } else {
                definitions.add(SkillVariable.builder()
                        .name(varName)
                        .build());
            }
        }

        return definitions;
    }

    /**
     * Resolve variable values from multiple sources.
     */
    public Map<String, String> resolveVariables(String skillName, List<SkillVariable> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return Collections.emptyMap();
        }

        // Load per-skill vars.json
        Map<String, String> perSkillVars = loadPerSkillVars(skillName);

        // Load global variables.json
        Map<String, Map<String, String>> globalVars = loadGlobalVars();
        Map<String, String> globalSkillSection = globalVars.getOrDefault(skillName, Collections.emptyMap());
        Map<String, String> globalSection = globalVars.getOrDefault(GLOBAL_SECTION, Collections.emptyMap());

        Map<String, String> resolved = new HashMap<>();

        for (SkillVariable def : definitions) {
            String name = def.getName();
            String value = null;

            // Priority 1: per-skill vars.json
            if (perSkillVars.containsKey(name)) {
                value = perSkillVars.get(name);
            }

            // Priority 2: global variables.json → skill-specific section
            if (value == null && globalSkillSection.containsKey(name)) {
                value = globalSkillSection.get(name);
            }

            // Priority 3: global variables.json → _global section
            if (value == null && globalSection.containsKey(name)) {
                value = globalSection.get(name);
            }

            // Priority 4: environment variable
            if (value == null) {
                value = System.getenv(name);
            }

            // Priority 5: default from definition
            if (value == null && def.getDefaultValue() != null) {
                value = def.getDefaultValue();
            }

            if (value != null) {
                resolved.put(name, value);
            }
        }

        return resolved;
    }

    /**
     * Find required variables that have no resolved value.
     */
    public List<String> findMissingRequired(List<SkillVariable> definitions, Map<String, String> resolved) {
        if (definitions == null) {
            return Collections.emptyList();
        }

        return definitions.stream()
                .filter(SkillVariable::isRequired)
                .map(SkillVariable::getName)
                .filter(name -> !resolved.containsKey(name))
                .toList();
    }

    /**
     * Mask secret variable values for logging.
     */
    public Map<String, String> maskSecrets(List<SkillVariable> definitions, Map<String, String> resolved) {
        if (definitions == null || resolved == null) {
            return Collections.emptyMap();
        }

        Set<String> secretNames = new HashSet<>();
        for (SkillVariable def : definitions) {
            if (def.isSecret()) {
                secretNames.add(def.getName());
            }
        }

        Map<String, String> masked = new HashMap<>();
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            if (secretNames.contains(entry.getKey())) {
                masked.put(entry.getKey(), "***");
            } else {
                masked.put(entry.getKey(), entry.getValue());
            }
        }

        return masked;
    }

    private Map<String, String> loadPerSkillVars(String skillName) {
        try {
            String json = storagePort.getText(SKILLS_DIR, skillName + "/vars.json").join();
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, STRING_MAP_TYPE_REF);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("No per-skill vars.json for {}: {}", skillName, e.getMessage());
        }
        return Collections.emptyMap();
    }

    private Map<String, Map<String, String>> loadGlobalVars() {
        try {
            String json = storagePort.getText("", "variables.json").join();
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, NESTED_MAP_TYPE_REF);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("No global variables.json: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }
}
