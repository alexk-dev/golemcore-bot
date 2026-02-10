package me.golemcore.bot.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageFlattenTest {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_TOOL = "tool";
    private static final Instant NOW = Instant.parse("2026-02-09T12:00:00Z");
    private static final String TC1 = "tc1";
    private static final String TC2 = "tc2";
    private static final String TOOL_SHELL = "shell";
    private static final String TOOL_FILESYSTEM = "filesystem";
    private static final String ARG_COMMAND = "command";
    private static final String CONTENT_FILE1 = "file1.txt";
    private static final String CONTAINS_TOOL_SHELL = "[Tool: shell";

    @Test
    void shouldPassThroughMessagesWithoutToolCalls() {
        List<Message> messages = List.of(
                Message.builder().id("m1").role(ROLE_USER).content("Hello").timestamp(NOW).build(),
                Message.builder().id("m2").role(ROLE_ASSISTANT).content("Hi there!").timestamp(NOW).build());

        List<Message> result = Message.flattenToolMessages(new ArrayList<>(messages));

        assertEquals(2, result.size());
        assertSame(messages.get(0), result.get(0));
        assertSame(messages.get(1), result.get(1));
    }

    @Test
    void shouldFlattenSingleToolCallRound() {
        List<Message> messages = new ArrayList<>(List.of(
                Message.builder().id("m1").role(ROLE_USER).content("List files").timestamp(NOW).build(),
                Message.builder().id("m2").role(ROLE_ASSISTANT)
                        .toolCalls(List.of(Message.ToolCall.builder()
                                .id(TC1).name(TOOL_SHELL)
                                .arguments(Map.of(ARG_COMMAND, "ls -la"))
                                .build()))
                        .timestamp(NOW).build(),
                Message.builder().id("m3").role(ROLE_TOOL)
                        .toolCallId(TC1).toolName(TOOL_SHELL)
                        .content("file1.txt\nfile2.txt")
                        .timestamp(NOW).build()));

        List<Message> result = Message.flattenToolMessages(messages);

        assertEquals(2, result.size());
        assertEquals(ROLE_USER, result.get(0).getRole());
        assertEquals(ROLE_ASSISTANT, result.get(1).getRole());
        assertTrue(result.get(1).getContent().contains(CONTAINS_TOOL_SHELL));
        assertTrue(result.get(1).getContent().contains(CONTENT_FILE1));
        assertNull(result.get(1).getToolCalls());
    }

    @Test
    void shouldFlattenMultipleToolCallsInOneRound() {
        List<Message> messages = new ArrayList<>(List.of(
                Message.builder().id("m1").role(ROLE_USER).content("Do stuff").timestamp(NOW).build(),
                Message.builder().id("m2").role(ROLE_ASSISTANT)
                        .toolCalls(List.of(
                                Message.ToolCall.builder()
                                        .id(TC1).name(TOOL_SHELL)
                                        .arguments(Map.of(ARG_COMMAND, "ls"))
                                        .build(),
                                Message.ToolCall.builder()
                                        .id(TC2).name(TOOL_FILESYSTEM)
                                        .arguments(Map.of("operation", "read", "path", "config.yml"))
                                        .build()))
                        .timestamp(NOW).build(),
                Message.builder().id("m3").role(ROLE_TOOL)
                        .toolCallId(TC1).toolName(TOOL_SHELL)
                        .content(CONTENT_FILE1)
                        .timestamp(NOW).build(),
                Message.builder().id("m4").role(ROLE_TOOL)
                        .toolCallId(TC2).toolName(TOOL_FILESYSTEM)
                        .content("key: value")
                        .timestamp(NOW).build()));

        List<Message> result = Message.flattenToolMessages(messages);

        assertEquals(2, result.size());
        String content = result.get(1).getContent();
        assertTrue(content.contains(CONTAINS_TOOL_SHELL));
        assertTrue(content.contains(CONTENT_FILE1));
        assertTrue(content.contains("[Tool: filesystem"));
        assertTrue(content.contains("key: value"));
    }

    @Test
    void shouldFlattenMultipleRounds() {
        List<Message> messages = new ArrayList<>(List.of(
                Message.builder().id("m1").role(ROLE_USER).content("Hi").timestamp(NOW).build(),
                Message.builder().id("m2").role(ROLE_ASSISTANT)
                        .toolCalls(List.of(Message.ToolCall.builder()
                                .id(TC1).name(TOOL_SHELL)
                                .arguments(Map.of(ARG_COMMAND, "date"))
                                .build()))
                        .timestamp(NOW).build(),
                Message.builder().id("m3").role(ROLE_TOOL)
                        .toolCallId(TC1).toolName(TOOL_SHELL)
                        .content("2026-02-09")
                        .timestamp(NOW).build(),
                Message.builder().id("m4").role(ROLE_ASSISTANT).content("Today is Feb 9").timestamp(NOW).build(),
                Message.builder().id("m5").role(ROLE_USER).content("Now read file").timestamp(NOW).build(),
                Message.builder().id("m6").role(ROLE_ASSISTANT)
                        .toolCalls(List.of(Message.ToolCall.builder()
                                .id(TC2).name(TOOL_FILESYSTEM)
                                .arguments(Map.of("path", "test.txt"))
                                .build()))
                        .timestamp(NOW).build(),
                Message.builder().id("m7").role(ROLE_TOOL)
                        .toolCallId(TC2).toolName(TOOL_FILESYSTEM)
                        .content("hello world")
                        .timestamp(NOW).build()));

        List<Message> result = Message.flattenToolMessages(messages);

        // m1(user) + m2+m3(flattened) + m4(assistant text) + m5(user) +
        // m6+m7(flattened)
        assertEquals(5, result.size());
        assertEquals(ROLE_USER, result.get(0).getRole());
        assertTrue(result.get(1).getContent().contains(CONTAINS_TOOL_SHELL));
        assertEquals("Today is Feb 9", result.get(2).getContent());
        assertEquals(ROLE_USER, result.get(3).getRole());
        assertTrue(result.get(4).getContent().contains("[Tool: filesystem"));
    }

    @Test
    void shouldPreserveAssistantContentBeforeToolCalls() {
        List<Message> messages = new ArrayList<>(List.of(
                Message.builder().id("m1").role(ROLE_ASSISTANT)
                        .content("Let me check that for you.")
                        .toolCalls(List.of(Message.ToolCall.builder()
                                .id(TC1).name(TOOL_SHELL)
                                .arguments(Map.of(ARG_COMMAND, "whoami"))
                                .build()))
                        .timestamp(NOW).build(),
                Message.builder().id("m2").role(ROLE_TOOL)
                        .toolCallId(TC1).toolName(TOOL_SHELL)
                        .content("alex")
                        .timestamp(NOW).build()));

        List<Message> result = Message.flattenToolMessages(messages);

        assertEquals(1, result.size());
        assertTrue(result.get(0).getContent().startsWith("Let me check that for you."));
        assertTrue(result.get(0).getContent().contains(CONTAINS_TOOL_SHELL));
        assertTrue(result.get(0).getContent().contains("alex"));
    }

    @Test
    void shouldHandleEmptyToolResultContent() {
        List<Message> messages = new ArrayList<>(List.of(
                Message.builder().id("m1").role(ROLE_ASSISTANT)
                        .toolCalls(List.of(Message.ToolCall.builder()
                                .id(TC1).name(TOOL_SHELL)
                                .arguments(Map.of(ARG_COMMAND, "true"))
                                .build()))
                        .timestamp(NOW).build(),
                Message.builder().id("m2").role(ROLE_TOOL)
                        .toolCallId(TC1).toolName(TOOL_SHELL)
                        .content("")
                        .timestamp(NOW).build()));

        List<Message> result = Message.flattenToolMessages(messages);

        assertEquals(1, result.size());
        assertTrue(result.get(0).getContent().contains("[Result: <empty>]"));
    }

    @Test
    void shouldHandleMissingToolResult() {
        List<Message> messages = new ArrayList<>(List.of(
                Message.builder().id("m1").role(ROLE_ASSISTANT)
                        .toolCalls(List.of(Message.ToolCall.builder()
                                .id(TC1).name(TOOL_SHELL)
                                .arguments(Map.of(ARG_COMMAND, "test"))
                                .build()))
                        .timestamp(NOW).build()));
        // No tool result message

        List<Message> result = Message.flattenToolMessages(messages);

        assertEquals(1, result.size());
        assertTrue(result.get(0).getContent().contains("[Result: <no response>]"));
    }

    @Test
    void shouldTruncateLongResults() {
        String longResult = "x".repeat(3000);
        List<Message> messages = new ArrayList<>(List.of(
                Message.builder().id("m1").role(ROLE_ASSISTANT)
                        .toolCalls(List.of(Message.ToolCall.builder()
                                .id(TC1).name(TOOL_SHELL)
                                .arguments(Map.of(ARG_COMMAND, "cat bigfile"))
                                .build()))
                        .timestamp(NOW).build(),
                Message.builder().id("m2").role(ROLE_TOOL)
                        .toolCallId(TC1).toolName(TOOL_SHELL)
                        .content(longResult)
                        .timestamp(NOW).build()));

        List<Message> result = Message.flattenToolMessages(messages);

        assertEquals(1, result.size());
        // Result should be truncated to ~2000 chars + "..."
        assertTrue(result.get(0).getContent().length() < longResult.length());
        assertTrue(result.get(0).getContent().contains("..."));
    }

    @Test
    void shouldHandleOrphanedToolMessage() {
        List<Message> messages = new ArrayList<>(List.of(
                Message.builder().id("m1").role(ROLE_USER).content("Hi").timestamp(NOW).build(),
                Message.builder().id("m2").role(ROLE_TOOL)
                        .toolCallId("orphan-id").toolName(TOOL_SHELL)
                        .content("orphaned result")
                        .timestamp(NOW).build()));

        List<Message> result = Message.flattenToolMessages(messages);

        assertEquals(2, result.size());
        assertEquals(ROLE_USER, result.get(0).getRole());
        // Orphaned tool message should be converted to assistant
        assertEquals(ROLE_ASSISTANT, result.get(1).getRole());
        assertTrue(result.get(1).getContent().contains("[Tool: shell]"));
        assertTrue(result.get(1).getContent().contains("orphaned result"));
    }

    @Test
    void shouldBeIdempotent() {
        List<Message> messages = new ArrayList<>(List.of(
                Message.builder().id("m1").role(ROLE_USER).content("Hello").timestamp(NOW).build(),
                Message.builder().id("m2").role(ROLE_ASSISTANT)
                        .toolCalls(List.of(Message.ToolCall.builder()
                                .id(TC1).name(TOOL_SHELL)
                                .arguments(Map.of(ARG_COMMAND, "ls"))
                                .build()))
                        .timestamp(NOW).build(),
                Message.builder().id("m3").role(ROLE_TOOL)
                        .toolCallId(TC1).toolName(TOOL_SHELL)
                        .content(CONTENT_FILE1)
                        .timestamp(NOW).build()));

        List<Message> first = Message.flattenToolMessages(messages);
        List<Message> second = Message.flattenToolMessages(new ArrayList<>(first));

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).getContent(), second.get(i).getContent());
            assertEquals(first.get(i).getRole(), second.get(i).getRole());
        }
        // After second pass, no tool calls should exist
        assertFalse(second.stream().anyMatch(Message::hasToolCalls));
        assertFalse(second.stream().anyMatch(Message::isToolMessage));
    }

    @Test
    void shouldHandleNullInput() {
        assertNull(Message.flattenToolMessages(null));
    }

    @Test
    void shouldHandleEmptyList() {
        List<Message> result = Message.flattenToolMessages(new ArrayList<>());
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldPreserveMessageMetadata() {
        Map<String, Object> metadata = Map.of("key", "value");
        List<Message> messages = new ArrayList<>(List.of(
                Message.builder().id("m1").role(ROLE_ASSISTANT)
                        .toolCalls(List.of(Message.ToolCall.builder()
                                .id(TC1).name(TOOL_SHELL)
                                .arguments(Map.of(ARG_COMMAND, "ls"))
                                .build()))
                        .timestamp(NOW)
                        .metadata(metadata)
                        .build(),
                Message.builder().id("m2").role(ROLE_TOOL)
                        .toolCallId(TC1).toolName(TOOL_SHELL)
                        .content("ok")
                        .timestamp(NOW).build()));

        List<Message> result = Message.flattenToolMessages(messages);

        assertEquals(1, result.size());
        assertEquals("m1", result.get(0).getId());
        assertEquals(NOW, result.get(0).getTimestamp());
        assertEquals(metadata, result.get(0).getMetadata());
    }
}
