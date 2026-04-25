package me.golemcore.bot.domain.resilience;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import java.util.List;

/**
 * Shared rendering helpers for resilience classifier prompts.
 */
public final class ClassifierPromptSupport {

    private static final String EMPTY_TEXT = "(empty)";
    private static final String NO_TOOLS = "(none)";

    private ClassifierPromptSupport() {
    }

    public static String buildUserPrompt(ClassifierRequest request, int maxUserMessageChars,
            int maxAssistantReplyChars) {
        StringBuilder builder = new StringBuilder();
        builder.append("User message:\n");
        builder.append(renderText(request != null ? request.userMessage() : null, maxUserMessageChars));
        builder.append("\n\nAssistant reply:\n");
        builder.append(renderText(request != null ? request.assistantReply() : null, maxAssistantReplyChars));
        builder.append("\n\nExecuted tools in this turn: ");
        builder.append(renderToolList(request != null ? request.executedToolsInTurn() : null));
        builder.append("\n\nRespond with the JSON verdict only.");
        return builder.toString();
    }

    private static String renderText(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return EMPTY_TEXT;
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "… (truncated)";
    }

    private static String renderToolList(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return NO_TOOLS;
        }
        return String.join(", ", tools);
    }
}
