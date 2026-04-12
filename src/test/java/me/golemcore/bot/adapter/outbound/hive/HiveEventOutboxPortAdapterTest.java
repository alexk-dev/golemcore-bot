package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.hive.HiveOutboxSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveEventOutboxPortAdapterTest {

    private HiveEventOutboxService hiveEventOutboxService;
    private HiveApiClient hiveApiClient;
    private HiveEventOutboxPortAdapter adapter;

    @BeforeEach
    void setUp() {
        hiveEventOutboxService = mock(HiveEventOutboxService.class);
        hiveApiClient = mock(HiveApiClient.class);
        adapter = new HiveEventOutboxPortAdapter(hiveEventOutboxService, hiveApiClient);
    }

    @Test
    void shouldMapSummaryToDomainRecord() {
        when(hiveEventOutboxService.getSummary()).thenReturn(new HiveEventOutboxService.OutboxSummary(2, 5, "boom"));

        HiveOutboxSummary summary = adapter.getSummary();

        assertEquals(2, summary.pendingBatchCount());
        assertEquals(5, summary.pendingEventCount());
        assertEquals("boom", summary.lastError());
    }

    @Test
    void shouldFlushPendingBatchesThroughHiveApiClient() {
        HiveSessionState sessionState = HiveSessionState.builder()
                .serverUrl("https://hive.example.com")
                .golemId("golem-1")
                .accessToken("access")
                .build();
        List<HiveEventPayload> events = List.of(HiveEventPayload.builder().eventType("runtime_event").build());
        doAnswer(invocation -> {
            HiveEventOutboxService.BatchSender sender = invocation.getArgument(1);
            sender.send("https://hive.example.com", "golem-1", "access", events);
            return new HiveEventOutboxService.OutboxSummary(0, 0, null);
        }).when(hiveEventOutboxService).flush(eq(sessionState), any(HiveEventOutboxService.BatchSender.class));

        adapter.flushPending(sessionState);

        verify(hiveApiClient).publishEventsBatch("https://hive.example.com", "golem-1", "access", events);
    }

    @Test
    void shouldClearOutbox() {
        adapter.clear();

        verify(hiveEventOutboxService).clear();
    }
}
