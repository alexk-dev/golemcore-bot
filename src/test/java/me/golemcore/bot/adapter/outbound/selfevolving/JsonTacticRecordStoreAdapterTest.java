package me.golemcore.bot.adapter.outbound.selfevolving;

import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTacticRecordStoreAdapterTest {

    @Test
    void shouldLoadValidTacticsWhileSkippingBrokenEntries() {
        InMemoryStoragePort storagePort = new InMemoryStoragePort();
        storagePort.paths = List.of(
                "tactics/old.json",
                "tactics/new.json",
                "tactics/broken.json",
                "tactics/blank.json",
                "notes/ignored.json",
                "tactics/ignored.txt");
        storagePort.text.put(key("self-evolving", "tactics/old.json"),
                "{\"tacticId\":\"old\",\"updatedAt\":\"2026-04-01T10:00:00Z\"}");
        storagePort.text.put(key("self-evolving", "tactics/new.json"),
                "{\"tacticId\":\"new\",\"updatedAt\":\"2026-04-01T11:00:00Z\"}");
        storagePort.text.put(key("self-evolving", "tactics/broken.json"), "{");
        storagePort.text.put(key("self-evolving", "tactics/blank.json"), " ");

        JsonTacticRecordStoreAdapter adapter = new JsonTacticRecordStoreAdapter(storagePort);

        List<TacticRecord> records = adapter.loadAll();

        assertEquals(List.of("new", "old"), records.stream().map(TacticRecord::getTacticId).toList());

        storagePort.failList = true;
        assertTrue(adapter.loadAll().isEmpty());
    }

    @Test
    void shouldSaveAndDeleteTacticRecords() {
        InMemoryStoragePort storagePort = new InMemoryStoragePort();
        JsonTacticRecordStoreAdapter adapter = new JsonTacticRecordStoreAdapter(storagePort);
        TacticRecord record = TacticRecord.builder()
                .tacticId("tactic-1")
                .title("Use safer retries")
                .updatedAt(Instant.parse("2026-04-01T12:00:00Z"))
                .build();

        adapter.save(record);

        String persisted = storagePort.text.get(key("self-evolving", "tactics/tactic-1.json"));
        assertTrue(persisted.contains("\"tacticId\":\"tactic-1\""));

        adapter.delete("tactic-1");

        assertTrue(storagePort.text.isEmpty());
    }

    @Test
    void shouldWrapPersistenceFailures() {
        InMemoryStoragePort storagePort = new InMemoryStoragePort();
        JsonTacticRecordStoreAdapter adapter = new JsonTacticRecordStoreAdapter(storagePort);
        TacticRecord record = TacticRecord.builder().tacticId("tactic-1").build();

        storagePort.failPut = true;
        assertThrows(IllegalStateException.class, () -> adapter.save(record));

        storagePort.failPut = false;
        storagePort.failDelete = true;
        assertThrows(IllegalStateException.class, () -> adapter.delete("tactic-1"));
    }

    private static String key(String directory, String path) {
        return directory + "/" + path;
    }

    private static final class InMemoryStoragePort implements StoragePort {

        private final Map<String, String> text = new HashMap<>();
        private List<String> paths = new ArrayList<>();
        private boolean failList;
        private boolean failPut;
        private boolean failDelete;

        @Override
        public CompletableFuture<Void> putObject(String directory, String path, byte[] content) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> putText(String directory, String path, String content) {
            text.put(key(directory, path), content);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> getObject(String directory, String path) {
            return CompletableFuture.completedFuture(new byte[0]);
        }

        @Override
        public CompletableFuture<String> getText(String directory, String path) {
            return CompletableFuture.completedFuture(text.get(key(directory, path)));
        }

        @Override
        public CompletableFuture<Boolean> exists(String directory, String path) {
            return CompletableFuture.completedFuture(text.containsKey(key(directory, path)));
        }

        @Override
        public CompletableFuture<Void> deleteObject(String directory, String path) {
            if (failDelete) {
                return failedFuture(new RuntimeException("delete failed"));
            }
            text.remove(key(directory, path));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<String>> listObjects(String directory, String prefix) {
            if (failList) {
                return failedFuture(new RuntimeException("list failed"));
            }
            return CompletableFuture.completedFuture(paths);
        }

        @Override
        public CompletableFuture<Void> appendText(String directory, String path, String content) {
            text.merge(key(directory, path), content, String::concat);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> putTextAtomic(String directory, String path, String content, boolean backup) {
            if (failPut) {
                return failedFuture(new RuntimeException("put failed"));
            }
            text.put(key(directory, path), content);
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
