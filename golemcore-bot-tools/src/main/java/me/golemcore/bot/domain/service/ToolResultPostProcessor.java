package me.golemcore.bot.domain.service;

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.port.outbound.ToolRuntimeSettingsPort;

@Slf4j
public class ToolResultPostProcessor {

    private final ToolRuntimeSettingsPort settingsPort;

    public ToolResultPostProcessor(ToolRuntimeSettingsPort settingsPort) {
        this.settingsPort = settingsPort;
    }

    public String buildToolMessageContent(ToolResult result) {
        if (result == null) {
            return null;
        }
        if (result.isSuccess()) {
            return result.getOutput();
        }
        if (result.getOutput() != null && !result.getOutput().isBlank()) {
            return result.getOutput();
        }
        return "Error: " + result.getError();
    }

    public String truncateToolResult(String content, String toolName) {
        if (content == null) {
            return null;
        }
        int maxChars = settingsPort.toolExecution().maxToolResultChars();
        if (maxChars <= 0 || content.length() <= maxChars) {
            return content;
        }

        String suffix = "\n\n[OUTPUT TRUNCATED: " + content.length() + " chars total, showing first " + maxChars
                + " chars. The full result is too large for the context window."
                + " Try a more specific query, use filtering/pagination, or process the data in smaller chunks.]";
        int cutPoint = Math.max(0, maxChars - suffix.length());
        log.warn("[Tools] Truncating '{}' result: {} chars -> ~{} chars", toolName, content.length(),
                cutPoint + suffix.length());
        return content.substring(0, cutPoint) + suffix;
    }
}
