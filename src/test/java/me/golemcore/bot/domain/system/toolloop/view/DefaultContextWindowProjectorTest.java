package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.ContextTokenEstimator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultContextWindowProjectorTest {

    private final DefaultContextWindowProjector projector = new DefaultContextWindowProjector(
            new ContextTokenEstimator(), new ContextGarbagePolicy());

    @Test
    void shouldCompressOldNoisyToolResultWithoutMutatingRawHistory() {
        Message oldUser = user("old request");
        Message assistantWithToolCall = assistantToolCall("call-1", "browser");
        Message rawToolResult = toolResult("call-1", "browser", largeHtml());
        Message oldAssistant = assistant("done");
        Message latestUser = user("current request");
        List<Message> rawMessages = new ArrayList<>(List.of(
                oldUser, assistantWithToolCall, rawToolResult, oldAssistant,
                user("later unrelated 1"), assistant("later unrelated response 1"),
                user("later unrelated 2"), assistant("later unrelated response 2"),
                latestUser));

        AgentContext context = AgentContext.builder()
                .messages(rawMessages)
                .systemPrompt("system")
                .build();

        ConversationView projected = projector.project(context, ConversationView.ofMessages(rawMessages),
                new ContextBudget(8_000, 2_000, 4_000, 800));

        assertNotSame(rawMessages, projected.messages());
        assertEquals("tool", rawMessages.get(2).getRole(), "raw session history must stay unchanged");
        assertEquals("assistant", projected.messages().get(1).getRole());
        assertFalse(projected.messages().stream().anyMatch(message -> "tool".equals(message.getRole())));
        assertTrue(projected.messages().get(1).getContent().contains("Previous tool interaction summarized"));
        assertTrue(projected.messages().stream().anyMatch(message -> "current request".equals(message.getContent())));
        assertFalse(projected.diagnostics().isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> report = context.getAttribute(ContextAttributes.CONTEXT_HYGIENE_REPORT);
        assertEquals(1, report.get("compressed"));
    }

    @Test
    void shouldDropOldLowPriorityItemsWhenConversationBudgetIsExceeded() {
        List<Message> rawMessages = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            rawMessages.add(user("old user " + index + " " + repeated("noise", 500)));
            rawMessages.add(assistant("old assistant " + index + " " + repeated("noise", 500)));
        }
        rawMessages.add(user("latest must stay"));
        rawMessages.add(assistantToolCall("open-call", "shell"));

        AgentContext context = AgentContext.builder()
                .messages(rawMessages)
                .systemPrompt("system")
                .build();

        ConversationView projected = projector.project(context, ConversationView.ofMessages(rawMessages),
                new ContextBudget(1_200, 200, 240, 100));

        assertTrue(projected.messages().stream().anyMatch(message -> "latest must stay".equals(message.getContent())));
        assertTrue(projected.messages().stream().anyMatch(Message::hasToolCalls),
                "unresolved current tool calls must stay pinned");
        assertFalse(projected.messages().stream()
                .anyMatch(message -> message.getContent() != null && message.getContent().contains("old user 0")));
        assertTrue(projected.diagnostics().stream().anyMatch(line -> line.contains("BUDGET_EXCEEDED")));
    }

    private Message user(String content) {
        return Message.builder()
                .role("user")
                .content(content)
                .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    private Message assistant(String content) {
        return Message.builder()
                .role("assistant")
                .content(content)
                .timestamp(Instant.parse("2026-01-01T00:00:01Z"))
                .build();
    }

    private Message assistantToolCall(String id, String name) {
        return Message.builder()
                .role("assistant")
                .content("calling " + name)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id(id)
                        .name(name)
                        .arguments(Map.of("query", "value"))
                        .build()))
                .timestamp(Instant.parse("2026-01-01T00:00:01Z"))
                .build();
    }

    private Message toolResult(String id, String name, String content) {
        return Message.builder()
                .role("tool")
                .toolCallId(id)
                .toolName(name)
                .content(content)
                .timestamp(Instant.parse("2026-01-01T00:00:02Z"))
                .build();
    }

    private String largeHtml() {
        return "<html><body>" + repeated("raw tool blob", 3_000) + "</body></html>";
    }

    private String repeated(String value, int targetChars) {
        StringBuilder builder = new StringBuilder(targetChars);
        while (builder.length() < targetChars) {
            builder.append(value).append(' ');
        }
        return builder.toString();
    }
}
