package me.golemcore.bot.adapter.inbound.web.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.util.List;
import me.golemcore.bot.adapter.inbound.web.dto.SessionDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionTraceSummaryDto;
import me.golemcore.bot.adapter.shared.dto.SessionTraceExportPayload;
import me.golemcore.bot.domain.view.SessionDetailView;
import me.golemcore.bot.domain.view.SessionTraceExportView;
import me.golemcore.bot.domain.view.SessionTraceStorageStatsView;
import me.golemcore.bot.domain.view.SessionTraceSummaryView;
import org.junit.jupiter.api.Test;

class SessionWebDtoMapperTest {

    private final SessionWebDtoMapper mapper = new SessionWebDtoMapper();

    @Test
    void shouldFormatSessionDetailInstantsAndAttachmentDownloadUrl() {
        SessionDetailView view = SessionDetailView.builder()
                .id("web:conv-1")
                .channelType("web")
                .chatId("legacy-chat")
                .conversationKey("conv-1")
                .transportChatId("client-1")
                .state("ACTIVE")
                .createdAt(Instant.parse("2026-03-20T10:00:00Z"))
                .updatedAt(Instant.parse("2026-03-20T10:05:00Z"))
                .messages(List.of(SessionDetailView.MessageView.builder()
                        .id("m-1")
                        .role("user")
                        .content("[1 attachment]")
                        .timestamp(Instant.parse("2026-03-20T10:01:00Z"))
                        .attachments(List.of(
                                SessionDetailView.AttachmentView.builder()
                                        .type("image")
                                        .name("diagram.png")
                                        .mimeType("image/png")
                                        .internalFilePath("folder/diagram 1.png")
                                        .thumbnailBase64("thumb")
                                        .build(),
                                SessionDetailView.AttachmentView.builder()
                                        .type("file")
                                        .name("report.txt")
                                        .mimeType("text/plain")
                                        .directUrl("https://cdn.example.com/report.txt")
                                        .internalFilePath("folder/report.txt")
                                        .build()))
                        .build()))
                .build();

        SessionDetailDto dto = mapper.toDetailDto(view);

        assertEquals("2026-03-20T10:00:00Z", dto.getCreatedAt());
        assertEquals("2026-03-20T10:05:00Z", dto.getUpdatedAt());
        assertEquals("2026-03-20T10:01:00Z", dto.getMessages().get(0).getTimestamp());
        assertEquals("/api/files/download?path=folder%2Fdiagram%201.png",
                dto.getMessages().get(0).getAttachments().get(0).getUrl());
        assertEquals("https://cdn.example.com/report.txt",
                dto.getMessages().get(0).getAttachments().get(1).getUrl());
    }

    @Test
    void shouldFormatTraceInstantsForWebDto() {
        SessionTraceSummaryView view = SessionTraceSummaryView.builder()
                .sessionId("web:conv-1")
                .traceCount(1)
                .spanCount(2)
                .snapshotCount(3)
                .storageStats(SessionTraceStorageStatsView.builder().compressedSnapshotBytes(10L).build())
                .traces(List.of(SessionTraceSummaryView.TraceSummaryView.builder()
                        .traceId("trace-1")
                        .rootSpanId("root")
                        .traceName("session.inspect")
                        .rootKind("INTERNAL")
                        .rootStatusCode("OK")
                        .startedAt(Instant.parse("2026-03-20T10:00:00Z"))
                        .endedAt(Instant.parse("2026-03-20T10:00:01Z"))
                        .durationMs(1000L)
                        .spanCount(2)
                        .snapshotCount(3)
                        .truncated(false)
                        .build()))
                .build();

        SessionTraceSummaryDto dto = mapper.toTraceSummaryDto(view);

        assertNotNull(dto.getStorageStats());
        assertEquals("2026-03-20T10:00:00Z", dto.getTraces().get(0).getStartedAt());
        assertEquals("2026-03-20T10:00:01Z", dto.getTraces().get(0).getEndedAt());
    }

    @Test
    void shouldBuildTypedTraceExportPayloadWithIsoInstants() {
        SessionTraceExportView view = SessionTraceExportView.builder()
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
                                .events(List.of(SessionTraceExportView.EventExportView.builder()
                                        .name("event-1")
                                        .timestamp(Instant.parse("2026-03-20T10:00:00.500Z"))
                                        .build()))
                                .snapshots(List.of(SessionTraceExportView.SnapshotExportView.builder()
                                        .snapshotId("snap-1")
                                        .payloadText("{\"ok\":true}")
                                        .build()))
                                .build()))
                        .build()))
                .build();

        SessionTraceExportPayload payload = mapper.toTraceExportPayload(view);

        assertEquals("web:conv-1", payload.getSessionId());
        assertEquals("2026-03-20T10:00:00Z", payload.getTraces().get(0).getStartedAt());
        assertEquals("2026-03-20T10:00:00.500Z",
                payload.getTraces().get(0).getSpans().get(0).getEvents().get(0).getTimestamp());
        assertEquals("{\"ok\":true}",
                payload.getTraces().get(0).getSpans().get(0).getSnapshots().get(0).getPayloadText());
    }
}
