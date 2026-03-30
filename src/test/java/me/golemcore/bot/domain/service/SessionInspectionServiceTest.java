package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceSummaryDto;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSnapshot;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

        SessionTraceSummaryDto summary = service.getSessionTraceSummary("s-trace");

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
}
