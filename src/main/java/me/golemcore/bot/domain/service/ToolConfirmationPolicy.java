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
        String operation = (String) args.get("operation");
        return "delete".equals(operation);
    }

    private boolean isDestructiveSkillOp(Map<String, Object> args) {
        if (args == null)
            return false;
        String operation = (String) args.get("operation");
        return "delete_skill".equals(operation);
    }

    private String describeFileAction(Map<String, Object> args) {
        String operation = args != null ? (String) args.get("operation") : "unknown";
        String path = args != null ? (String) args.get("path") : "unknown";
        if ("delete".equals(operation)) {
            return "Delete file: " + (path != null ? path : "unknown");
        }
        return "File operation: " + operation + " on " + path;
    }

    private String describeShellAction(Map<String, Object> args) {
        String command = args != null ? (String) args.get("command") : "unknown";
        if (command != null && command.length() > 80) {
            command = command.substring(0, 80) + "...";
        }
        return "Run command: " + command;
    }

    private String describeSkillAction(Map<String, Object> args) {
        String operation = args != null ? (String) args.get("operation") : "unknown";
        String name = args != null ? (String) args.get("name") : "unknown";
        if ("delete_skill".equals(operation)) {
            return "Delete skill: " + (name != null ? name : "unknown");
        }
        return "Skill operation: " + operation;
    }
}
