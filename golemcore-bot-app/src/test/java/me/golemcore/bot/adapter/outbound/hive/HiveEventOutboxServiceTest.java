package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveEventOutboxServiceTest {

    private StoragePort storagePort;
    private HiveEventOutboxService service;
    private Map<String, String> persistedFiles;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        persistedFiles = new ConcurrentHashMap<>();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    persistedFiles.put(invocation.getArgument(1), invocation.getArgument(2));
                    return CompletableFuture.completedFuture(null);
                });
        when(storagePort.getText(anyString(), anyString()))
                .thenAnswer(
                        invocation -> CompletableFuture.completedFuture(persistedFiles.get(invocation.getArgument(1))));

        service = new HiveEventOutboxService(storagePort, objectMapper);
    }

    @Test
    void shouldKeepBatchPendingWhenFlushFailsAndReplayLater() {
        HiveSessionState sessionState = HiveSessionState.builder()
                .serverUrl("https://hive.example.com")
                .golemId("golem-1")
                .accessToken("access")
                .build();

        service.enqueue(sessionState, List.of(HiveEventPayload.builder()
                .schemaVersion(1)
                .eventType("runtime_event")
                .runtimeEventType("RUN_PROGRESS")
                .threadId("thread-1")
                .commandId("cmd-1")
                .runId("run-1")
                .createdAt(Instant.parse("2026-03-18T00:00:00Z"))
                .build()));

        HiveEventOutboxService.OutboxSummary failedSummary = service.flush(
                sessionState,
                (serverUrl, golemId, accessToken, events) -> {
                    throw new IllegalStateException("network unavailable");
                });

        AtomicInteger sendCount = new AtomicInteger();
        HiveEventOutboxService.OutboxSummary successSummary = service.flush(
                sessionState,
                (serverUrl, golemId, accessToken, events) -> sendCount.incrementAndGet());

        assertEquals(1, failedSummary.pendingBatchCount());
        assertEquals(1, failedSummary.pendingEventCount());
        assertEquals(1, sendCount.get());
        assertEquals(0, successSummary.pendingBatchCount());
        assertEquals(0, successSummary.pendingEventCount());
    }

    @Test
    void shouldTrimOldestBatchesWhenOutboxExceedsCapacity() {
        HiveSessionState sessionState = HiveSessionState.builder()
                .serverUrl("https://hive.example.com")
                .golemId("golem-1")
                .accessToken("access")
                .build();

        for (int index = 0; index < 300; index++) {
            service.enqueue(sessionState, List.of(HiveEventPayload.builder()
                    .schemaVersion(1)
                    .eventType("runtime_event")
                    .runtimeEventType("RUN_PROGRESS")
                    .threadId("thread-" + index)
                    .commandId("cmd-" + index)
                    .runId("run-" + index)
                    .createdAt(Instant.parse("2026-03-18T00:00:00Z"))
                    .build()));
        }

        HiveEventOutboxService.OutboxSummary summary = service.getSummary();

        assertEquals(256, summary.pendingBatchCount());
        assertEquals(256, summary.pendingEventCount());
    }
}
