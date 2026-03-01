package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.CompactionDetails;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionDetailsExtractorTest {

    private CompactionDetailsExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new CompactionDetailsExtractor();
    }

    @Test
    void shouldExtractFilesystemAndToolStatistics() {
        List<Message.ToolCall> toolCalls = List.of(
                toolCall("filesystem", Map.of("operation", "read_file", "path", "src/A.java")),
                toolCall("filesystem", Map.of("operation", "write_file", "path", "src/B.java", "content", "line1\nline2")),
                toolCall("filesystem", Map.of("operation", "delete", "path", "src/C.java")),
                toolCall("filesystem", Map.of("operation", "create_directory", "path", "src/new")),
                toolCall("shell", Map.of("command", "echo ok")));

        Message assistant = Message.builder()
                .role("assistant")
                .content("processing")
                .toolName("browser")
                .toolCalls(toolCalls)
                .build();

        CompactionDetails details = extractor.extract(
                CompactionReason.MANUAL_COMMAND,
                List.of(assistant),
                7,
                3,
                true,
                120,
                true,
                false,
                42,
                50);

        assertEquals(1, details.schemaVersion());
        assertEquals(CompactionReason.MANUAL_COMMAND, details.reason());
        assertEquals(7, details.summarizedCount());
        assertEquals(3, details.keptCount());
        assertTrue(details.usedLlmSummary());
        assertEquals(120, details.summaryLength());
        assertEquals(3, details.toolCount());
        assertEquals(1, details.readFilesCount());
        assertEquals(3, details.modifiedFilesCount());
        assertEquals(42, details.durationMs());
        assertTrue(details.splitTurnDetected());
        assertFalse(details.fallbackUsed());

        assertTrue(details.toolNames().contains("browser"));
        assertTrue(details.toolNames().contains("filesystem"));
        assertTrue(details.toolNames().contains("shell"));

        assertEquals(List.of("src/A.java"), details.readFiles());
        assertTrue(details.modifiedFiles().contains("src/B.java"));
        assertTrue(details.modifiedFiles().contains("src/C.java"));
        assertTrue(details.modifiedFiles().contains("src/new"));

        assertEquals(3, details.fileChanges().size());
        CompactionDetails.FileChangeStat writeStat = find(details.fileChanges(), "src/B.java");
        assertNotNull(writeStat);
        assertEquals(2, writeStat.addedLines());
        assertEquals(0, writeStat.removedLines());
        assertFalse(writeStat.deleted());

        CompactionDetails.FileChangeStat deleteStat = find(details.fileChanges(), "src/C.java");
        assertNotNull(deleteStat);
        assertEquals(0, deleteStat.addedLines());
        assertEquals(1, deleteStat.removedLines());
        assertTrue(deleteStat.deleted());

        CompactionDetails.FileChangeStat createDirStat = find(details.fileChanges(), "src/new");
        assertNotNull(createDirStat);
        assertEquals(0, createDirStat.addedLines());
        assertEquals(0, createDirStat.removedLines());
        assertFalse(createDirStat.deleted());
    }

    @Test
    void shouldRespectMaxItemsPerCategoryAndStopCollectingEarly() {
        Message firstMessage = Message.builder()
                .role("assistant")
                .toolName("filesystem")
                .toolCalls(List.of(
                        toolCall("filesystem", Map.of("operation", "read_file", "path", "a.txt")),
                        toolCall("filesystem", Map.of("operation", "write_file", "path", "b.txt", "content", "x"))))
                .build();

        Message secondMessage = Message.builder()
                .role("assistant")
                .toolName("shell")
                .toolCalls(List.of(toolCall("filesystem", Map.of("operation", "read_file", "path", "c.txt"))))
                .build();

        CompactionDetails details = extractor.extract(
                CompactionReason.AUTO_THRESHOLD,
                List.of(firstMessage, secondMessage),
                2,
                2,
                false,
                0,
                false,
                true,
                5,
                1);

        assertEquals(1, details.toolNames().size());
        assertEquals(1, details.readFiles().size());
        assertEquals(1, details.modifiedFiles().size());
        assertEquals(1, details.fileChanges().size());
        assertTrue(details.fallbackUsed());
    }

    @Test
    void shouldHandleNullMessagesAndInvalidFilesystemArguments() {
        List<Message.ToolCall> toolCalls = new ArrayList<>();
        toolCalls.add(null);
        toolCalls.add(toolCall("filesystem", null));
        toolCalls.add(toolCall("filesystem", Map.of()));

        Map<String, Object> invalidOperationArgs = new LinkedHashMap<>();
        invalidOperationArgs.put("operation", 123);
        invalidOperationArgs.put("path", "ignored.txt");
        toolCalls.add(toolCall("filesystem", invalidOperationArgs));

        Map<String, Object> invalidPathArgs = new LinkedHashMap<>();
        invalidPathArgs.put("operation", "read_file");
        invalidPathArgs.put("path", 999);
        toolCalls.add(toolCall("filesystem", invalidPathArgs));

        toolCalls.add(toolCall("filesystem", Map.of("operation", "write_file", "path", "w.txt", "content", "")));
        toolCalls.add(toolCall("filesystem", Map.of("operation", "read_file", "path", "r.txt")));
        toolCalls.add(toolCall("shell", Map.of("command", "echo")));

        Message assistant = Message.builder()
                .role("assistant")
                .toolCalls(toolCalls)
                .build();

        List<Message> messages = new ArrayList<>();
        messages.add(null);
        messages.add(assistant);

        CompactionDetails details = extractor.extract(
                CompactionReason.CONTEXT_OVERFLOW_RECOVERY,
                messages,
                3,
                1,
                false,
                0,
                false,
                true,
                11,
                0);

        assertTrue(details.toolNames().contains("filesystem"));
        assertTrue(details.toolNames().contains("shell"));
        assertEquals(List.of("r.txt"), details.readFiles());
        assertEquals(List.of("w.txt"), details.modifiedFiles());

        CompactionDetails.FileChangeStat stat = find(details.fileChanges(), "w.txt");
        assertNotNull(stat);
        assertEquals(0, stat.addedLines());
        assertEquals(0, stat.removedLines());
        assertFalse(stat.deleted());
    }

    private Message.ToolCall toolCall(String name, Map<String, Object> args) {
        return Message.ToolCall.builder()
                .id(name + "-id")
                .name(name)
                .arguments(args)
                .build();
    }

    private CompactionDetails.FileChangeStat find(List<CompactionDetails.FileChangeStat> stats, String path) {
        for (CompactionDetails.FileChangeStat stat : stats) {
            if (path.equals(stat.path())) {
                return stat;
            }
        }
        return null;
    }
}
