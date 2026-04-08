package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSnapshot;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.view.SessionMessagesPageView;
import me.golemcore.bot.domain.view.SessionSummaryView;
import me.golemcore.bot.domain.view.SessionTraceSummaryView;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

class SessionInspectionServiceTest {

    private SessionPort sessionPort;
    private ActiveSessionPointerService pointerService;
    private TraceSnapshotCompressionService compressionService;
    private SessionInspectionService service;

    @BeforeEach
    void setUp() {
        sessionPort = mock(SessionPort.class);
        pointerService = mock(ActiveSessionPointerService.class);
        compressionService = new TraceSnapshotCompressionService();
        service = new SessionInspectionService(sessionPort, pointerService, compressionService);
    }

    @Test
    void shouldReturnTraceSummaryForExistingSession() {
        TraceSnapshot snapshot = TraceSnapshot.builder()
                .snapshotId("snap-1")
                .role("request")
                .contentType("application/json")
                .encoding("zstd")
                .compressedPayload(
                        compressionService.compress("{\"prompt\":\"hello\"}".getBytes(StandardCharsets.UTF_8)))
                .originalSize(18L)
                .compressedSize(26L)
                .build();
        TraceSpanRecord rootSpan = TraceSpanRecord.builder()
                .spanId("span-root")
                .name("web.request")
                .kind(TraceSpanKind.INGRESS)
                .statusCode(TraceStatusCode.OK)
                .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                .endedAt(Instant.parse("2026-03-20T10:00:01Z"))
                .attributes(Map.of("channel", "web"))
                .snapshots(List.of(snapshot))
                .build();
        AgentSession session = AgentSession.builder()
                .id("s-trace")
                .channelType("web")
                .chatId("chat-1")
                .traces(List.of(TraceRecord.builder()
                        .traceId("trace-1")
                        .rootSpanId("span-root")
                        .traceName("web.message")
                        .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                        .endedAt(Instant.parse("2026-03-20T10:00:01Z"))
                        .spans(List.of(rootSpan))
                        .build()))
                .messages(List.of())
                .build();
        when(sessionPort.get("s-trace")).thenReturn(Optional.of(session));

        SessionTraceSummaryView summary = service.getSessionTraceSummary("s-trace");

        assertNotNull(summary);
        assertEquals("s-trace", summary.getSessionId());
        assertEquals(1, summary.getTraceCount());
        assertEquals(1, summary.getSnapshotCount());
        assertEquals("trace-1", summary.getTraces().get(0).getTraceId());
    }

    @Test
    void shouldDeleteSessionAndRepairMatchingPointers() {
        AgentSession deletedSession = AgentSession.builder()
                .id("web:to-delete")
                .channelType("web")
                .chatId("legacy-chat")
                .metadata(Map.of("conversationKey", "to-delete"))
                .messages(List.of())
                .build();
        AgentSession replacementSession = AgentSession.builder()
                .id("web:replacement")
                .channelType("web")
                .chatId("legacy-replacement")
                .metadata(Map.of("conversationKey", "replacement"))
                .updatedAt(Instant.parse("2026-03-20T10:05:00Z"))
                .messages(List.of())
                .build();
        when(sessionPort.get("web:to-delete")).thenReturn(Optional.of(deletedSession));
        when(pointerService.getPointersSnapshot()).thenReturn(Map.of("web|operator|client-1", "to-delete"));
        when(sessionPort.listByChannelType("web")).thenReturn(List.of(replacementSession));

        service.deleteSession("web:to-delete");

        verify(sessionPort).delete("web:to-delete");
        verify(pointerService).setActiveConversationKey("web|operator|client-1", "replacement");
    }

