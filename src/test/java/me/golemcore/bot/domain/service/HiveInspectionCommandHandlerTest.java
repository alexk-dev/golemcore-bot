package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import me.golemcore.bot.adapter.inbound.web.dto.SessionSummaryDto;
import me.golemcore.bot.adapter.outbound.hive.HiveEventBatchPublisher;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveInspectionRequestBody;
import me.golemcore.bot.domain.model.HiveInspectionResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class HiveInspectionCommandHandlerTest {

    @Test
    void shouldPublishSessionListInspectionResponse() {
        SessionInspectionService sessionInspectionService = mock(SessionInspectionService.class);
        HiveEventBatchPublisher publisher = mock(HiveEventBatchPublisher.class);
        HiveInspectionCommandHandler handler = new HiveInspectionCommandHandler(sessionInspectionService, publisher);
        when(sessionInspectionService.listSessions("web")).thenReturn(List.of(SessionSummaryDto.builder()
                .id("web:conv-1")
                .channelType("web")
                .chatId("legacy-chat")
                .conversationKey("conv-1")
                .transportChatId("client-1")
                .messageCount(1)
                .state("ACTIVE")
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
        assertInstanceOf(List.class, response.payload());
        assertEquals(1, ((List<?>) response.payload()).size());
    }

    @Test
    void shouldPublishTypedErrorResponseWhenInspectionOperationFails() {
        SessionInspectionService sessionInspectionService = mock(SessionInspectionService.class);
        HiveEventBatchPublisher publisher = mock(HiveEventBatchPublisher.class);
        HiveInspectionCommandHandler handler = new HiveInspectionCommandHandler(sessionInspectionService, publisher);
        when(sessionInspectionService.getSessionTraceSummary("missing"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

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
}
