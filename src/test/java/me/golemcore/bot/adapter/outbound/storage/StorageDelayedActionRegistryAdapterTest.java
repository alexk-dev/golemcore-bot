package me.golemcore.bot.adapter.outbound.storage;

import me.golemcore.bot.domain.model.DelayedActionKind;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageDelayedActionRegistryAdapterTest {

    @Test
    void shouldPersistAndLoadDelayedActions() {
        InMemoryStoragePort storagePort = new InMemoryStoragePort();
        StorageDelayedActionRegistryAdapter adapter = new StorageDelayedActionRegistryAdapter(storagePort);
        DelayedSessionAction action = DelayedSessionAction.builder()
                .id("action-1")
                .channelType("telegram")
                .kind(DelayedActionKind.REMIND_LATER)
                .runAt(Instant.parse("2026-04-01T12:00:00Z"))
                .build();

        adapter.saveActions(List.of(action));

        List<DelayedSessionAction> loaded = adapter.loadActions();

        assertEquals(1, loaded.size());
        assertEquals("action-1", loaded.getFirst().getId());
        assertTrue(storagePort.content.contains("\"version\""));

        adapter.saveActions(null);
        assertTrue(adapter.loadActions().isEmpty());
    }

    @Test
    void shouldReturnEmptyOnMissingOrBrokenRegistryAndWrapSaveFailures() {
        InMemoryStoragePort storagePort = new InMemoryStoragePort();
        StorageDelayedActionRegistryAdapter adapter = new StorageDelayedActionRegistryAdapter(storagePort);

        assertTrue(adapter.loadActions().isEmpty());

        storagePort.content = "{";
        assertTrue(adapter.loadActions().isEmpty());

        storagePort.failRead = true;
        assertTrue(adapter.loadActions().isEmpty());

        storagePort.failRead = false;
        storagePort.failWrite = true;
        assertThrows(IllegalStateException.class, () -> adapter.saveActions(List.of()));
    }

    private static final class InMemoryStoragePort implements StoragePort {

        private String content = "";
        private boolean contentPresent;
        private boolean failRead;
        private boolean failWrite;

        @Override
        public CompletableFuture<Void> putObject(String directory, String path, byte[] content) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> putText(String directory, String path, String content) {
            this.content = content;
            contentPresent = true;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> getObject(String directory, String path) {
            return CompletableFuture.completedFuture(new byte[0]);
        }

        @Override
        public CompletableFuture<String> getText(String directory, String path) {
            if (failRead) {
                return failedFuture(new RuntimeException("read failed"));
            }
            return CompletableFuture.completedFuture(contentPresent ? content : null);
        }

        @Override
        public CompletableFuture<Boolean> exists(String directory, String path) {
            return CompletableFuture.completedFuture(contentPresent);
        }

        @Override
        public CompletableFuture<Void> deleteObject(String directory, String path) {
            content = "";
            contentPresent = false;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<String>> listObjects(String directory, String prefix) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<Void> appendText(String directory, String path, String content) {
            this.content = contentPresent ? this.content + content : content;
            contentPresent = true;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> putTextAtomic(String directory, String path, String content, boolean backup) {
            if (failWrite) {
                return failedFuture(new RuntimeException("write failed"));
            }
            this.content = content;
            contentPresent = true;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> ensureDirectory(String directory) {
            return CompletableFuture.completedFuture(null);
        }

        private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
            CompletableFuture<T> future = new CompletableFuture<>();
            future.completeExceptionally(throwable);
            return future;
        }
    }
}
