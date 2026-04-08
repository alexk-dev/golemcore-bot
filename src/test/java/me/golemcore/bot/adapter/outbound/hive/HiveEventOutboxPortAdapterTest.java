package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.port.outbound.HiveEventOutboxPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HiveEventOutboxPortAdapterTest {

    @Mock
    private HiveApiClient hiveApiClient;

    @Mock
    private HiveEventOutboxService hiveEventOutboxService;

    private HiveEventOutboxPortAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new HiveEventOutboxPortAdapter(hiveApiClient, hiveEventOutboxService);
    }

    @Test
    void getSummaryShouldMapServiceSummary() {
        when(hiveEventOutboxService.getSummary()).thenReturn(new HiveEventOutboxService.OutboxSummary(2, 5, "boom"));

        HiveEventOutboxPort.OutboxSummary summary = adapter.getSummary();

        assertEquals(2, summary.pendingBatchCount());
        assertEquals(5, summary.pendingEventCount());
        assertEquals("boom", summary.lastError());
    }

    @Test
    void flushShouldDelegateBatchPublishingThroughApiClient() {
        HiveSessionState sessionState = HiveSessionState.builder()
                .serverUrl("https://hive.example.com")
                .golemId("golem-1")
                .accessToken("access")
                .build();
        List<HiveEventPayload> events = List.of(HiveEventPayload.builder()
                .schemaVersion(1)
                .eventType("runtime_event")
                .runtimeEventType("COMMAND_ACKNOWLEDGED")
                .commandId("cmd-1")
                .createdAt(Instant.parse("2026-04-08T00:00:00Z"))
                .build());
        doAnswer(invocation -> {
            HiveEventOutboxService.BatchSender batchSender = invocation.getArgument(1);
            batchSender.send("https://hive.example.com", "golem-1", "access", events);
            return new HiveEventOutboxService.OutboxSummary(1, events.size(), null);
        }).when(hiveEventOutboxService).flush(eq(sessionState), any());

        HiveEventOutboxPort.OutboxSummary summary = adapter.flush(sessionState);

        verify(hiveApiClient).publishEventsBatch("https://hive.example.com", "golem-1", "access", events);
        assertEquals(1, summary.pendingBatchCount());
        assertEquals(1, summary.pendingEventCount());
        assertEquals(null, summary.lastError());
    }

    @Test
    void clearShouldDelegateToService() {
        adapter.clear();

        verify(hiveEventOutboxService).clear();
    }
}