    @Test
    void shouldListSessionsWithTitlePreviewAndVisibleMessageCount() {
        AgentSession sessionWithText = AgentSession.builder()
                .id("web:conv-1")
                .channelType("web")
                .chatId("legacy-chat")
                .metadata(Map.of(ContextAttributes.CONVERSATION_KEY, "conv-1"))
                .updatedAt(Instant.parse("2026-03-20T10:05:00Z"))
                .messages(List.of(
                        Message.builder()
                                .id("msg-1")
                                .role("user")
                                .content("First question")
                                .timestamp(Instant.parse("2026-03-20T10:00:00Z"))
                                .build(),
                        Message.builder()
                                .id("msg-2")
                                .role("assistant")
                                .content("Most recent reply")
                                .timestamp(Instant.parse("2026-03-20T10:04:00Z"))
                                .build()))
                .build();
        AgentSession sessionWithFallbackTitle = AgentSession.builder()
                .id("web:conv-2")
                .channelType("web")
                .chatId("legacy-chat-2")
                .metadata(Map.of(ContextAttributes.CONVERSATION_KEY, "conv-2"))
                .updatedAt(Instant.parse("2026-03-20T10:00:00Z"))
                .messages(List.of(Message.builder()
                        .id("msg-3")
                        .role("user")
                        .content(" ")
                        .metadata(Map.of("attachments", List.of(Map.of("name", "diagram.png"))))
                        .timestamp(Instant.parse("2026-03-20T09:59:00Z"))
                        .build()))
                .build();
        when(sessionPort.listByChannelType("web")).thenReturn(List.of(sessionWithFallbackTitle, sessionWithText));

        List<SessionSummaryView> summaries = service.listSessions("web");

        assertEquals(2, summaries.size());
        assertEquals("web:conv-1", summaries.get(0).getId());
        assertEquals("First question", summaries.get(0).getTitle());
        assertEquals("Most recent reply", summaries.get(0).getPreview());
        assertEquals(2, summaries.get(0).getMessageCount());
        assertEquals("Session conv-2", summaries.get(1).getTitle());
        assertNull(summaries.get(1).getPreview());
        assertEquals(1, summaries.get(1).getMessageCount());
    }

    @Test
    void shouldPageMessagesWithAttachmentFallbackAndMetadataProjection() {
        Map<String, Object> attachmentOnlyMetadata = new LinkedHashMap<>();
        attachmentOnlyMetadata.put("attachments", List.of(
                Map.of(
                        "type", "image",
                        "name", "diagram.png",
                        "mimeType", "image/png",
                        "internalFilePath", "folder/diagram 1.png",
                        "thumbnailBase64", "thumb-base64"),
                Map.of(),
                "skip"));

        Map<String, Object> assistantMetadata = new LinkedHashMap<>();
        assistantMetadata.put("model", "gpt-5.4");
        assistantMetadata.put("modelTier", "operator");
        assistantMetadata.put("reasoning", "high");
        assistantMetadata.put("clientMessageId", "client-1");
        assistantMetadata.put(ContextAttributes.AUTO_MODE, true);
        assistantMetadata.put(ContextAttributes.AUTO_RUN_ACTIVE_SKILL, "inspection");
        assistantMetadata.put(ContextAttributes.AUTO_RUN_ID, "run-1");
        assistantMetadata.put(ContextAttributes.AUTO_SCHEDULE_ID, "schedule-1");
        assistantMetadata.put(ContextAttributes.AUTO_GOAL_ID, "goal-1");
        assistantMetadata.put(ContextAttributes.AUTO_TASK_ID, "task-1");

        AgentSession session = AgentSession.builder()
                .id("web:conv-1")
                .channelType("web")
                .chatId("legacy-chat")
                .messages(List.of(
                        Message.builder()
                                .id("hidden")
                                .role("user")
                                .content("internal")
                                .metadata(Map.of(ContextAttributes.MESSAGE_INTERNAL, true))
                                .timestamp(Instant.parse("2026-03-20T10:00:00Z"))
                                .build(),
                        Message.builder()
                                .id("m-1")
                                .role("user")
                                .content("   ")
                                .metadata(attachmentOnlyMetadata)
                                .timestamp(Instant.parse("2026-03-20T10:01:00Z"))
                                .build(),
                        Message.builder()
                                .id("m-2")
                                .role("assistant")
                                .content("Rendered trace")
                                .metadata(assistantMetadata)
                                .voiceData(new byte[] { 1 })
                                .toolCalls(
                                        List.of(Message.ToolCall.builder().id("tool-1").name("trace.search").build()))
                                .timestamp(Instant.parse("2026-03-20T10:02:00Z"))
                                .build(),
                        Message.builder()
                                .id("m-3")
                                .role("user")
                                .content("Latest question")
                                .timestamp(Instant.parse("2026-03-20T10:03:00Z"))
                                .build()))
                .build();
        when(sessionPort.get("web:conv-1")).thenReturn(Optional.of(session));

        SessionMessagesPageView latestPage = service.getSessionMessages("web:conv-1", 2, null);
        SessionMessagesPageView firstPage = service.getSessionMessages("web:conv-1", 2, "m-3");

        assertEquals(2, latestPage.getMessages().size());
        assertEquals("m-2", latestPage.getOldestMessageId());
        assertEquals("m-2", latestPage.getMessages().get(0).getId());
        assertEquals("m-3", latestPage.getMessages().get(1).getId());
        assertTrue(latestPage.isHasMore());

        assertEquals(2, firstPage.getMessages().size());
        assertEquals("m-1", firstPage.getOldestMessageId());
        assertEquals("[3 attachments]", firstPage.getMessages().get(0).getContent());
        assertEquals(1, firstPage.getMessages().get(0).getAttachments().size());
        assertEquals("image", firstPage.getMessages().get(0).getAttachments().get(0).getType());
        assertEquals("/api/files/download?path=folder%2Fdiagram%201.png",
                firstPage.getMessages().get(0).getAttachments().get(0).getUrl());
        assertEquals("Rendered trace", firstPage.getMessages().get(1).getContent());
        assertEquals("gpt-5.4", firstPage.getMessages().get(1).getModel());
        assertEquals("operator", firstPage.getMessages().get(1).getModelTier());
        assertEquals("inspection", firstPage.getMessages().get(1).getSkill());
        assertEquals("high", firstPage.getMessages().get(1).getReasoning());
        assertEquals("client-1", firstPage.getMessages().get(1).getClientMessageId());
        assertEquals("run-1", firstPage.getMessages().get(1).getAutoRunId());
        assertEquals("schedule-1", firstPage.getMessages().get(1).getAutoScheduleId());
        assertEquals("goal-1", firstPage.getMessages().get(1).getAutoGoalId());
        assertEquals("task-1", firstPage.getMessages().get(1).getAutoTaskId());
        assertTrue(firstPage.getMessages().get(1).isAutoMode());
        assertTrue(firstPage.getMessages().get(1).isHasToolCalls());
        assertTrue(firstPage.getMessages().get(1).isHasVoice());
    }

