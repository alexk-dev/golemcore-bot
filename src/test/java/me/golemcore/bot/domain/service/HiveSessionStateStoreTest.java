package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
                .scopes(List.of("dispatch", "chat"))
                .registeredAt(Instant.parse("2026-03-18T00:00:00Z"))
                .build();

        store.save(sessionState);

        HiveSessionState loaded = store.load().orElseThrow();
        assertEquals("golem-1", loaded.getGolemId());
        assertEquals("https://hive.example.com", loaded.getServerUrl());
        assertEquals("access", loaded.getAccessToken());
        assertNotSame(sessionState.getScopes(), loaded.getScopes());
    }

    @Test
    void shouldClearPersistedSessionState() {
        store.save(HiveSessionState.builder().golemId("golem-1").build());

        store.clear();

        assertFalse(store.load().isPresent());
        assertTrue(persistedFiles.isEmpty());
    }

    @Test
    void shouldRejectNullSessionState() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> store.save(null));
        assertEquals("Hive session state is required", exception.getMessage());
    }

    @Test
    void shouldThrowWhenPersistingSessionStateFails() {
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk error")));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> store.save(HiveSessionState.builder().golemId("golem-1").build()));
        assertEquals("Failed to persist Hive session state", exception.getMessage());
    }

    @Test
    void shouldLoadPersistedStateOnlyOnce() throws Exception {
        HiveSessionState persistedState = HiveSessionState.builder()
                .golemId("golem-2")
                .serverUrl("https://hive.example.com")
                .accessToken("access-2")
                .registeredAt(Instant.parse("2026-03-18T00:00:10Z"))
                .build();
        persistedFiles.put(
                "hive-session.json",
                new ObjectMapper().registerModule(new JavaTimeModule())
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(persistedState));

        HiveSessionStateStore freshStore = new HiveSessionStateStore(storagePort, new ObjectMapper()
                .registerModule(new JavaTimeModule()));

        HiveSessionState firstLoad = freshStore.load().orElseThrow();
        HiveSessionState secondLoad = freshStore.load().orElseThrow();

        assertEquals("golem-2", firstLoad.getGolemId());
        assertEquals("golem-2", secondLoad.getGolemId());
        verify(storagePort, times(1)).getText("preferences", "hive-session.json");
    }

    @Test
    void shouldReturnFromInnerLoadedCheckWhenConcurrentLoadWinsRace() throws Exception {
        StoragePort concurrentStoragePort = mock(StoragePort.class);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        HiveSessionState persistedState = HiveSessionState.builder()
                .golemId("golem-3")
                .serverUrl("https://hive.example.com")
                .accessToken("access-3")
                .registeredAt(Instant.parse("2026-03-18T00:00:20Z"))
                .build();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(persistedState);
        CountDownLatch firstReadStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRead = new CountDownLatch(1);

        when(concurrentStoragePort.getText(anyString(), anyString())).thenAnswer(invocation -> {
            firstReadStarted.countDown();
            assertTrue(releaseFirstRead.await(2, TimeUnit.SECONDS));
            return CompletableFuture.completedFuture(json);
        });

        HiveSessionStateStore concurrentStore = new HiveSessionStateStore(concurrentStoragePort, mapper);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var firstLoad = executor.submit(concurrentStore::load);
            assertTrue(firstReadStarted.await(2, TimeUnit.SECONDS));

            var secondLoad = executor.submit(concurrentStore::load);
            releaseFirstRead.countDown();

            assertEquals("golem-3", firstLoad.get(2, TimeUnit.SECONDS).orElseThrow().getGolemId());
            assertEquals("golem-3", secondLoad.get(2, TimeUnit.SECONDS).orElseThrow().getGolemId());
        }

        verify(concurrentStoragePort, times(1)).getText("preferences", "hive-session.json");
    }

    @Test
    void shouldReturnEmptyWhenPersistedStateIsBlank() {
        persistedFiles.put("hive-session.json", "   ");

        HiveSessionStateStore freshStore = new HiveSessionStateStore(storagePort, new ObjectMapper()
                .registerModule(new JavaTimeModule()));

        assertFalse(freshStore.load().isPresent());
    }

    @Test
    void shouldReturnEmptyWhenPersistedStateIsInvalid() {
        persistedFiles.put("hive-session.json", "{broken");

        HiveSessionStateStore freshStore = new HiveSessionStateStore(storagePort, new ObjectMapper()
                .registerModule(new JavaTimeModule()));

        assertFalse(freshStore.load().isPresent());
    }

    @Test
    void shouldClearCachedStateEvenWhenDeleteFails() {
        store.save(HiveSessionState.builder().golemId("golem-1").build());
        when(storagePort.deleteObject(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("delete failed")));

        store.clear();

        assertFalse(store.load().isPresent());
    }
}
