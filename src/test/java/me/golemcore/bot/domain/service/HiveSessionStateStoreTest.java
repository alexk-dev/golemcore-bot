package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveSessionStateStoreTest {

    private StoragePort storagePort;
    private HiveSessionStateStore store;
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
        when(storagePort.deleteObject(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    persistedFiles.remove(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });

        store = new HiveSessionStateStore(storagePort, objectMapper);
    }

    @Test
    void shouldPersistAndLoadSessionState() {
        HiveSessionState sessionState = HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .accessToken("access")
                .refreshToken("refresh")
                .registeredAt(Instant.parse("2026-03-18T00:00:00Z"))
                .build();

        store.save(sessionState);

        HiveSessionState loaded = store.load().orElseThrow();
        assertEquals("golem-1", loaded.getGolemId());
        assertEquals("https://hive.example.com", loaded.getServerUrl());
        assertEquals("access", loaded.getAccessToken());
    }

    @Test
    void shouldClearPersistedSessionState() {
        store.save(HiveSessionState.builder().golemId("golem-1").build());

        store.clear();

        assertFalse(store.load().isPresent());
        assertTrue(persistedFiles.isEmpty());
    }
}
