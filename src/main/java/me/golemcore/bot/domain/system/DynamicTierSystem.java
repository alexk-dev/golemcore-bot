package me.golemcore.bot.domain.system;

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

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * System for dynamically upgrading model tier to "coding" when code-related
 * activity is detected (order=25). Analyzes tool calls and results from the
 * current agent loop run (not old history) for signals like FileSystem
 * operations on code files, Shell execution of code commands, or stack traces
 * in tool results. Runs after ContextBuildingSystem, before the LLM request
 * execution step (ToolLoop). Only runs on iteration > 0. Only upgrades tier,
 * never downgrades, to prevent oscillation. Skipped entirely when user has tier
 * force enabled.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DynamicTierSystem implements AgentSystem {

    private final RuntimeConfigService runtimeConfigService;
    private final UserPreferencesService userPreferencesService;

    private static final String TOOL_NAME_SHELL = "shell";
    private static final Set<String> CODE_EXTENSIONS = Set.of(
            ".py", ".js", ".ts", ".jsx", ".tsx", ".java", ".go", ".rs", ".rb",
            ".sh", ".bash", ".c", ".cpp", ".h", ".hpp", ".cs", ".kt", ".scala",
            ".swift", ".lua", ".r", ".pl", ".php", ".sql", ".yaml", ".yml",
            ".toml", ".gradle", ".cmake", ".makefile");

    private static final Set<String> CODE_COMMANDS = Set.of(
            "python", "python3", "node", "npm", "npx", "pip", "pip3",
            "mvn", "gradle", "gcc", "g++", "cargo", "go", "rustc",
            "pytest", "make", "cmake", "javac", "dotnet", "ruby",
            "tsc", "webpack", "esbuild", "jest", "mocha", "yarn");

    private static final Set<String> STACK_TRACE_PATTERNS = Set.of(
            "Traceback", "SyntaxError", "TypeError", "NameError",
            "ValueError", "AttributeError", "ImportError", "ModuleNotFoundError",
            "CompileError", "ReferenceError", "NullPointerException",
            "ClassNotFoundException", "at com.", "at org.", "at java.",
            "FAILED", "error[E", "panic:", "thread 'main' panicked");

    @Override
    public String getName() {
        return "DynamicTierSystem";
    }

    @Override
    public int getOrder() {
        return 25;
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfigService.isDynamicTierEnabled();
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        if (userPreferencesService.getPreferences().isTierForce()) {
            return false;
        }
        return context.getCurrentIteration() > 0
                && !"coding".equals(context.getModelTier())
                && !"deep".equals(context.getModelTier());
    }

    @Override
    public AgentContext process(AgentContext context) {
        List<Message> messages = context.getMessages();
        if (messages == null || messages.isEmpty()) {
            return context;
        }

        // Only scan messages from the current agent loop run (after the last user
        // message).
        // This prevents false positives from old coding activity in conversation
        // history.
        List<Message> currentRunMessages = getMessagesAfterLastUserMessage(messages);
        if (currentRunMessages.isEmpty()) {
            return context;
        }

        if (hasCodingSignals(currentRunMessages)) {
            String previousTier = context.getModelTier();
            context.setModelTier("coding");
            log.info("[DynamicTier] Detected coding activity, upgrading tier: {} -> coding", previousTier);
        }

        return context;
    }

    /**
     * Returns messages after the last user message (exclusive). These are the
     * assistant tool calls and tool results from the current agent loop.
     */
    List<Message> getMessagesAfterLastUserMessage(List<Message> messages) {
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).isUserMessage()) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx < 0 || lastUserIdx >= messages.size() - 1) {
            return List.of();
        }
        return messages.subList(lastUserIdx + 1, messages.size());
    }

    boolean hasCodingSignals(List<Message> messages) {
        for (Message msg : messages) {
            if (msg.isAssistantMessage() && msg.hasToolCalls()) {
                for (Message.ToolCall toolCall : msg.getToolCalls()) {
                    if (isCodeToolCall(toolCall)) {
                        return true;
                    }
                }
            }
            if (msg.isToolMessage()) {
                if (hasCodePatterns(msg.getContent())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isCodeToolCall(Message.ToolCall toolCall) {
        String name = toolCall.getName();
        Map<String, Object> args = toolCall.getArguments();
        if (args == null) {
            return false;
        }

        if ("filesystem".equals(name) || "file_system".equals(name)) {
            String operation = stringArg(args, "operation");
            String path = stringArg(args, "path");
            if (path != null && ("write_file".equals(operation) || "read_file".equals(operation))) {
                return isCodeFile(path);
            }
        }

        if (TOOL_NAME_SHELL.equals(name)) {
            String command = stringArg(args, "command");
            if (command != null) {
                return isCodeCommand(command);
            }
        }

        return false;
    }

    boolean isCodeFile(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        // Check known extensions
        for (String ext : CODE_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        // Check Makefile (no extension)
        String filename = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;
        return "makefile".equals(filename) || "dockerfile".equals(filename);
    }

    boolean isCodeCommand(String command) {
        if (command == null) {
            return false;
        }
        String trimmed = command.trim();
        for (String prefix : CODE_COMMANDS) {
            if (trimmed.equals(prefix) || trimmed.startsWith(prefix + " ")) {
                return true;
            }
        }
        return false;
    }

    boolean hasCodePatterns(String content) {
        if (content == null) {
            return false;
        }
        for (String pattern : STACK_TRACE_PATTERNS) {
            if (content.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String stringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof String ? (String) value : null;
    }
}
