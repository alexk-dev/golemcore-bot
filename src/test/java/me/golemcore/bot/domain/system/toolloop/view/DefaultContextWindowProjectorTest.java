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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultContextWindowProjectorTest {

    private final DefaultContextWindowProjector projector = new DefaultContextWindowProjector(
            new ContextTokenEstimator(), new ContextGarbagePolicy());

    @Test
    void shouldReturnBaseViewForEmptyUnlimitedProjection() {
        AgentContext context = AgentContext.builder()
                .systemPrompt(null)
                .build();

        ConversationView projected = projector.project(context, null, null);

        assertTrue(projected.messages().isEmpty());
        assertTrue(projected.diagnostics().isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> report = context.getAttribute(ContextAttributes.CONTEXT_HYGIENE_REPORT);
        assertEquals(0, report.get("rawTokens"));
        assertEquals(0, report.get("projectedTokens"));
    }

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
    void shouldSummarizeOldOrphanToolResultAndLargeAssistantMessage() {
        String originalToolBlob = repeated("{\"rows\":[1,2,3]}", 3_000);
        Message orphanToolResult = toolResult("orphan-call", "rag", originalToolBlob);
        Message largeAssistant = assistant(repeated("very long assistant note", 5_000));
        List<Message> rawMessages = new ArrayList<>(List.of(
                user("old request"), orphanToolResult, largeAssistant,
                user("later 1"), assistant("later response 1"),
                user("later 2"), assistant("later response 2"),
                user("latest request")));

        AgentContext context = AgentContext.builder()
                .messages(rawMessages)
                .systemPrompt("system")
                .build();

        ConversationView projected = projector.project(context, ConversationView.ofMessages(rawMessages),
                new ContextBudget(8_000, 1_000, 4_000, 500));

        assertTrue(projected.messages().stream()
                .anyMatch(message -> message.getContent() != null
                        && message.getContent().contains("Previous tool result summarized")));
        assertTrue(projected.messages().stream()
                .anyMatch(message -> message.getContent() != null
                        && message.getContent().contains("...[truncated]")));
        assertFalse(projected.messages().stream()
                .anyMatch(message -> originalToolBlob.equals(message.getContent())));
        assertEquals("tool", rawMessages.get(1).getRole(), "raw orphan tool result must remain unchanged");

        @SuppressWarnings("unchecked")
        Map<String, Object> report = context.getAttribute(ContextAttributes.CONTEXT_HYGIENE_REPORT);
        assertEquals(2, report.get("compressed"));
    }

    @Test
    void shouldKeepArtifactReferencesWhenSummarizingToolResults() {
        Message oldToolResult = Message.builder()
                .role("tool")
                .toolCallId("call-artifact")
                .toolName("browser")
                .content(largeHtml())
                .metadata(Map.of("toolAttachments", List.of(Map.of(
                        "name", "page.html",
                        "internalFilePath", "artifacts/sess/browser/page.html",
                        "url", "/api/tool-artifacts/page.html"))))
                .timestamp(Instant.parse("2026-01-01T00:00:02Z"))
                .build();
        List<Message> rawMessages = new ArrayList<>(List.of(
                user("old request"), oldToolResult,
                user("later 1"), assistant("later response 1"),
                user("later 2"), assistant("later response 2"),
                user("latest request")));

        AgentContext context = AgentContext.builder()
                .messages(rawMessages)
                .systemPrompt("system")
                .memoryContext("memory facts")
                .build();

        ConversationView projected = projector.project(context, ConversationView.ofMessages(rawMessages),
                new ContextBudget(8_000, 1_000, 4_000, 500));

        assertTrue(projected.messages().stream()
                .anyMatch(message -> message.getContent() != null
                        && message.getContent().contains("Artifact: page.html")
                        && message.getContent().contains("artifacts/sess/browser/page.html")));

        @SuppressWarnings("unchecked")
        Map<String, Object> report = context.getAttribute(ContextAttributes.CONTEXT_HYGIENE_REPORT);
        assertTrue((Integer) report.get("memoryTokens") > 0);
    }

    @Test
    void shouldSummarizeOldNoisyMultiToolInteraction() {
        Message assistantWithTwoToolCalls = assistantToolCalls(List.of(
                Message.ToolCall.builder()
                        .id("call-1")
                        .name("browser")
                        .arguments(Map.of("query", "one"))
                        .build(),
                Message.ToolCall.builder()
                        .id("call-2")
                        .name("browser")
                        .arguments(Map.of("query", "two"))
                        .build()));
        List<Message> rawMessages = new ArrayList<>(List.of(
                user("old request"), assistantWithTwoToolCalls,
                toolResult("call-1", "browser", largeHtml()),
                toolResult("call-2", "browser", repeated("[1,2,3]", 3_000)),
                user("later 1"), assistant("later response 1"),
                user("later 2"), assistant("later response 2"),
                user("latest request")));

        AgentContext context = AgentContext.builder()
                .messages(rawMessages)
                .systemPrompt("system")
                .build();

        ConversationView projected = projector.project(context, ConversationView.ofMessages(rawMessages),
                new ContextBudget(8_000, 1_000, 4_000, 500));

        assertTrue(projected.messages().stream()
                .anyMatch(message -> message.getContent() != null
                        && message.getContent().contains("Previous tool interaction summarized")
                        && message.getContent().contains("[1,2,3]")));
        assertFalse(projected.messages().stream().anyMatch(Message::isToolMessage));
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

    @Test
    void shouldKeepUnchangedViewInstanceWhenProjectionDoesNotChangeMessages() {
        List<Message> rawMessages = List.of(user("latest"));
        AgentContext context = AgentContext.builder()
                .messages(rawMessages)
                .systemPrompt("system")
                .build();
        ConversationView rawView = ConversationView.ofMessages(rawMessages);

        ConversationView projected = projector.project(context, rawView,
                new ContextBudget(8_000, 1_000, 4_000, 500));

        assertSame(rawView, projected);
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

    private Message assistantToolCalls(List<Message.ToolCall> toolCalls) {
        return Message.builder()
                .role("assistant")
                .content("calling multiple tools")
                .toolCalls(toolCalls)
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
