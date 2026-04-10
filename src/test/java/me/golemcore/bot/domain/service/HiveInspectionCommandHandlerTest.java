package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.golemcore.bot.adapter.outbound.hive.HiveInspectionPayloadMapper;
import me.golemcore.bot.adapter.shared.dto.SessionTraceExportPayload;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveInspectionRequestBody;
import me.golemcore.bot.domain.model.HiveInspectionResponse;
import me.golemcore.bot.domain.view.SessionTraceExportView;
import me.golemcore.bot.domain.view.SessionTraceStorageStatsView;
import me.golemcore.bot.domain.view.SessionSummaryView;
import me.golemcore.bot.port.outbound.HiveEventPublishPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.NoSuchElementException;

class HiveInspectionCommandHandlerTest {

    @Test
    void shouldPublishSessionListInspectionResponse() {
        SessionInspectionService sessionInspectionService = mock(SessionInspectionService.class);
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveInspectionCommandHandler handler = new HiveInspectionCommandHandler(
                sessionInspectionService, publisher, new HiveInspectionPayloadMapper());
        when(sessionInspectionService.listSessions("web")).thenReturn(List.of(SessionSummaryView.builder()
                .id("web:conv-1")
                .channelType("web")
                .chatId("legacy-chat")
                .conversationKey("conv-1")
                .transportChatId("client-1")
                .messageCount(1)
                .state("ACTIVE")
                .createdAt(Instant.parse("2026-03-20T10:00:00Z"))
                .updatedAt(Instant.parse("2026-03-20T10:05:00Z"))
                .title("Session conv-1")
                .preview("hello")
                .active(false)
                .build()));

        handler.handle(HiveControlCommandEnvelope.builder()
                .eventType("inspection.request")
                .requestId("req-1")
                .threadId("thread-1")
                .cardId("card-1")
                .runId("run-1")
                .golemId("golem-1")
                .inspection(HiveInspectionRequestBody.builder()
                        .operation("sessions.list")
                        .channel("web")
                        .build())
                .build());

        ArgumentCaptor<HiveInspectionResponse> responseCaptor = ArgumentCaptor.forClass(HiveInspectionResponse.class);
        verify(publisher).publishInspectionResponse(responseCaptor.capture());
        HiveInspectionResponse response = responseCaptor.getValue();
        assertEquals("req-1", response.requestId());
        assertEquals("sessions.list", response.operation());
        assertTrue(response.success());
        List<?> payload = assertInstanceOf(List.class, response.payload());
        assertEquals(1, payload.size());
        Map<?, ?> session = assertInstanceOf(Map.class, payload.get(0));
        assertEquals("web:conv-1", session.get("id"));
        assertEquals("conv-1", session.get("conversationKey"));
        assertEquals("2026-03-20T10:00:00Z", session.get("createdAt"));
        assertEquals("2026-03-20T10:05:00Z", session.get("updatedAt"));
    }

    @Test
    void shouldPublishTypedErrorResponseWhenInspectionOperationFails() {
        SessionInspectionService sessionInspectionService = mock(SessionInspectionService.class);
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveInspectionCommandHandler handler = new HiveInspectionCommandHandler(
                sessionInspectionService, publisher, new HiveInspectionPayloadMapper());
        when(sessionInspectionService.getSessionTraceSummary("missing"))
                .thenThrow(new NoSuchElementException("Session not found"));

        handler.handle(HiveControlCommandEnvelope.builder()
                .eventType("inspection.request")
                .requestId("req-404")
                .threadId("thread-1")
                .inspection(HiveInspectionRequestBody.builder()
                        .operation("session.trace.summary")
                        .sessionId("missing")
                        .build())
                .build());

        ArgumentCaptor<HiveInspectionResponse> responseCaptor = ArgumentCaptor.forClass(HiveInspectionResponse.class);
        verify(publisher).publishInspectionResponse(responseCaptor.capture());
        HiveInspectionResponse response = responseCaptor.getValue();
        assertEquals("req-404", response.requestId());
        assertEquals("session.trace.summary", response.operation());
        assertFalse(response.success());
        assertEquals("NOT_FOUND", response.errorCode());
        assertEquals("Session not found", response.errorMessage());
    }

    @Test
    void shouldPublishSnapshotExportAndSessionMutationResponses() {
        SessionInspectionService sessionInspectionService = mock(SessionInspectionService.class);
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveInspectionCommandHandler handler = new HiveInspectionCommandHandler(
                sessionInspectionService, publisher, new HiveInspectionPayloadMapper());
        when(sessionInspectionService.exportSessionTraceSnapshotPayload("web:conv-1", "snap-1"))
                .thenReturn(new SessionInspectionService.SnapshotPayloadExport(
                        "{\"ok\":true}",
                        "application/json",
                        ".json"));
        when(sessionInspectionService.compactSession("web:conv-1", 5)).thenReturn(7);

        handler.handle(inspection("req-snapshot", "session.trace.snapshot.payload", body -> {
            body.sessionId("web:conv-1");
            body.snapshotId("snap-1");
        }));
        handler.handle(inspection("req-compact", "session.compact", body -> {
            body.sessionId("web:conv-1");
            body.keepLast(5);
        }));
        handler.handle(inspection("req-clear", "session.clear", body -> body.sessionId("web:conv-1")));
        handler.handle(inspection("req-delete", "session.delete", body -> body.sessionId("web:conv-1")));

        ArgumentCaptor<HiveInspectionResponse> responseCaptor = ArgumentCaptor.forClass(HiveInspectionResponse.class);
        verify(publisher, org.mockito.Mockito.times(4)).publishInspectionResponse(responseCaptor.capture());
        List<HiveInspectionResponse> responses = responseCaptor.getAllValues();
        assertEquals("req-snapshot", responses.get(0).requestId());
        assertTrue(responses.get(0).success());
        assertEquals(
                Map.of(
                        "payloadText", "{\"ok\":true}",
                        "contentType", "application/json",
                        "fileExtension", ".json"),
                responses.get(0).payload());
        assertEquals("req-compact", responses.get(1).requestId());
        assertEquals(Map.of("removed", 7), responses.get(1).payload());
        assertEquals("req-clear", responses.get(2).requestId());
        assertEquals(Map.of(), responses.get(2).payload());
        assertEquals("req-delete", responses.get(3).requestId());
        assertEquals(Map.of(), responses.get(3).payload());
    }

