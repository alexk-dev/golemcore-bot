package me.golemcore.bot.domain.system.toolloop.repeat;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolNames;

/**
 * Builds stable, redacted fingerprints for tool calls before execution.
 */
public class ToolUseFingerprintService {

    private static final Set<String> VOLATILE_FIELDS = Set.of(
            "timestamp", "nonce", "requestid", "request_id", "traceid", "trace_id", "callid", "call_id");
    private static final Set<String> SECRET_FIELD_FRAGMENTS = Set.of(
            "token", "password", "secret", "apikey", "api_key", "authorization");
    private static final Set<String> SHELL_WORKING_DIRECTORY_FIELDS = Set.of(
            "cwd", "workdir", "workingdirectory", "working_directory");

    private final ToolSemanticsRegistry semanticsRegistry;

    public ToolUseFingerprintService() {
        this(new ToolSemanticsRegistry());
    }

    ToolUseFingerprintService(ToolSemanticsRegistry semanticsRegistry) {
        this.semanticsRegistry = Objects.requireNonNull(semanticsRegistry);
    }

    public ToolUseFingerprint fingerprint(Message.ToolCall toolCall) {
        String toolName = normalizeToolName(toolCall != null ? toolCall.getName() : null);
        ToolSemantics semantics = semanticsRegistry.semantics(
                toolName, toolCall != null ? toolCall.getArguments() : null);
        Object canonicalArguments = canonicalize(
                toolName,
                toolCall != null && toolCall.getArguments() != null ? toolCall.getArguments() : Map.of());
        String debugArguments = canonicalJson(canonicalArguments);
        String hash = sha256(debugArguments);
        String stableKey = toolName + ":" + semantics.category() + ":" + hash;
        return new ToolUseFingerprint(
                toolName,
                semantics.category(),
                "sha256:" + hash,
                stableKey,
                debugArguments,
                semantics.observedDomains(),
                semantics.invalidatedDomains());
    }

    private Object canonicalize(String toolName, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return canonicalizeMap(toolName, map);
        }
        if (value instanceof Collection<?> collection) {
            return canonicalizeCollection(toolName, collection);
        }
        if (value.getClass().isArray()) {
            return canonicalizeArray(toolName, value);
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof String) {
            return value;
        }
        return String.valueOf(value);
    }

    private Map<String, Object> canonicalizeMap(String toolName, Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> sorted = new TreeMap<>();
        List<String> shellWorkingDirectories = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String fieldName = String.valueOf(entry.getKey());
            String normalizedField = normalizeValue(fieldName);
            if (!VOLATILE_FIELDS.contains(normalizedField)) {
                if (ToolNames.SHELL.equals(toolName) && SHELL_WORKING_DIRECTORY_FIELDS.contains(normalizedField)) {
                    if (entry.getValue() instanceof String stringValue && !stringValue.isBlank()) {
                        shellWorkingDirectories.add(normalizePath(stringValue));
                    }
                    continue;
                }
                sorted.put(canonicalFieldName(toolName, fieldName, normalizedField),
                        canonicalizeMapValue(toolName, normalizedField, entry.getValue()));
            }
        }
        if (ToolNames.SHELL.equals(toolName)) {
            List<String> canonicalDirectories = shellWorkingDirectories.stream()
                    .distinct()
                    .sorted()
                    .toList();
            if (canonicalDirectories.isEmpty()) {
                sorted.put("cwd", normalizePath("."));
            } else if (canonicalDirectories.size() == 1) {
                sorted.put("cwd", canonicalDirectories.getFirst());
            } else {
                sorted.put("cwd", canonicalDirectories.getFirst());
                sorted.put("cwd_aliases", canonicalDirectories);
                sorted.put("cwd_aliases_conflict", true);
            }
        }
        result.putAll(sorted);
        return result;
    }

    private String canonicalFieldName(String toolName, String fieldName, String normalizedField) {
        if (ToolNames.SHELL.equals(toolName) && SHELL_WORKING_DIRECTORY_FIELDS.contains(normalizedField)) {
            return "cwd";
        }
        return fieldName;
    }

    private Object canonicalizeMapValue(String toolName, String normalizedField, Object entryValue) {
        if (isSecretField(normalizedField)) {
            return "<redacted>";
        }
        if (ToolNames.FILESYSTEM.equals(toolName) && "path".equals(normalizedField)
                && entryValue instanceof String stringValue) {
            return normalizePath(stringValue);
        }
        if (ToolNames.SHELL.equals(toolName) && SHELL_WORKING_DIRECTORY_FIELDS.contains(normalizedField)
                && entryValue instanceof String stringValue) {
            return normalizePath(stringValue);
        }
        return canonicalize(toolName, entryValue);
    }

    private List<Object> canonicalizeCollection(String toolName, Collection<?> collection) {
        List<Object> result = new ArrayList<>(collection.size());
        collection.forEach(item -> result.add(canonicalize(toolName, item)));
        return result;
    }

    private List<Object> canonicalizeArray(String toolName, Object value) {
        int length = Array.getLength(value);
        List<Object> result = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            result.add(canonicalize(toolName, Array.get(value, index)));
        }
        return result;
    }

    private String canonicalJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder();
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(quote(String.valueOf(entry.getKey())))
                        .append(':')
                        .append(canonicalJson(entry.getValue()));
            }
            builder.append('}');
            return builder.toString();
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder builder = new StringBuilder();
            builder.append('[');
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(canonicalJson(item));
            }
            builder.append(']');
            return builder.toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return quote(Objects.toString(value, ""));
    }

    private String quote(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
            case '"' -> builder.append("\\\"");
            case '\\' -> builder.append("\\\\");
            case '\b' -> builder.append("\\b");
            case '\f' -> builder.append("\\f");
            case '\n' -> builder.append("\\n");
            case '\r' -> builder.append("\\r");
            case '\t' -> builder.append("\\t");
            default -> {
                if (current < 0x20) {
                    builder.append(String.format("\\u%04x", (int) current));
                } else {
                    builder.append(current);
                }
            }
            }
        }
        builder.append('"');
        return builder.toString();
    }

    private boolean isSecretField(String normalizedField) {
        for (String fragment : SECRET_FIELD_FRAGMENTS) {
            if (normalizedField.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private String normalizePath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        try {
            return Path.of(trimmed).normalize().toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return "<invalid-path>:sha256:" + sha256(trimmed.replace('\\', '/'));
        }
    }

    private String normalizeToolName(String value) {
        return normalizeValue(value);
    }

    private String normalizeValue(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

}
