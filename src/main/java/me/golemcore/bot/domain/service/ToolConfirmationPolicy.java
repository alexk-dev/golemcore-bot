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

import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.infrastructure.config.BotProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Policy engine determining which tool calls require explicit user confirmation
 * before execution. Identifies potentially destructive operations (file
 * deletion, shell commands, skill deletion) and provides human-readable action
 * descriptions for confirmation prompts. Can be disabled via configuration
 * while still detecting notable actions for informational notifications.
 */
@Component
@Slf4j
public class ToolConfirmationPolicy {

    private static final String OPERATION = "operation";
    private static final String UNKNOWN = "unknown";
    private static final String DELETE = "delete";
    private static final String DELETE_SKILL = "delete_skill";
    private static final int COMMAND_LENGTH_THRESHOLD = 80;

    private final boolean enabled;

    public ToolConfirmationPolicy(BotProperties properties) {
        this.enabled = properties.getSecurity().getToolConfirmation().isEnabled();
        log.info("ToolConfirmationPolicy enabled: {}", enabled);
    }

    /**
     * Check if a tool call requires user confirmation.
     */
    public boolean requiresConfirmation(Message.ToolCall toolCall) {
        if (!enabled) {
            return false;
        }
        return isNotableAction(toolCall);
    }

    /**
     * Check if a tool call is a notable/dangerous action (regardless of enabled
     * state). Used for informational notifications when confirmation is disabled.
     */
    public boolean isNotableAction(Message.ToolCall toolCall) {
        String toolName = toolCall.getName();
        Map<String, Object> args = toolCall.getArguments();

        return switch (toolName) {
        case "filesystem" -> isDestructiveFileOp(args);
        case "shell" -> true;
        case "skill_management" -> isDestructiveSkillOp(args);
        default -> false;
        };
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Build a human-readable description of the action for the confirmation prompt.
     */
    public String describeAction(Message.ToolCall toolCall) {
        String toolName = toolCall.getName();
        Map<String, Object> args = toolCall.getArguments();

        return switch (toolName) {
        case "filesystem" -> describeFileAction(args);
        case "shell" -> describeShellAction(args);
        case "skill_management" -> describeSkillAction(args);
        default -> toolName + ": " + args;
        };
    }

    private boolean isDestructiveFileOp(Map<String, Object> args) {
        if (args == null)
            return false;
        String operation = (String) args.get(OPERATION);
        return DELETE.equals(operation);
    }

    private boolean isDestructiveSkillOp(Map<String, Object> args) {
        if (args == null)
            return false;
        String operation = (String) args.get(OPERATION);
        return DELETE_SKILL.equals(operation);
    }

    private String describeFileAction(Map<String, Object> args) {
        String operation = args != null ? (String) args.get(OPERATION) : UNKNOWN;
        String path = args != null ? (String) args.get("path") : UNKNOWN;
        if (DELETE.equals(operation)) {
            return "Delete file: " + (path != null ? path : UNKNOWN);
        }
        return "File operation: " + operation + " on " + path;
    }

    private String describeShellAction(Map<String, Object> args) {
        String command = args != null ? (String) args.get("command") : UNKNOWN;
        if (command != null && command.length() > COMMAND_LENGTH_THRESHOLD) {
            command = command.substring(0, COMMAND_LENGTH_THRESHOLD) + "...";
        }
        return "Run command: " + command;
    }

    private String describeSkillAction(Map<String, Object> args) {
        String operation = args != null ? (String) args.get(OPERATION) : UNKNOWN;
        String name = args != null ? (String) args.get("name") : UNKNOWN;
        if (DELETE_SKILL.equals(operation)) {
            return "Delete skill: " + (name != null ? name : UNKNOWN);
        }
        return "Skill operation: " + operation;
    }
}