    @Test
    void shouldPublishTraceExportThroughHivePayloadMapper() {
        SessionInspectionService sessionInspectionService = mock(SessionInspectionService.class);
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveInspectionCommandHandler handler = new HiveInspectionCommandHandler(
                sessionInspectionService, publisher, new HiveInspectionPayloadMapper());
        when(sessionInspectionService.getSessionTraceExport("web:conv-1")).thenReturn(SessionTraceExportView.builder()
                .sessionId("web:conv-1")
                .storageStats(SessionTraceStorageStatsView.builder().compressedSnapshotBytes(10L).build())
                .traces(List.of(SessionTraceExportView.TraceExportView.builder()
                        .traceId("trace-1")
                        .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                        .endedAt(Instant.parse("2026-03-20T10:00:01Z"))
                        .spans(List.of(SessionTraceExportView.SpanExportView.builder()
                                .spanId("span-1")
                                .status(SessionTraceExportView.StatusView.builder()
                                        .code("OK")
                                        .message("done")
                                        .build())
                                .events(List.of())
                                .snapshots(List.of(SessionTraceExportView.SnapshotExportView.builder()
                                        .snapshotId("snap-1")
                                        .payloadText("{\"ok\":true}")
                                        .build()))
                                .build()))
                        .build()))
                .build());

        handler.handle(inspection("req-export", "session.trace.export", body -> body.sessionId("web:conv-1")));

        ArgumentCaptor<HiveInspectionResponse> responseCaptor = ArgumentCaptor.forClass(HiveInspectionResponse.class);
        verify(publisher).publishInspectionResponse(responseCaptor.capture());
        HiveInspectionResponse response = responseCaptor.getValue();
        SessionTraceExportPayload payload = assertInstanceOf(SessionTraceExportPayload.class, response.payload());
        assertEquals("web:conv-1", payload.getSessionId());
        assertEquals("2026-03-20T10:00:00Z", payload.getTraces().get(0).getStartedAt());
    }

    @Test
    void shouldForwardSessionMessagesCursorAndLimit() {
        SessionInspectionService sessionInspectionService = mock(SessionInspectionService.class);
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveInspectionCommandHandler handler = new HiveInspectionCommandHandler(
                sessionInspectionService, publisher, new HiveInspectionPayloadMapper());
        when(sessionInspectionService.getSessionMessages("web:conv-1", 25, "m-25"))
                .thenReturn(me.golemcore.bot.domain.view.SessionMessagesPageView.builder()
                        .sessionId("web:conv-1")
                        .messages(List.of())
                        .hasMore(false)
                        .build());

        handler.handle(inspection("req-messages", "session.messages", body -> {
            body.sessionId("web:conv-1");
            body.limit(25);
            body.beforeMessageId("m-25");
        }));

        verify(sessionInspectionService).getSessionMessages("web:conv-1", 25, "m-25");
        ArgumentCaptor<HiveInspectionResponse> responseCaptor = ArgumentCaptor.forClass(HiveInspectionResponse.class);
        verify(publisher, times(1)).publishInspectionResponse(responseCaptor.capture());
        HiveInspectionResponse response = responseCaptor.getValue();
        assertEquals("req-messages", response.requestId());
        assertEquals("session.messages", response.operation());
        assertTrue(response.success());
        Map<?, ?> payload = assertInstanceOf(Map.class, response.payload());
        assertEquals("web:conv-1", payload.get("sessionId"));
    }

    @Test
    void shouldPublishInvalidRequestErrorForUnsupportedOperation() {
        SessionInspectionService sessionInspectionService = mock(SessionInspectionService.class);
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveInspectionCommandHandler handler = new HiveInspectionCommandHandler(
                sessionInspectionService, publisher, new HiveInspectionPayloadMapper());

        handler.handle(inspection("req-bad", "unknown.operation", body -> {
        }));

        ArgumentCaptor<HiveInspectionResponse> responseCaptor = ArgumentCaptor.forClass(HiveInspectionResponse.class);
        verify(publisher).publishInspectionResponse(responseCaptor.capture());
        HiveInspectionResponse response = responseCaptor.getValue();
        assertEquals("req-bad", response.requestId());
        assertFalse(response.success());
        assertEquals("INVALID_REQUEST", response.errorCode());
        assertEquals("Unsupported inspection operation: unknown.operation", response.errorMessage());
        assertNull(response.payload());
    }

    private HiveControlCommandEnvelope inspection(
            String requestId,
            String operation,
            java.util.function.Consumer<HiveInspectionRequestBody.HiveInspectionRequestBodyBuilder> customizer) {
        HiveInspectionRequestBody.HiveInspectionRequestBodyBuilder inspection = HiveInspectionRequestBody.builder()
                .operation(operation);
        customizer.accept(inspection);
        return HiveControlCommandEnvelope.builder()
                .eventType("inspection.request")
                .requestId(requestId)
                .threadId("thread-1")
                .inspection(inspection.build())
                .build();
    }
}
