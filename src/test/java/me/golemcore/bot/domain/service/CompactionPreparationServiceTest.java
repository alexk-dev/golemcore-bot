package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.CompactionPreparation;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionPreparationServiceTest {

    private CompactionPreparationService service;

    @BeforeEach
    void setUp() {
        service = new CompactionPreparationService();
    }

    @Test
    void shouldPrepareWithoutBoundaryPreservationWhenDisabled() {
        List<Message> messages = List.of(
                userMessage("u1"),
                assistantMessage("a1"),
                toolResultMessage("tc-1"),
                assistantMessage("a2"));

        CompactionPreparation preparation = service.prepare(
                "s1",
                messages,
                2,
                CompactionReason.MANUAL_COMMAND,
                false);

        assertEquals("s1", preparation.sessionId());
        assertEquals(CompactionReason.MANUAL_COMMAND, preparation.reason());
        assertEquals(4, preparation.totalMessages());
        assertEquals(2, preparation.keepLastRequested());
        assertEquals(2, preparation.rawCutIndex());
        assertEquals(2, preparation.adjustedCutIndex());
        assertFalse(preparation.splitTurnDetected());
        assertEquals(2, preparation.messagesToCompact().size());
        assertEquals(2, preparation.messagesToKeep().size());
    }

    @Test
    void shouldNormalizeKeepLastToAtLeastOne() {
        List<Message> messages = List.of(
                userMessage("u1"),
                assistantMessage("a1"),
                userMessage("u2"));

        CompactionPreparation preparation = service.prepare(
                "s2",
                messages,
                0,
                CompactionReason.AUTO_THRESHOLD,
                false);

        assertEquals(1, preparation.keepLastRequested());
        assertEquals(2, preparation.rawCutIndex());
        assertEquals(2, preparation.adjustedCutIndex());
        assertEquals(2, preparation.messagesToCompact().size());
        assertEquals(1, preparation.messagesToKeep().size());
    }

    @Test
    void shouldMoveCutIndexToStartWhenFirstKeptToolMessageHasNoOrigin() {
        List<Message> messages = List.of(
                userMessage("u1"),
                toolResultMessage("tc-orphan"));

        CompactionPreparation preparation = service.prepare(
                "s3",
                messages,
                1,
                CompactionReason.AUTO_THRESHOLD,
                true);

        assertEquals(1, preparation.rawCutIndex());
        assertEquals(0, preparation.adjustedCutIndex());
        assertTrue(preparation.splitTurnDetected());
        assertEquals(0, preparation.messagesToCompact().size());
        assertEquals(2, preparation.messagesToKeep().size());
    }

    @Test
    void shouldKeepBoundaryWhenToolResultHasOriginAndPreviousMessageIsNotAssistantToolCall() {
        List<Message> messages = List.of(
                assistantToolCallMessage("tc-1"),
                userMessage("intermediate"),
                toolResultMessage("tc-1"));

        CompactionPreparation preparation = service.prepare(
                "s4",
                messages,
                1,
                CompactionReason.MANUAL_COMMAND,
                true);

        assertEquals(2, preparation.rawCutIndex());
        assertEquals(2, preparation.adjustedCutIndex());
        assertFalse(preparation.splitTurnDetected());
        assertEquals(2, preparation.messagesToCompact().size());
        assertEquals(1, preparation.messagesToKeep().size());
    }

    @Test
    void shouldKeepBoundaryWhenAssistantToolCallAlreadyCompactable() {
        List<Message> messages = List.of(
                userMessage("u1"),
                assistantToolCallMessage("tc-2"),
                toolResultMessage("tc-2"),
                assistantMessage("after"));

        CompactionPreparation preparation = service.prepare(
                "s5",
                messages,
                2,
                CompactionReason.CONTEXT_OVERFLOW_RECOVERY,
                true);

        assertEquals(2, preparation.rawCutIndex());
        assertEquals(2, preparation.adjustedCutIndex());
        assertFalse(preparation.splitTurnDetected());
        assertEquals(2, preparation.messagesToCompact().size());
        assertEquals(2, preparation.messagesToKeep().size());
    }

    @Test
    void shouldMoveCutIndexLeftWhenPreviousAssistantHasDanglingToolCalls() {
        List<Message> messages = List.of(
                userMessage("u1"),
                assistantToolCallMessage("tc-3"),
                assistantMessage("bridge"),
                toolResultMessage("tc-3"),
                assistantMessage("after"));

        CompactionPreparation preparation = service.prepare(
                "s6",
                messages,
                3,
                CompactionReason.CONTEXT_OVERFLOW_RECOVERY,
                true);

        assertEquals(2, preparation.rawCutIndex());
        assertEquals(1, preparation.adjustedCutIndex());
        assertTrue(preparation.splitTurnDetected());
        assertEquals(1, preparation.messagesToCompact().size());
        assertEquals(4, preparation.messagesToKeep().size());
    }

    @Test
    void shouldHandleRawCutAtBeginningWhenKeepLastExceedsMessageCount() {
        List<Message> messages = List.of(
                userMessage("u1"),
                assistantMessage("a1"));

        CompactionPreparation preparation = service.prepare(
                "s7",
                messages,
                10,
                CompactionReason.AUTO_THRESHOLD,
                true);

        assertEquals(0, preparation.rawCutIndex());
        assertEquals(0, preparation.adjustedCutIndex());
        assertFalse(preparation.splitTurnDetected());
        assertEquals(0, preparation.messagesToCompact().size());
        assertEquals(2, preparation.messagesToKeep().size());
    }

    private Message userMessage(String content) {
        return Message.builder()
                .role("user")
                .content(content)
                .build();
    }

    private Message assistantMessage(String content) {
        return Message.builder()
                .role("assistant")
                .content(content)
                .build();
    }

    private Message assistantToolCallMessage(String toolCallId) {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(toolCallId)
                .name("filesystem")
                .build();

        return Message.builder()
                .role("assistant")
                .content("running tool")
                .toolCalls(List.of(toolCall))
                .build();
    }

    private Message toolResultMessage(String toolCallId) {
        return Message.builder()
                .role("tool")
                .toolName("filesystem")
                .toolCallId(toolCallId)
                .content("ok")
                .build();
    }
}
