package me.golemcore.bot.domain.tools.execution;

import java.util.Base64;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ToolResult;

@Slf4j
public class ToolAttachmentExtractor {

    private static final int MAX_BASE64_LENGTH = 67_000_000;

    public Attachment extract(ToolResult result, String toolName) {
        if (result == null || !result.isSuccess() || !(result.getData() instanceof Map<?, ?> dataMap)) {
            return null;
        }

        Attachment attachment = extractDirectAttachment(dataMap);
        if (attachment == null) {
            attachment = extractScreenshot(dataMap, toolName);
        }
        if (attachment == null) {
            attachment = extractFileBytes(dataMap);
        }
        return attachment;
    }

    private Attachment extractDirectAttachment(Map<?, ?> dataMap) {
        Object attachmentObj = dataMap.get("attachment");
        return attachmentObj instanceof Attachment attachment ? attachment : null;
    }

    private Attachment extractScreenshot(Map<?, ?> dataMap, String toolName) {
        Object screenshotB64 = dataMap.get("screenshot_base64");
        if (!(screenshotB64 instanceof String base64)) {
            return null;
        }
        if (base64.length() > MAX_BASE64_LENGTH) {
            log.warn("[Tools] Base64 data too large ({} chars) from '{}', skipping", base64.length(), toolName);
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return Attachment.builder().type(Attachment.Type.IMAGE).data(bytes).filename("screenshot.png")
                    .mimeType("image/png").build();
        } catch (IllegalArgumentException _) {
            log.warn("[Tools] Invalid base64 in screenshot from '{}'", toolName);
            return null;
        }
    }

    private Attachment extractFileBytes(Map<?, ?> dataMap) {
        Object fileBytes = dataMap.get("file_bytes");
        if (!(fileBytes instanceof byte[] bytes)) {
            return null;
        }
        String filename = dataMap.containsKey("filename") ? dataMap.get("filename").toString() : "file";
        String mimeType = dataMap.containsKey("mime_type") ? dataMap.get("mime_type").toString()
                : "application/octet-stream";
        Attachment.Type type = mimeType.startsWith("image/") ? Attachment.Type.IMAGE : Attachment.Type.DOCUMENT;
        return Attachment.builder().type(type).data(bytes).filename(filename).mimeType(mimeType).build();
    }
}
