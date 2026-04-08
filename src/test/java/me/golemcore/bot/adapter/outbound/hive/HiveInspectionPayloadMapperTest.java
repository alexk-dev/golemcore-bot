package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Instant;
import java.util.List;
import me.golemcore.bot.adapter.shared.dto.SessionTraceExportPayload;
import me.golemcore.bot.domain.view.SessionTraceExportView;
import me.golemcore.bot.domain.view.SessionTraceStorageStatsView;
import org.junit.jupiter.api.Test;

class HiveInspectionPayloadMapperTest {

    private final HiveInspectionPayloadMapper mapper = new HiveInspectionPayloadMapper();

    @Test
    void shouldMapTraceExportToTypedSharedPayload() {
        Object payload = mapper.toSessionTraceExportPayload(SessionTraceExportView.builder()
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

        SessionTraceExportPayload typedPayload = assertInstanceOf(SessionTraceExportPayload.class, payload);
        assertEquals("web:conv-1", typedPayload.getSessionId());
        assertEquals("2026-03-20T10:00:00Z", typedPayload.getTraces().get(0).getStartedAt());
        assertEquals("{\"ok\":true}",
                typedPayload.getTraces().get(0).getSpans().get(0).getSnapshots().get(0).getPayloadText());
    }
}
