package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.context.compaction.ContextTokenEstimator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinnedContextCompressorTest {

    private final ContextTokenEstimator tokenEstimator = new ContextTokenEstimator();
    private final PinnedContextCompressor compressor = new PinnedContextCompressor(
            tokenEstimator, new ContextGarbagePolicy());

    @Test
    void shouldDropNonCriticalPinnedItemsBeforeProtocolCriticalContext() {
        ProjectedItem oldPinnedAssistant = item(0, ContextItemKind.ASSISTANT, true,
                assistant(repeated("old assistant noise", 4_000)));
        ProjectedItem latestUser = item(1, ContextItemKind.USER, true, user("latest request"));
        ProjectedItem unresolvedToolCall = item(2, ContextItemKind.TOOL_INTERACTION, true,
                assistantToolCall("call-open", "browser"));
        int protocolCriticalLimit = tokenEstimator.estimateMessages(
                List.of(latestUser.messages().get(0), unresolvedToolCall.messages().get(0))) + 8;
        ContextHygieneReport report = new ContextHygieneReport();

        List<ProjectedItem> compressed = compressor.compressWithinBudget(
                List.of(oldPinnedAssistant, latestUser, unresolvedToolCall), protocolCriticalLimit, report);

        assertEquals(List.of(latestUser.item(), unresolvedToolCall.item()),
                compressed.stream().map(ProjectedItem::item).toList());
        assertTrue(report.summary().contains("BUDGET_EXCEEDED"));
    }

    @Test
    void shouldHardCompressProtocolCriticalToolResults() {
        Message latestUser = user("inspect page");
        Message assistantCall = assistantToolCall("call-current", "browser");
        Message hugeToolResult = toolResult("call-current", "browser", largeHtml());
        ProjectedItem userItem = item(0, ContextItemKind.USER, true, latestUser);
        ProjectedItem toolItem = item(1, ContextItemKind.TOOL_INTERACTION, true, assistantCall, hugeToolResult);
        int hardLimit = 120;
        ContextHygieneReport report = new ContextHygieneReport();

        List<ProjectedItem> compressed = compressor.compressWithinBudget(List.of(userItem, toolItem),
                hardLimit, report);

        List<Message> messages = compressed.stream().flatMap(item -> item.messages().stream()).toList();
        assertTrue(tokenEstimator.estimateMessages(messages) <= hardLimit);
        assertTrue(messages.stream().anyMatch(Message::hasToolCalls));
        assertTrue(messages.stream().anyMatch(message -> message.isToolMessage()
                && "call-current".equals(message.getToolCallId())
                && message.getContent().contains("Tool result summarized")));
        assertTrue(report.summary().contains("compressed="));
    }

    @Test
    void shouldCompressLatestUserWhenItIsTheOnlyMessageThatCanFit() {
        ProjectedItem oldAssistant = item(0, ContextItemKind.ASSISTANT, true,
                assistant(repeated("old assistant noise", 4_000)));
        ProjectedItem latestUser = item(1, ContextItemKind.USER, true,
                user(repeated("latest request details", 4_000)));
        ContextHygieneReport report = new ContextHygieneReport();

        List<ProjectedItem> compressed = compressor.compressWithinBudget(List.of(oldAssistant, latestUser),
                48, report);

        assertEquals(1, compressed.size());
        assertEquals(ContextItemKind.USER, compressed.get(0).item().kind());
        assertTrue(tokenEstimator.estimateMessages(compressed.get(0).messages()) <= 48);
        assertTrue(compressed.get(0).messages().get(0).getContent().contains("...[truncated]"));
        assertFalse(report.summary().contains("RAW_TOOL_BLOB"));
    }

    private ProjectedItem item(int ordinal, ContextItemKind kind, boolean pinned, Message... messages) {
        List<Message> messageList = List.of(messages);
        return new ProjectedItem(new ContextItem(ordinal, kind, messageList), messageList, pinned, 100,
                tokenEstimator.estimateMessages(messageList));
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
