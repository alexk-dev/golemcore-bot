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

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolNames;

/**
 * Enforces the restricted tool surface while plan mode is active.
 *
 * <p>
 * Plan mode is intended for inspection and planning. Durable planning state may
 * still be maintained through goals/tasks, but shell execution and workspace
 * mutations are blocked until the user leaves plan mode.
 */
public class PlanModeToolRestrictionService {

    public static final String LOCAL_PLAN_MARKDOWN_GLOB = ".golemcore/plans/*.md";

    private static final Set<String> ALLOWED_TOOL_NAMES = Set.of(
            ToolNames.GOAL_MANAGEMENT,
            ToolNames.FILESYSTEM,
            ToolNames.PLAN_EXIT);
    private static final Set<String> DENIED_TOOL_NAMES = Set.of(
            ToolNames.SHELL,
            "bash",
            "shell_command",
            "task",
            "subagent",
            "agent",
            "write",
            "edit",
            "patch",
            "apply_patch");
    private static final Set<String> READ_ONLY_FILESYSTEM_OPERATIONS = Set.of(
            "read_file",
            "list_directory",
            "file_info");
    private static final Set<String> PLAN_MODE_FILESYSTEM_OPERATIONS = Set.of(
            "read_file",
            "list_directory",
            "file_info",
            "write_file");

    private final PlanService planService;

    public PlanModeToolRestrictionService(PlanService planService) {
        this.planService = planService;
    }

    public boolean isPlanModeActive(AgentContext context) {
        if (planService == null) {
            return false;
        }
        SessionIdentity sessionIdentity = SessionIdentitySupport.resolveSessionIdentity(
                context != null ? context.getSession() : null);
        return sessionIdentity != null
                ? planService.isPlanModeActive(sessionIdentity)
                : planService.isPlanModeActive();
    }

    public boolean shouldAdvertiseTool(AgentContext context, String toolName) {
        String normalized = normalizeToolName(toolName);
        if (!isPlanModeActive(context)) {
            if (ToolNames.PLAN_EXIT.equals(normalized)) {
                return false;
            }
            return true;
        }
        return ALLOWED_TOOL_NAMES.contains(normalized);
    }

    public ToolDefinition restrictToolDefinition(AgentContext context, ToolDefinition toolDefinition) {
        if (!isPlanModeActive(context) || toolDefinition == null) {
            return toolDefinition;
        }
        String toolName = normalizeToolName(toolDefinition.getName());
        if (!ToolNames.FILESYSTEM.equals(toolName)) {
            return toolDefinition;
        }
        String planFilePath = activePlanFilePath(context).orElse(LOCAL_PLAN_MARKDOWN_GLOB);
        return ToolDefinition.builder()
                .name(toolDefinition.getName())
                .description("Read, list, or inspect workspace files. In Plan Mode, write_file is only allowed for `"
                        + planFilePath + "`.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "operation", Map.of(
                                        "type", "string",
                                        "enum", PLAN_MODE_FILESYSTEM_OPERATIONS.stream().sorted().toList(),
                                        "description", "Plan Mode operation to perform"),
                                "path", Map.of(
                                        "type", "string",
                                        "description", "Read path, or the exact writable plan file: " + planFilePath),
                                "content", Map.of(
                                        "type", "string",
                                        "description", "Markdown content for write_file when path is "
                                                + planFilePath)),
                        "required", java.util.List.of("operation", "path")))
                .build();
    }

    public Optional<String> denialReason(AgentContext context, Message.ToolCall toolCall) {
        if (!isPlanModeActive(context) || toolCall == null) {
            return Optional.empty();
        }
        String toolName = normalizeToolName(toolCall.getName());
        if (ToolNames.GOAL_MANAGEMENT.equals(toolName) || ToolNames.PLAN_EXIT.equals(toolName)) {
            return Optional.empty();
        }
        if (DENIED_TOOL_NAMES.contains(toolName)) {
            return Optional.of("Plan mode is active. The `" + toolName
                    + "` tool is disabled until plan mode is finished.");
        }
        if (ToolNames.FILESYSTEM.equals(toolName)) {
            return filesystemDenialReason(context, toolCall.getArguments());
        }
        return Optional.of("Plan mode is active. The `" + toolName
                + "` tool is not allowed by the plan mode policy.");
    }

    private Optional<String> filesystemDenialReason(AgentContext context, Map<String, Object> arguments) {
        String operation = readString(arguments, "operation");
        if (operation == null) {
            return Optional.of("Plan mode is active. Filesystem calls must declare an operation.");
        }
        String normalizedOperation = operation.trim().toLowerCase(Locale.ROOT);
        if (READ_ONLY_FILESYSTEM_OPERATIONS.contains(normalizedOperation)) {
            return Optional.empty();
        }
        if ("write_file".equals(normalizedOperation)
                && isActivePlanFilePath(context, arguments)) {
            return Optional.empty();
        }
        String planFilePath = activePlanFilePath(context).orElse(LOCAL_PLAN_MARKDOWN_GLOB);
        return Optional.of("Plan mode is active. Filesystem is read-only except the plan file `"
                + planFilePath + "`.");
    }

    private boolean isActivePlanFilePath(AgentContext context, Map<String, Object> arguments) {
        String path = readString(arguments, "path");
        Optional<String> activePlanFilePath = activePlanFilePath(context);
        if (activePlanFilePath.isPresent()) {
            return activePlanFilePath.get().equals(normalizeRelativePlanPath(path));
        }
        return false;
    }

    private Optional<String> activePlanFilePath(AgentContext context) {
        SessionIdentity sessionIdentity = SessionIdentitySupport.resolveSessionIdentity(
                context != null ? context.getSession() : null);
        return sessionIdentity != null
                ? planService.getActivePlanFilePath(sessionIdentity)
                : planService.getActivePlanFilePath();
    }

    private String normalizeRelativePlanPath(String path) {
        if (StringValueSupport.isBlank(path)) {
            return null;
        }
        try {
            Path rawPath = Path.of(path.trim());
            if (rawPath.isAbsolute() || containsParentReference(rawPath)) {
                return null;
            }
            Path normalized = rawPath.normalize();
            if (normalized.getNameCount() != 3) {
                return null;
            }
            String root = normalized.getName(0).toString();
            String directory = normalized.getName(1).toString();
            String fileName = normalized.getName(2).toString();
            boolean valid = ".golemcore".equals(root)
                    && "plans".equals(directory)
                    && fileName.endsWith(".md")
                    && fileName.length() > ".md".length();
            return valid ? normalized.toString().replace('\\', '/') : null;
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private boolean containsParentReference(Path path) {
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private String readString(Map<String, Object> arguments, String key) {
        if (arguments == null) {
            return null;
        }
        Object value = arguments.get(key);
        return value instanceof String stringValue ? stringValue : null;
    }

    private String normalizeToolName(String toolName) {
        return toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
    }
}
