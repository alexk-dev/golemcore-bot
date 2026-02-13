package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal tool masking strategy used when switching models/providers: flattens
 * tool calls and tool results into plain assistant text so the target model
 * does not receive provider-specific tool call fields.
 */
public class FlatteningToolMessageMasker implements ToolMessageMasker {

    @Override
    public MaskingResult maskToolMessages(List<Message> rawMessages) {
        List<Message> result = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        if (rawMessages == null || rawMessages.isEmpty()) {
            return new MaskingResult(List.of(), List.of());
        }

        boolean flattenedAny = false;

        for (Message message : rawMessages) {
            if (message == null) {
                continue;
            }

            if (message.hasToolCalls()) {
                flattenedAny = true;
                diagnostics.add("flatten: assistant tool_calls -> assistant text (tool calls masked)");

                String text = message.getContent() != null ? message.getContent() : "";
                StringBuilder sb = new StringBuilder(text);
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("[Tool calls were made and are masked due to model/provider switch]");

                result.add(Message.builder().role("assistant").content(sb.toString()).build());
                continue;
            }

            if (message.isToolMessage()) {
                flattenedAny = true;
                diagnostics.add("flatten: tool message -> assistant text (tool result masked)");

                String toolName = message.getToolName() != null ? message.getToolName() : "tool";
                String toolContent = message.getContent() != null ? message.getContent() : "";
                String text = "[Tool result: " + toolName + "]\n" + toolContent;
                result.add(Message.builder().role("assistant").content(text).build());
                continue;
            }

            result.add(message);
        }

        if (!flattenedAny) {
            diagnostics.add("no-op: no tool messages found");
        }

        return new MaskingResult(result, diagnostics);
    }
}
