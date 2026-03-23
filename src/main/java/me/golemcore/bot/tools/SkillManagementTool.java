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

package me.golemcore.bot.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillDocument;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SkillDocumentService;
import me.golemcore.bot.domain.service.SkillMarketplaceService;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Tool for managing skills (create, list, get, delete).
 *
 * <p>
 * Allows the LLM to dynamically create new skills on behalf of the user. Skills
 * are stored as SKILL.md files with YAML frontmatter in the skills workspace.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillManagementTool implements ToolComponent {

    private static final String PARAM_OPERATION = "operation";
    private static final String PARAM_NAME = "name";
    private static final String PARAM_DESCRIPTION = "description";
    private static final String PARAM_CONTENT = "content";
    private static final String PARAM_TYPE = "type";
    private static final String TYPE_STRING = "string";
    private static final String TYPE_OBJECT = "object";
    private static final String OP_CREATE_SKILL = "create_skill";
    private static final String OP_LIST_SKILLS = "list_skills";
    private static final String OP_GET_SKILL = "get_skill";
    private static final String OP_DELETE_SKILL = "delete_skill";

    private static final String SKILLS_DIR = "skills";
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    private static final int MAX_NAME_LENGTH = 50;
    private static final int MAX_DESCRIPTION_LENGTH = 200;
    private static final int MAX_CONTENT_LENGTH = 50_000;

    private final RuntimeConfigService runtimeConfigService;
    private final StoragePort storagePort;
    private final SkillComponent skillComponent;
    private final SkillMarketplaceService skillMarketplaceService;
    private final SkillDocumentService skillDocumentService;

    @Override
    public boolean isEnabled() {
        return runtimeConfigService.isSkillManagementEnabled();
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("skill_management")
                .description("""
                        Manage bot skills. Use this tool to create, list, view, or delete skills.
                        Skills define how the bot handles specific types of requests.
                        Operations: create_skill, list_skills, get_skill, delete_skill.
                        """)
                .inputSchema(Map.of(
                        PARAM_TYPE, TYPE_OBJECT,
                        "properties", Map.of(
                                PARAM_OPERATION, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        "enum", List.of(OP_CREATE_SKILL, OP_LIST_SKILLS, OP_GET_SKILL, OP_DELETE_SKILL),
                                        PARAM_DESCRIPTION, "Operation to perform"),
                                PARAM_NAME, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        PARAM_DESCRIPTION,
                                        "Skill name (lowercase, alphanumeric with hyphens, e.g. 'greeting' or 'code-review')"),
                                PARAM_DESCRIPTION, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        PARAM_DESCRIPTION,
                                        "Short description of what the skill does (for create_skill)"),
                                PARAM_CONTENT, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        PARAM_DESCRIPTION,
                                        "Full skill instructions/content in markdown (for create_skill)")),
                        "required", List.of(PARAM_OPERATION)))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("[SkillManagement] Execute: {}", parameters);

            if (!isEnabled()) {
                return ToolResult.failure("Skill management tool is disabled");
            }

            try {
                String operation = (String) parameters.get(PARAM_OPERATION);
                if (operation == null) {
                    return ToolResult.failure("Missing required parameter: operation");
                }

                return switch (operation) {
                case OP_CREATE_SKILL -> createSkill(parameters);
                case OP_LIST_SKILLS -> listSkills();
                case OP_GET_SKILL -> getSkill(parameters);
                case OP_DELETE_SKILL -> deleteSkill(parameters);
                default -> ToolResult.failure("Unknown operation: " + operation);
                };
            } catch (Exception ex) {
                log.error("[SkillManagement] Error: {}", ex.getMessage(), ex);
                return ToolResult.failure("Error: " + ex.getMessage());
            }
        });
    }

    private ToolResult createSkill(Map<String, Object> parameters) {
        String name = (String) parameters.get(PARAM_NAME);
        String description = (String) parameters.get(PARAM_DESCRIPTION);
        String content = (String) parameters.get(PARAM_CONTENT);

        if (name == null || name.isBlank()) {
            return ToolResult.failure("Missing required parameter: name");
        }
        if (description == null || description.isBlank()) {
            return ToolResult.failure("Missing required parameter: description");
        }
        if (content == null || content.isBlank()) {
            return ToolResult.failure("Missing required parameter: content");
        }

        String normalizedName = name.trim();
        String normalizedDescription = description.trim();

        if (!NAME_PATTERN.matcher(normalizedName).matches()) {
            return ToolResult
                    .failure("Invalid skill name: must be lowercase alphanumeric with hyphens (e.g. 'my-skill')");
        }
        if (normalizedName.length() > MAX_NAME_LENGTH) {
            return ToolResult.failure("Skill name too long (max " + MAX_NAME_LENGTH + " characters)");
        }
        if (normalizedDescription.length() > MAX_DESCRIPTION_LENGTH) {
            return ToolResult.failure("Description too long (max " + MAX_DESCRIPTION_LENGTH + " characters)");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            return ToolResult.failure("Content too long (max " + MAX_CONTENT_LENGTH + " characters)");
        }

        Optional<Skill> existing = skillComponent.findByName(normalizedName);
        if (existing.isPresent()) {
            return ToolResult.failure(
                    "Skill '" + normalizedName + "' already exists. Delete it first or choose a different name.");
        }

        SkillDocument parsedDocument = skillDocumentService.parseNormalizedDocument(content);
        Map<String, Object> metadata = new LinkedHashMap<>(parsedDocument.metadata());
        metadata.put("name", normalizedName);
        metadata.put("description", normalizedDescription);
        String skillDocument = skillDocumentService.renderDocument(metadata, parsedDocument.body());

        String path = normalizedName + "/SKILL.md";
        try {
            storagePort.putText(SKILLS_DIR, path, skillDocument).join();
            skillComponent.reload();
            log.info("[SkillManagement] Created skill: {}", normalizedName);
            return ToolResult.success("Skill '" + normalizedName + "' created successfully.", Map.of(
                    PARAM_NAME, normalizedName,
                    "path", SKILLS_DIR + "/" + path));
        } catch (Exception ex) {
            log.error("[SkillManagement] Failed to create skill: {}", normalizedName, ex);
            return ToolResult.failure("Failed to create skill: " + ex.getMessage());
        }
    }

    private ToolResult listSkills() {
        List<Skill> skills = skillComponent.getAvailableSkills();

        if (skills.isEmpty()) {
            return ToolResult.success("No skills available.");
        }

        String list = skills.stream()
                .map(skill -> String.format("- %s: %s", skill.getName(), skill.getDescription()))
                .collect(Collectors.joining("\n"));

        return ToolResult.success("Available skills (" + skills.size() + "):\n" + list, Map.of(
                "count", skills.size(),
                "skills", skills.stream().map(Skill::getName).collect(Collectors.toList())));
    }

    private ToolResult getSkill(Map<String, Object> parameters) {
        String name = (String) parameters.get(PARAM_NAME);
        if (name == null || name.isBlank()) {
            return ToolResult.failure("Missing required parameter: name");
        }

        Optional<Skill> skill = skillComponent.findByName(name);
        if (skill.isEmpty()) {
            return ToolResult.failure("Skill not found: " + name);
        }

        Skill resolvedSkill = skill.get();
        String output = String.format("Skill: %s%nDescription: %s%nAvailable: %s%n%n%s",
                resolvedSkill.getName(), resolvedSkill.getDescription(), resolvedSkill.isAvailable(),
                resolvedSkill.getContent());

        return ToolResult.success(output, Map.of(
                PARAM_NAME, resolvedSkill.getName(),
                PARAM_DESCRIPTION, resolvedSkill.getDescription(),
                "available", resolvedSkill.isAvailable()));
    }

    private ToolResult deleteSkill(Map<String, Object> parameters) {
        String name = (String) parameters.get(PARAM_NAME);
        if (name == null || name.isBlank()) {
            return ToolResult.failure("Missing required parameter: name");
        }

        Optional<Skill> existing = skillComponent.findByName(name);
        if (existing.isEmpty()) {
            return ToolResult.failure("Skill not found: " + name);
        }

        try {
            skillMarketplaceService.deleteManagedSkill(existing.get());
            skillComponent.reload();
            log.info("[SkillManagement] Deleted skill: {}", name);
            return ToolResult.success("Skill '" + name + "' deleted successfully.");
        } catch (Exception ex) {
            log.error("[SkillManagement] Failed to delete skill: {}", name, ex);
            return ToolResult.failure("Failed to delete skill: " + ex.getMessage());
        }
    }

}
