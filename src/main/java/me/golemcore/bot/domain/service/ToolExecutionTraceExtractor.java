package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolExecutionTrace;
import me.golemcore.bot.domain.system.toolloop.ToolExecutionOutcome;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Builds normalized user-facing execution traces from tool calls and outcomes.
 */
@Component
public class ToolExecutionTraceExtractor {

    private static final int MAX_ACTION_LENGTH = 160;
    private static final int MAX_VALUE_LENGTH = 120;

    public ToolExecutionTrace extract(Message.ToolCall toolCall, ToolExecutionOutcome outcome, long durationMs) {
        String toolName = toolCall != null && toolCall.getName() != null ? toolCall.getName() : "tool";
        Map<String, Object> arguments = toolCall != null && toolCall.getArguments() != null
                ? toolCall.getArguments()
                : Map.of();
        Map<String, Object> resultData = outcome != null && outcome.toolResult() != null
                && outcome.toolResult().getData() != null
                        ? castDetails(outcome.toolResult().getData())
                        : Map.of();

        String family = resolveFamily(toolName);
        Map<String, Object> details = new LinkedHashMap<>();
        String action = switch (family) {
        case "shell" -> extractShellAction(arguments, resultData, details);
        case "filesystem" -> extractFilesystemAction(arguments, details);
        case "search" -> extractSearchAction(arguments, details);
        case "browse" -> extractBrowseAction(arguments, details);
        default -> extractGenericAction(toolName, arguments, details);
        };

        boolean success = outcome != null && outcome.toolResult() != null && outcome.toolResult().isSuccess();
        if (resultData.containsKey("exitCode")) {
            details.put("exitCode", resultData.get("exitCode"));
        }
        if (toolName != null) {
            details.put("tool", toolName);
        }

        return new ToolExecutionTrace(toolName, family, truncate(action, MAX_ACTION_LENGTH), success, durationMs,
                details);
    }

    private String resolveFamily(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "tool";
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        if ("shell".equals(normalized)) {
            return "shell";
        }
        if ("filesystem".equals(normalized)) {
            return "filesystem";
        }
        if (normalized.contains("search") || normalized.startsWith("perplexity_")) {
            return "search";
        }
        if (normalized.contains("browse") || normalized.contains("browser") || normalized.contains("firecrawl")
                || normalized.startsWith("pinchtab_")) {
            return "browse";
        }
        return "tool";
    }

    private String extractShellAction(Map<String, Object> arguments, Map<String, Object> resultData,
            Map<String, Object> details) {
        String command = safeString(arguments.get("command"));
        String workdir = firstNonBlank(
                safeString(arguments.get("workdir")),
                safeString(resultData.get("workdir")));
        if (!command.isBlank()) {
            details.put("command", truncate(command, MAX_VALUE_LENGTH));
        }
        if (!workdir.isBlank()) {
            details.put("workdir", truncate(workdir, MAX_VALUE_LENGTH));
        }
        if (!command.isBlank() && !workdir.isBlank()) {
            return "ran `" + truncate(command, 80) + "` in " + truncate(workdir, 60);
        }
        if (!command.isBlank()) {
            return "ran `" + truncate(command, 100) + "`";
        }
        return "ran a shell command";
    }

    private String extractFilesystemAction(Map<String, Object> arguments, Map<String, Object> details) {
        String operation = safeString(arguments.get("operation"));
        String path = safeString(arguments.get("path"));
        if (!operation.isBlank()) {
            details.put("operation", operation);
        }
        if (!path.isBlank()) {
            details.put("path", truncate(path, MAX_VALUE_LENGTH));
        }
        if (!operation.isBlank() && !path.isBlank()) {
            return humanizeFilesystemOperation(operation) + " " + truncate(path, 80);
        }
        if (!path.isBlank()) {
            return "worked with " + truncate(path, 80);
        }
        return "worked with files in the workspace";
    }

    private String extractSearchAction(Map<String, Object> arguments, Map<String, Object> details) {
        String query = firstNonBlank(
                safeString(arguments.get("query")),
                safeString(arguments.get("question")));
        if (!query.isBlank()) {
            details.put("query", truncate(query, MAX_VALUE_LENGTH));
            return "searched for " + quote(truncate(query, 100));
        }
        return "ran a search";
    }

    private String extractBrowseAction(Map<String, Object> arguments, Map<String, Object> details) {
        String url = safeString(arguments.get("url"));
        String query = safeString(arguments.get("query"));
        if (!url.isBlank()) {
            details.put("url", truncate(url, MAX_VALUE_LENGTH));
            return "checked " + truncate(url, 100);
        }
        if (!query.isBlank()) {
            details.put("query", truncate(query, MAX_VALUE_LENGTH));
            return "looked up " + quote(truncate(query, 100));
        }
        return "inspected web content";
    }

    private String extractGenericAction(String toolName, Map<String, Object> arguments, Map<String, Object> details) {
        StringJoiner joiner = new StringJoiner(", ");
        arguments.forEach((key, value) -> {
            if (joiner.length() > 0 || details.size() >= 3) {
                return;
            }
            String normalized = safeString(value);
            if (!normalized.isBlank()) {
                details.put(key, truncate(normalized, MAX_VALUE_LENGTH));
                joiner.add(key + "=" + quote(truncate(normalized, 40)));
            }
        });
        if (joiner.length() > 0) {
            return "used " + toolName + " (" + joiner + ")";
        }
        return "used " + toolName;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castDetails(Object data) {
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key instanceof String keyString) {
                    normalized.put(keyString, value);
                }
            });
            return normalized;
        }
        return Map.of();
    }

    private String humanizeFilesystemOperation(String operation) {
        return switch (operation) {
        case "read_file" -> "read";
        case "write_file" -> "updated";
        case "list_directory" -> "listed";
        case "create_directory" -> "created";
        case "delete" -> "deleted";
        case "file_info" -> "checked";
        case "send_file" -> "prepared";
        default -> "worked with";
        };
    }

    private String firstNonBlank(String primary, String fallback) {
        return !primary.isBlank() ? primary : fallback;
    }

    private String safeString(Object value) {
        if (value == null) {
            return "";
        }
        String rendered = String.valueOf(value).trim();
        return rendered.replaceAll("\\s+", " ");
    }

    private String quote(String value) {
        return "\"" + value + "\"";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
