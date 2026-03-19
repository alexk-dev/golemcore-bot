package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveControlInboxServiceTest {

    private StoragePort storagePort;
    private HiveControlInboxService service;
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

        service = new HiveControlInboxService(storagePort, objectMapper);
    }

    @Test
    void shouldPersistReceivedCommandsAndExposeSummary() {
        HiveControlInboxService.RecordResult result = service.recordReceived(command("cmd-1", "thread-1", "run-1"));

        assertFalse(result.duplicate());
        assertEquals(1, result.summary().receivedCommandCount());
        assertEquals(1, result.summary().bufferedCommandCount());
        assertEquals(1, result.summary().pendingCommandCount());
        assertEquals("cmd-1", result.summary().lastReceivedCommandId());
        assertEquals("cmd-1", service.getSummary().lastReceivedCommandId());
    }

    @Test
    void shouldDeduplicateRepeatedCommandId() {
        service.recordReceived(command("cmd-1", "thread-1", "run-1"));

        HiveControlInboxService.RecordResult duplicate = service.recordReceived(command("cmd-1", "thread-1", "run-1"));

        assertEquals(1, duplicate.summary().receivedCommandCount());
        assertEquals(1, duplicate.summary().bufferedCommandCount());
        assertEquals(1, duplicate.summary().pendingCommandCount());
        assertEquals("cmd-1", duplicate.summary().lastReceivedCommandId());
    }

    @Test
    void shouldReplayFailedCommandOnNextDrain() {
        service.recordReceived(command("cmd-1", "thread-1", "run-1"));
        List<String> dispatched = new ArrayList<>();

        int firstDrainCount = service.drainPending(envelope -> {
            throw new IllegalStateException("temporary failure");
        });
        int secondDrainCount = service.drainPending(envelope -> dispatched.add(envelope.getCommandId()));

        assertEquals(0, firstDrainCount);
        assertEquals(1, secondDrainCount);
        assertEquals(List.of("cmd-1"), dispatched);
        assertEquals(0, service.getSummary().pendingCommandCount());
    }

    @Test
    void shouldRequireCommandId() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.recordReceived(HiveControlCommandEnvelope.builder().threadId("thread-1").build()));

        assertEquals("Hive control command commandId is required", error.getMessage());
    }

    private HiveControlCommandEnvelope command(String commandId, String threadId, String runId) {
        return HiveControlCommandEnvelope.builder()
                .commandId(commandId)
                .threadId(threadId)
                .runId(runId)
                .createdAt(Instant.parse("2026-03-18T00:00:00Z"))
                .build();
    }
}
