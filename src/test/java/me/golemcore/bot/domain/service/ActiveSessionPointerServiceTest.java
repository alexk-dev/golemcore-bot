package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActiveSessionPointerServiceTest {

    private StoragePort storagePort;
    private ActiveSessionPointerService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        when(storagePort.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(CompletableFuture.completedFuture(null));
        service = new ActiveSessionPointerService(storagePort, new ObjectMapper());
    }

    @Test
    void shouldPersistAndReadWebPointer() {
        String pointerKey = service.buildWebPointerKey("admin", "client-1");
        service.setActiveConversationKey(pointerKey, "session-1234");

        Optional<String> active = service.getActiveConversationKey(pointerKey);

        assertEquals(Optional.of("session-1234"), active);
        verify(storagePort).putTextAtomic(org.mockito.ArgumentMatchers.eq("preferences"),
                org.mockito.ArgumentMatchers.eq("session-pointers.json"),
                anyString(),
                org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void shouldRollbackInMemoryStateWhenPersistFails() {
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk-full")));
        String pointerKey = service.buildWebPointerKey("admin", "client-1");

        assertThrows(IllegalStateException.class, () -> service.setActiveConversationKey(pointerKey, "session-1234"));
        assertEquals(Optional.empty(), service.getActiveConversationKey(pointerKey));
    }

    @Test
    void shouldClearPointer() {
        String pointerKey = service.buildTelegramPointerKey("100");
        service.setActiveConversationKey(pointerKey, "telegram-session");
        service.clearActiveConversationKey(pointerKey);

        assertEquals(Optional.empty(), service.getActiveConversationKey(pointerKey));
    }

    @Test
    void shouldNormalizeBlankPointerSegments() {
        assertEquals("web|_|_", service.buildWebPointerKey(" ", null));
        assertEquals("telegram|_", service.buildTelegramPointerKey("  "));
    }

    @Test
    void shouldIgnoreCorruptedStoredPointersAndContinueWorking() {
        when(storagePort.getText(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture("{broken-json"));
        service = new ActiveSessionPointerService(storagePort, new ObjectMapper());

        assertEquals(Optional.empty(), service.getActiveConversationKey("web|admin|client-1"));
        service.setActiveConversationKey("web|admin|client-1", "session-100");

        assertEquals(Optional.of("session-100"), service.getActiveConversationKey("web|admin|client-1"));
    }

    @Test
    void shouldValidateArgumentsForSetPointer() {
        assertThrows(IllegalArgumentException.class, () -> service.setActiveConversationKey(" ", "session-1"));
        assertThrows(IllegalArgumentException.class, () -> service.setActiveConversationKey("web|admin|client-1", " "));
    }

    @Test
    void shouldReturnSnapshotOfPointers() {
        String webPointer = service.buildWebPointerKey("admin", "client-1");
        String tgPointer = service.buildTelegramPointerKey("100");
        service.setActiveConversationKey(webPointer, "session-1");
        service.setActiveConversationKey(tgPointer, "session-2");

        java.util.Map<String, String> snapshot = service.getPointersSnapshot();

        assertEquals("session-1", snapshot.get(webPointer));
        assertEquals("session-2", snapshot.get(tgPointer));
    }

    @Test
    void shouldRollbackClearWhenPersistFails() {
        String pointerKey = service.buildTelegramPointerKey("100");
        service.setActiveConversationKey(pointerKey, "telegram-session");
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk-full")));

        assertThrows(IllegalStateException.class, () -> service.clearActiveConversationKey(pointerKey));
        assertEquals(Optional.of("telegram-session"), service.getActiveConversationKey(pointerKey));
    }

    @Test
    void shouldNotPersistWhenClearingUnknownPointer() {
        service.clearActiveConversationKey("telegram|unknown");

        verify(storagePort, never()).putTextAtomic(org.mockito.ArgumentMatchers.eq("preferences"),
                org.mockito.ArgumentMatchers.eq("session-pointers.json"),
                anyString(),
                org.mockito.ArgumentMatchers.eq(true));
        assertTrue(service.getActiveConversationKey("telegram|unknown").isEmpty());
    }

    @Test
    void shouldSkipPersistWhenPointerAlreadyMatchesRequestedConversation() {
        String pointerKey = service.buildWebPointerKey("admin", "client-1");

        service.setActiveConversationKey(pointerKey, "session-1234");
        service.setActiveConversationKey(pointerKey, "session-1234");

        verify(storagePort, times(1)).putTextAtomic(org.mockito.ArgumentMatchers.eq("preferences"),
                org.mockito.ArgumentMatchers.eq("session-pointers.json"),
                anyString(),
                org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void shouldSerializeConcurrentWritesToPointerRegistry() throws InterruptedException {
        TrackingStoragePort trackingStoragePort = new TrackingStoragePort();
        service = new ActiveSessionPointerService(trackingStoragePort, new ObjectMapper());
        String pointerKey = service.buildWebPointerKey("admin", "client-1");

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> first = CompletableFuture.runAsync(
                    () -> service.setActiveConversationKey(pointerKey, "session-1"),
                    executor);
            CompletableFuture<Void> second = CompletableFuture.runAsync(
                    () -> service.setActiveConversationKey(pointerKey, "session-2"),
                    executor);

            assertDoesNotThrow(() -> CompletableFuture.allOf(first, second).join());
            assertEquals(1, trackingStoragePort.maxConcurrentWrites());
            assertTrue(List.of("session-1", "session-2")
                    .contains(service.getActiveConversationKey(pointerKey).orElse(null)));
        }
    }

    private static final class TrackingStoragePort implements StoragePort {

        private final AtomicInteger concurrentWrites = new AtomicInteger();
        private final AtomicInteger maxConcurrentWritesCounter = new AtomicInteger();

        @Override
        public CompletableFuture<Void> putObject(String directory, String path, byte[] content) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> putText(String directory, String path, String content) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> getObject(String directory, String path) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<String> getText(String directory, String path) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Boolean> exists(String directory, String path) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletableFuture<Void> deleteObject(String directory, String path) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<String>> listObjects(String directory, String prefix) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<Void> appendText(String directory, String path, String content) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> putTextAtomic(String directory, String path, String content, boolean backup) {
            return CompletableFuture.runAsync(() -> {
                int active = concurrentWrites.incrementAndGet();
                maxConcurrentWritesCounter.accumulateAndGet(active, Math::max);
                try {
                    Thread.sleep(75);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                } finally {
                    concurrentWrites.decrementAndGet();
                }
            });
        }

        @Override
        public CompletableFuture<Void> ensureDirectory(String directory) {
            return CompletableFuture.completedFuture(null);
        }

        int maxConcurrentWrites() {
            return maxConcurrentWritesCounter.get();
        }
    }
}
