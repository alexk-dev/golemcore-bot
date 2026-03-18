package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
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
        HiveControlInboxService.InboxSummary summary = service.recordReceived(HiveControlCommandEnvelope.builder()
                .commandId("cmd-1")
                .threadId("thread-1")
                .createdAt(Instant.parse("2026-03-18T00:00:00Z"))
                .build());

        assertEquals(1, summary.receivedCommandCount());
        assertEquals(1, summary.bufferedCommandCount());
        assertEquals("cmd-1", summary.lastReceivedCommandId());
        assertEquals("cmd-1", service.getSummary().lastReceivedCommandId());
    }
}
