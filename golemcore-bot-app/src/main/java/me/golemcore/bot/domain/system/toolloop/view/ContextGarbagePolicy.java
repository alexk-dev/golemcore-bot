package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.Message;

import java.util.regex.Pattern;

/**
 * Classifies noisy conversation artifacts for request-time projection.
 */
public class ContextGarbagePolicy {

    private static final int LARGE_TOOL_RESULT_CHARS = 2_000;
    private static final Pattern HTML_PATTERN = Pattern.compile("(?is)<html\\b|<!doctype\\s+html|<body\\b");
    private static final Pattern BASE64_PATTERN = Pattern.compile("^[A-Za-z0-9+/\\r\\n=]{800,}$");

    public GarbageReason reasonFor(Message message) {
        if (message == null) {
            return GarbageReason.BUDGET_EXCEEDED;
        }
        String content = message.getContent();
        if (content == null || content.isBlank()) {
            return GarbageReason.BUDGET_EXCEEDED;
        }
        if (looksLikeBase64(content)) {
            return GarbageReason.BASE64_OR_BINARY;
        }
        if (looksLikeHtml(content) || looksLikeLargeJson(content)) {
            return GarbageReason.LARGE_JSON_OR_HTML;
        }
        if (message.isToolMessage() && content.length() > LARGE_TOOL_RESULT_CHARS) {
            return GarbageReason.RAW_TOOL_BLOB;
        }
        return GarbageReason.BUDGET_EXCEEDED;
    }

    public boolean isNoisyToolResult(Message message) {
        if (message == null || !message.isToolMessage()) {
            return false;
        }
        GarbageReason reason = reasonFor(message);
        return reason == GarbageReason.RAW_TOOL_BLOB
                || reason == GarbageReason.LARGE_JSON_OR_HTML
                || reason == GarbageReason.BASE64_OR_BINARY;
    }

    private boolean looksLikeHtml(String content) {
        return HTML_PATTERN.matcher(content).find();
    }

    private boolean looksLikeLargeJson(String content) {
        String trimmed = content.stripLeading();
        return content.length() > LARGE_TOOL_RESULT_CHARS
                && (trimmed.startsWith("{") || trimmed.startsWith("["));
    }

    private boolean looksLikeBase64(String content) {
        String normalized = content.replace("\n", "").replace("\r", "");
        if (normalized.length() < 800 || normalized.length() % 4 != 0) {
            return false;
        }
        return BASE64_PATTERN.matcher(normalized).matches();
    }
}
