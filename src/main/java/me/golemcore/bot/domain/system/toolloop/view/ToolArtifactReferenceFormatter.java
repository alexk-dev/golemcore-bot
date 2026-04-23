package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.Message;

import java.util.List;
import java.util.Map;

/**
 * Renders durable artifact references from tool-result metadata.
 */
final class ToolArtifactReferenceFormatter {

    private static final String TOOL_ATTACHMENTS_METADATA_KEY = "toolAttachments";

    private ToolArtifactReferenceFormatter() {
    }

    static void appendArtifactRefs(StringBuilder content, Message result) {
        if (content == null || result == null || result.getMetadata() == null) {
            return;
        }
        Object rawAttachments = result.getMetadata().get(TOOL_ATTACHMENTS_METADATA_KEY);
        if (!(rawAttachments instanceof List<?> attachments) || attachments.isEmpty()) {
            return;
        }
        for (Object rawAttachment : attachments) {
            if (!(rawAttachment instanceof Map<?, ?> attachment)) {
                continue;
            }
            String name = stringValue(attachment.get("name"));
            String path = stringValue(attachment.get("internalFilePath"));
            String url = stringValue(attachment.get("url"));
            if (path == null && url == null) {
                continue;
            }
            content.append("\nArtifact: ").append(name != null ? name : "tool-output");
            if (path != null) {
                content.append(" path=").append(path);
            }
            if (url != null) {
                content.append(" url=").append(url);
            }
        }
    }

    private static String stringValue(Object value) {
        if (value instanceof String string && !string.isBlank()) {
            return string;
        }
        return null;
    }
}