    @Test
    void shouldRejectUnknownBeforeMessageId() {
        AgentSession session = AgentSession.builder()
                .id("web:conv-1")
                .channelType("web")
                .messages(List.of(Message.builder()
                        .id("m-1")
                        .role("user")
                        .content("hello")
                        .timestamp(Instant.parse("2026-03-20T10:00:00Z"))
                        .build()))
                .build();
        when(sessionPort.get("web:conv-1")).thenReturn(Optional.of(session));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.getSessionMessages("web:conv-1", 10, "missing"));

        assertEquals(400, error.getStatusCode().value());
        assertEquals("beforeMessageId not found", error.getReason());
    }

    @Test
    void shouldExportSnapshotPayloadWithFallbackContentType() {
        TraceSnapshot snapshot = TraceSnapshot.builder()
                .snapshotId("snap-1")
                .role("response")
                .contentType("%%%")
                .encoding("zstd")
                .compressedPayload(compressionService.compress("payload".getBytes(StandardCharsets.UTF_8)))
                .originalSize(7L)
                .compressedSize(15L)
                .build();
        AgentSession session = AgentSession.builder()
                .id("web:conv-1")
                .channelType("web")
                .traces(List.of(TraceRecord.builder()
                        .traceId("trace-1")
                        .rootSpanId("span-root")
                        .spans(List.of(TraceSpanRecord.builder()
                                .spanId("span-root")
                                .snapshots(List.of(snapshot))
                                .build()))
                        .build()))
                .build();
        when(sessionPort.get("web:conv-1")).thenReturn(Optional.of(session));

        SessionInspectionService.SnapshotPayloadExport export = service.exportSessionTraceSnapshotPayload("web:conv-1",
                "snap-1");

        assertEquals("payload", export.payloadText());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, export.contentType());
        assertEquals(".txt", export.fileExtension());
    }

    @Test
    void shouldDeleteTelegramSessionAndRepairMatchingPointers() {
        AgentSession deletedSession = AgentSession.builder()
                .id("telegram:conv-old")
                .channelType("telegram")
                .chatId("legacy-chat")
                .metadata(Map.of(
                        ContextAttributes.CONVERSATION_KEY, "conv-old",
                        ContextAttributes.TRANSPORT_CHAT_ID, "555"))
                .messages(List.of())
                .build();
        AgentSession replacementSession = AgentSession.builder()
                .id("telegram:conv-new")
                .channelType("telegram")
                .chatId("legacy-replacement")
                .metadata(Map.of(
                        ContextAttributes.CONVERSATION_KEY, "conv-new",
                        ContextAttributes.TRANSPORT_CHAT_ID, "555"))
                .updatedAt(Instant.parse("2026-03-20T10:05:00Z"))
                .messages(List.of())
                .build();
        when(sessionPort.get("telegram:conv-old")).thenReturn(Optional.of(deletedSession));
        when(pointerService.getPointersSnapshot()).thenReturn(Map.of("telegram|555", "conv-old"));
        when(sessionPort.listByChannelTypeAndTransportChatId("telegram", "555"))
                .thenReturn(List.of(replacementSession));

        service.deleteSession("telegram:conv-old");

        verify(sessionPort).delete("telegram:conv-old");
        verify(pointerService).setActiveConversationKey("telegram|555", "conv-new");
    }
}
