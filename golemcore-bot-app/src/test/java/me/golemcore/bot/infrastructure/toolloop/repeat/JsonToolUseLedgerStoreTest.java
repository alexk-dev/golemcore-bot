package me.golemcore.bot.infrastructure.toolloop.repeat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import me.golemcore.bot.domain.system.toolloop.repeat.AutonomyWorkKey;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseCategory;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseFingerprint;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseLedger;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseRecord;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonToolUseLedgerStoreTest {

    private static final Instant NOW = Instant.parse("2026-04-28T12:00:00Z");
    private static final ToolUseFingerprint READ_FINGERPRINT = new ToolUseFingerprint(
            "filesystem",
            ToolUseCategory.OBSERVE,
            "sha256:read",
            "filesystem:OBSERVE:sha256:read",
            "{\"path\":\"secret.txt\",\"token\":\"<redacted>\"}");
    private static final AutonomyWorkKey WORK_KEY = new AutonomyWorkKey(
            "web:chat-1", "goal-1", "task-1", "schedule-a");

    private final InMemoryStoragePort storagePort = new InMemoryStoragePort();
    private final JsonToolUseLedgerStore store = new JsonToolUseLedgerStore(
            storagePort, new ObjectMapper().findAndRegisterModules(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void savesAndLoadsLedgerByAutonomyWorkKey() {
        ToolUseLedger ledger = new ToolUseLedger();
        ledger.recordUse(successfulRead(NOW.minusSeconds(10), "sha256:output"));

        store.save(WORK_KEY, ledger);
        Optional<ToolUseLedger> loaded = store.load(WORK_KEY, Duration.ofMinutes(120));

        assertTrue(loaded.isPresent());
        assertEquals(1, loaded.orElseThrow().recordsFor(READ_FINGERPRINT).size());
        assertTrue(storagePort.contains("auto", "tool-ledgers/web_chat-1/tasks/task-1.json"));
    }

    @Test
    void writesAtomically() {
        ToolUseLedger ledger = new ToolUseLedger();

        store.save(WORK_KEY, ledger);

        assertEquals(List.of("auto/tool-ledgers/web_chat-1/tasks/task-1.json"), storagePort.atomicWrites());
    }

    @Test
    void prunesExpiredObservationRecordsOnLoad() {
        ToolUseLedger ledger = new ToolUseLedger();
        ledger.recordUse(successfulRead(NOW.minus(Duration.ofMinutes(121)), "sha256:old"));
        ledger.recordUse(successfulRead(NOW.minus(Duration.ofMinutes(5)), "sha256:new"));
        store.save(WORK_KEY, ledger);

        ToolUseLedger loaded = store.load(WORK_KEY, Duration.ofMinutes(120)).orElseThrow();

        assertEquals(1, loaded.recordsFor(READ_FINGERPRINT).size());
        assertEquals("sha256:new", loaded.recordsFor(READ_FINGERPRINT).getFirst().outputDigest());
    }

    @Test
    void doesNotPersistRawSecretsOrLargeOutputs() {
        ToolUseLedger ledger = new ToolUseLedger();
        ledger.recordUse(successfulRead(NOW, "sha256:digest-only"));

        store.save(WORK_KEY, ledger);
        String json = storagePort.get("auto", "tool-ledgers/web_chat-1/tasks/task-1.json");

        assertTrue(json.contains("sha256:digest-only"));
        assertFalse(json.contains("raw-secret-token"));
        assertFalse(json.contains("very large raw output"));
        assertFalse(json.contains("secret.txt"));
    }

    @Test
    void returnsEmptyForNullMissingBlankOrMalformedLedger() {
        assertTrue(store.load(null, Duration.ofMinutes(120)).isEmpty());
        assertTrue(store.load(WORK_KEY, Duration.ofMinutes(120)).isEmpty());

        storagePort.putText("auto", "tool-ledgers/web_chat-1/tasks/task-1.json", "   ").join();
        assertTrue(store.load(WORK_KEY, Duration.ofMinutes(120)).isEmpty());

        storagePort.putText("auto", "tool-ledgers/web_chat-1/tasks/task-1.json", "{not-json").join();
        assertTrue(store.load(WORK_KEY, Duration.ofMinutes(120)).isEmpty());
    }

    @Test
    void saveIgnoresNullInputs() {
        store.save(null, new ToolUseLedger());
        store.save(WORK_KEY, null);

        assertTrue(storagePort.atomicWrites().isEmpty());
    }

    @Test
    void nullTtlKeepsFreshRecordsAndPrunesOnlyOlderObservationsAtCurrentInstant() {
        ToolUseLedger ledger = new ToolUseLedger();
        ToolUseFingerprint mutationFingerprint = new ToolUseFingerprint(
                "filesystem",
                ToolUseCategory.MUTATE_IDEMPOTENT,
                "sha256:write",
                "filesystem:MUTATE_IDEMPOTENT:sha256:write",
                null);
        ledger.recordUse(successfulRead(NOW.minusSeconds(1), "sha256:old-observation"));
        ledger.recordUse(new ToolUseRecord(
                mutationFingerprint,
                NOW.minusSeconds(10),
                NOW.minusSeconds(10),
                true,
                null,
                "sha256:mutation",
                0,
                false,
                null));
        store.save(WORK_KEY, ledger);

        ToolUseLedger loaded = store.load(WORK_KEY, null).orElseThrow();

        assertEquals(0, loaded.recordsFor(READ_FINGERPRINT).size());
        assertEquals(1, loaded.recordsFor(mutationFingerprint).size());
    }

    private ToolUseRecord successfulRead(Instant finishedAt, String outputDigest) {
        return new ToolUseRecord(
                READ_FINGERPRINT,
                finishedAt.minusMillis(50),
                finishedAt,
                true,
                null,
                outputDigest,
                0,
                false,
                null);
    }

    private static final class InMemoryStoragePort implements StoragePort {

        private final Map<String, String> files = new ConcurrentHashMap<>();
        private final List<String> atomicWritePaths = new ArrayList<>();

        boolean contains(String directory, String path) {
            return files.containsKey(key(directory, path));
        }

        String get(String directory, String path) {
            return files.get(key(directory, path));
        }

        List<String> atomicWrites() {
            return List.copyOf(atomicWritePaths);
        }

        @Override
        public CompletableFuture<Void> putObject(String directory, String path, byte[] content) {
            files.put(key(directory, path), new String(content, java.nio.charset.StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> putText(String directory, String path, String content) {
            files.put(key(directory, path), content);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> getObject(String directory, String path) {
            return CompletableFuture.completedFuture(files.get(key(directory, path))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public CompletableFuture<String> getText(String directory, String path) {
            return CompletableFuture.completedFuture(files.get(key(directory, path)));
        }

        @Override
        public CompletableFuture<Boolean> exists(String directory, String path) {
            return CompletableFuture.completedFuture(files.containsKey(key(directory, path)));
        }

        @Override
        public CompletableFuture<Void> deleteObject(String directory, String path) {
            files.remove(key(directory, path));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<String>> listObjects(String directory, String prefix) {
            return CompletableFuture.completedFuture(files.keySet().stream()
                    .filter(key -> key.startsWith(directory + "/" + prefix))
                    .map(key -> key.substring(directory.length() + 1))
                    .toList());
        }

        @Override
        public CompletableFuture<Void> appendText(String directory, String path, String content) {
            files.merge(key(directory, path), content, String::concat);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> putTextAtomic(String directory, String path, String content, boolean backup) {
            atomicWritePaths.add(directory + "/" + path);
            files.put(key(directory, path), content);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> ensureDirectory(String directory) {
            return CompletableFuture.completedFuture(null);
        }

        private String key(String directory, String path) {
            return directory + "/" + path;
        }
    }
}
