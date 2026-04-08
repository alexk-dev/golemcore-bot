package me.golemcore.bot.domain.selfevolving.tactic;

import me.golemcore.bot.domain.model.selfevolving.tactic.TacticOutcomeEntry;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TacticOutcomeJournalServiceTest {

    private StoragePort storagePort;
    private TacticOutcomeJournalService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.getText(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        service = new TacticOutcomeJournalService(storagePort);
    }

    @Test
    void shouldRecordEntryAndPersistToStorage() {
        TacticOutcomeEntry entry = TacticOutcomeEntry.builder()
                .tacticId("tactic-1")
                .rawQuery("test query")
                .finishReason("success")
                .recordedAt(Instant.parse("2026-04-05T12:00:00Z"))
                .build();

        service.record(entry);

        verify(storagePort).putTextAtomic(
                eq("self-evolving"),
                eq("tactic-outcome-journal.json"),
                anyString(),
                eq(true));
        List<TacticOutcomeEntry> entries = service.getEntries();
        assertEquals(1, entries.size());
        assertEquals("tactic-1", entries.get(0).getTacticId());
    }

    @Test
    void shouldIgnoreNullEntry() {
        service.record(null);

        List<TacticOutcomeEntry> entries = service.getEntries();
        assertTrue(entries.isEmpty());
    }

    @Test
    void shouldIgnoreEntryWithBlankTacticId() {
        TacticOutcomeEntry entry = TacticOutcomeEntry.builder()
                .tacticId("  ")
                .finishReason("success")
                .build();

        service.record(entry);

        List<TacticOutcomeEntry> entries = service.getEntries();
        assertTrue(entries.isEmpty());
    }

    @Test
    void shouldLoadEntriesFromStorageOnFirstAccess() {
        String json = "[{\"tacticId\":\"tactic-stored\",\"finishReason\":\"error\"}]";
        when(storagePort.getText("self-evolving", "tactic-outcome-journal.json"))
                .thenReturn(CompletableFuture.completedFuture(json));

        List<TacticOutcomeEntry> entries = service.getEntries();

        assertEquals(1, entries.size());
        assertEquals("tactic-stored", entries.get(0).getTacticId());
        assertEquals("error", entries.get(0).getFinishReason());
    }

    @Test
    void shouldNotBreakOnStorageReadFailure() {
        when(storagePort.getText("self-evolving", "tactic-outcome-journal.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("read error")));

        List<TacticOutcomeEntry> entries = service.getEntries();

        assertTrue(entries.isEmpty());
    }

    @Test
    void shouldNotBreakOnStorageWriteFailure() {
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("write error")));
        TacticOutcomeEntry entry = TacticOutcomeEntry.builder()
                .tacticId("tactic-1")
                .finishReason("success")
                .build();

        service.record(entry);

        // Should not throw — fail-open behavior
    }

    @Test
    void shouldNotLoseEntriesWhenTwoRecordsOverlap() throws Exception {
        AtomicReference<String> storageJson = new AtomicReference<>(null);
        AtomicInteger writeCount = new AtomicInteger();
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch secondWriteAttempted = new CountDownLatch(1);
        CountDownLatch allowFirstWriteToFinish = new CountDownLatch(1);

        when(storagePort.getText("self-evolving", "tactic-outcome-journal.json"))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(storageJson.get()));
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    String json = invocation.getArgument(2);
                    int currentWrite = writeCount.incrementAndGet();
                    if (currentWrite == 1) {
                        firstWriteStarted.countDown();
                        allowFirstWriteToFinish.await(5, TimeUnit.SECONDS);
                    }
                    storageJson.set(json);
                    return CompletableFuture.completedFuture(null);
                });

        TacticOutcomeEntry first = TacticOutcomeEntry.builder()
                .tacticId("tactic-1")
                .finishReason("success")
                .recordedAt(Instant.parse("2026-04-05T12:00:00Z"))
                .build();
        TacticOutcomeEntry second = TacticOutcomeEntry.builder()
                .tacticId("tactic-2")
                .finishReason("error")
                .recordedAt(Instant.parse("2026-04-05T12:01:00Z"))
                .build();

        CompletableFuture<Void> firstWrite = CompletableFuture.runAsync(() -> service.record(first));
        assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> secondWrite = CompletableFuture.runAsync(() -> {
            secondWriteAttempted.countDown();
            service.record(second);
        });
        assertTrue(secondWriteAttempted.await(5, TimeUnit.SECONDS));
        allowFirstWriteToFinish.countDown();
        firstWrite.join();
        secondWrite.join();

        List<TacticOutcomeEntry> entries = service.getEntries();
        assertEquals(2, entries.size());
    }
}
