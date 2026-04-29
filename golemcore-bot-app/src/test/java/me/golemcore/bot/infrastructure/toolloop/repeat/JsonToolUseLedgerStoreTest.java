package me.golemcore.bot.infrastructure.toolloop.repeat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import me.golemcore.bot.domain.system.toolloop.repeat.AutonomyWorkKey;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolStateDomain;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseCategory;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseFingerprint;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseLedger;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseRecord;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
    private static final ToolUseFingerprint WORKSPACE_READ_FINGERPRINT = new ToolUseFingerprint(
            "filesystem",
            ToolUseCategory.OBSERVE,
            "sha256:workspace-read",
            "filesystem:OBSERVE:sha256:workspace-read",
            null,
            Set.of(ToolStateDomain.WORKSPACE),
            Set.of());
    private static final ToolUseFingerprint MEMORY_WRITE_FINGERPRINT = new ToolUseFingerprint(
            "memory",
            ToolUseCategory.MUTATE_IDEMPOTENT,
            "sha256:memory-write",
            "memory:MUTATE_IDEMPOTENT:sha256:memory-write",
            null,
            Set.of(ToolStateDomain.MEMORY),
            Set.of(ToolStateDomain.MEMORY));
    private static final ToolUseFingerprint SHELL_FINGERPRINT = new ToolUseFingerprint(
            "shell",
            ToolUseCategory.EXECUTE_UNKNOWN,
            "sha256:shell",
            "shell:EXECUTE_UNKNOWN:sha256:shell",
            "{\"command\":\"git status\"}");
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
        assertTrue(storagePort.contains("auto", WORK_KEY.storageFile()));
    }

    @Test
    void writesAtomically() {
        ToolUseLedger ledger = new ToolUseLedger();

        store.save(WORK_KEY, ledger);

        assertEquals(List.of(WORK_KEY.storagePath()), storagePort.atomicWrites());
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
    void loadPrunesExpiredExecuteUnknownRecordsByAutoLedgerTtl() {
        ToolUseLedger ledger = new ToolUseLedger();
        ledger.recordUse(successfulShell(NOW.minus(Duration.ofMinutes(121)), "sha256:old-shell"));
        ledger.recordUse(successfulShell(NOW.minus(Duration.ofMinutes(5)), "sha256:new-shell"));
        store.save(WORK_KEY, ledger);

        ToolUseLedger loaded = store.load(WORK_KEY, Duration.ofMinutes(120)).orElseThrow();

        assertEquals(1, loaded.recordsFor(SHELL_FINGERPRINT).size());
        assertEquals("sha256:new-shell", loaded.recordsFor(SHELL_FINGERPRINT).getFirst().outputDigest());
    }

    @Test
    void repeatGuardStopTurnSyntheticMutationExpiresByTtl() {
        ToolUseLedger ledger = new ToolUseLedger();
        ledger.restore(repeatGuardStopMutation(NOW.minus(Duration.ofMinutes(121))));
        store.save(WORK_KEY, ledger);

        ToolUseLedger loaded = store.load(WORK_KEY, Duration.ofMinutes(120)).orElseThrow();

        assertEquals(0, loaded.recordsFor(MEMORY_WRITE_FINGERPRINT).size());
    }

    @Test
    void savesAndLoadsDomainScopedEnvironmentVersions() {
        ToolUseLedger ledger = new ToolUseLedger();
        ledger.recordUse(successfulWorkspaceRead(NOW.minusSeconds(10), "sha256:read"));
        ledger.recordUse(successfulMemoryWrite(NOW.minusSeconds(5)));
        store.save(WORK_KEY, ledger);

        ToolUseLedger loaded = store.load(WORK_KEY, Duration.ofMinutes(120)).orElseThrow();

        assertEquals(1, loaded.getEnvironmentVersions().get(ToolStateDomain.MEMORY));
        assertEquals(1, loaded.recordsForCurrentEnvironment(WORKSPACE_READ_FINGERPRINT).size());
    }

    @Test
    void loadDoesNotRestorePerTurnCounters() {
        ToolUseLedger ledger = new ToolUseLedger();
        ledger.incrementBlockedRepeatCount();
        ledger.incrementWarnedRepeatCount();

        store.save(WORK_KEY, ledger);
        ToolUseLedger loaded = store.load(WORK_KEY, Duration.ofMinutes(120)).orElseThrow();

        assertEquals(0, loaded.getBlockedRepeatCount());
        assertEquals(0, loaded.getWarnedRepeatCount());
    }

    @Test
    void pruningExpiredRecordsAlsoDropsPerTurnCounters() {
        ToolUseLedger ledger = new ToolUseLedger();
        ledger.recordUse(successfulRead(NOW.minus(Duration.ofMinutes(121)), "sha256:old"));
        ledger.incrementBlockedRepeatCount();
        ledger.incrementWarnedRepeatCount();

        store.save(WORK_KEY, ledger);
        ToolUseLedger loaded = store.load(WORK_KEY, Duration.ofMinutes(120)).orElseThrow();

        assertEquals(0, loaded.recordsFor(READ_FINGERPRINT).size());
        assertEquals(0, loaded.getBlockedRepeatCount());
        assertEquals(0, loaded.getWarnedRepeatCount());
    }

    @Test
    void doesNotPersistRawSecretsOrLargeOutputs() {
        ToolUseLedger ledger = new ToolUseLedger();
        ledger.recordUse(successfulRead(NOW, "sha256:digest-only"));
        ledger.recordUse(new ToolUseRecord(
                READ_FINGERPRINT,
                NOW,
                NOW,
                true,
                null,
                "sha256:output-only",
                0,
                false,
                "raw-secret-token inside very large raw output"));

        store.save(WORK_KEY, ledger);
        String json = storagePort.get("auto", WORK_KEY.storageFile());

        assertTrue(json.contains("sha256:digest-only"));
        assertFalse(json.contains("raw-secret-token"));
        assertFalse(json.contains("very large raw output"));
        assertFalse(json.contains("secret.txt"));
    }

    @Test
    void returnsEmptyForNullMissingBlankOrMalformedLedger() {
        assertTrue(store.load(null, Duration.ofMinutes(120)).isEmpty());
        assertTrue(store.load(WORK_KEY, Duration.ofMinutes(120)).isEmpty());

        storagePort.putText("auto", WORK_KEY.storageFile(), "   ").join();
        assertTrue(store.load(WORK_KEY, Duration.ofMinutes(120)).isEmpty());

        storagePort.putText("auto", WORK_KEY.storageFile(), "{not-json").join();
        assertTrue(store.load(WORK_KEY, Duration.ofMinutes(120)).isEmpty());
    }

    @Test
    void malformedLedgerLoadEmitsDiagnosticAndFallsBackToEmptyLedger() {
        Logger logger = (Logger) LoggerFactory.getLogger(JsonToolUseLedgerStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        storagePort.putText("auto", WORK_KEY.storageFile(), "{not-json").join();

        try {
            assertTrue(store.load(WORK_KEY, Duration.ofMinutes(120)).isEmpty());
        } finally {
            logger.detachAppender(appender);
        }

        assertTrue(appender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("Failed to load repeat-guard ledger")
                        && event.getFormattedMessage().contains(WORK_KEY.storageFile())));
    }

    @Test
    void loadRejectsLedgerWithMismatchedWorkKey() {
        ToolUseLedger ledger = new ToolUseLedger();
        ledger.recordUse(successfulRead(NOW, "sha256:output"));
        AutonomyWorkKey otherKey = new AutonomyWorkKey("web:other", "goal-1", "task-1", null);
        store.save(otherKey, ledger);
        storagePort.putText("auto", WORK_KEY.storageFile(), storagePort.get("auto", otherKey.storageFile())).join();

        assertTrue(store.load(WORK_KEY, Duration.ofMinutes(120)).isEmpty());
    }

    @Test
    void loadRejectsUnsupportedSchemaVersion() {
        ToolUseLedger ledger = new ToolUseLedger();
        store.save(WORK_KEY, ledger);
        String json = storagePort.get("auto", WORK_KEY.storageFile())
                .replace("\"schemaVersion\" : 2", "\"schemaVersion\" : 999");
        storagePort.putText("auto", WORK_KEY.storageFile(), json).join();

        assertTrue(store.load(WORK_KEY, Duration.ofMinutes(120)).isEmpty());
    }

    @Test
    void saveWritesCurrentSchemaVersionTwo() {
        store.save(WORK_KEY, new ToolUseLedger());

        assertTrue(storagePort.get("auto", WORK_KEY.storageFile()).contains("\"schemaVersion\" : 2"));
    }

    @Test
    void loadCapsTotalStoredRecordsPerWorkItem() {
        ToolUseLedger ledger = new ToolUseLedger();
        for (int index = 0; index < 520; index++) {
            ToolUseFingerprint fingerprint = new ToolUseFingerprint(
                    "tool-" + index,
                    ToolUseCategory.EXECUTE_UNKNOWN,
                    "sha256:" + index,
                    "tool-" + index + ":EXECUTE_UNKNOWN:sha256:" + index,
                    null);
            ledger.recordUse(new ToolUseRecord(
                    fingerprint,
                    NOW.plusSeconds(index),
                    NOW.plusSeconds(index),
                    true,
                    null,
                    "sha256:out-" + index,
                    0,
                    false,
                    null));
        }

        store.save(WORK_KEY, ledger);
        ToolUseLedger loaded = store.load(WORK_KEY, Duration.ofMinutes(120)).orElseThrow();

        assertEquals(500, loaded.snapshotRecords().values().stream().mapToInt(List::size).sum());
        assertEquals(1, loaded.recordsFor(new ToolUseFingerprint(
                "tool-519",
                ToolUseCategory.EXECUTE_UNKNOWN,
                "sha256:519",
                "tool-519:EXECUTE_UNKNOWN:sha256:519",
                null)).size());
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

    private ToolUseRecord successfulWorkspaceRead(Instant finishedAt, String outputDigest) {
        return new ToolUseRecord(
                WORKSPACE_READ_FINGERPRINT,
                finishedAt.minusMillis(50),
                finishedAt,
                true,
                null,
                outputDigest,
                0,
                false,
                null);
    }

    private ToolUseRecord successfulMemoryWrite(Instant finishedAt) {
        return new ToolUseRecord(
                MEMORY_WRITE_FINGERPRINT,
                finishedAt.minusMillis(50),
                finishedAt,
                true,
                null,
                "sha256:memory-write",
                0,
                false,
                null);
    }

    private ToolUseRecord repeatGuardStopMutation(Instant finishedAt) {
        return new ToolUseRecord(
                MEMORY_WRITE_FINGERPRINT,
                finishedAt.minusMillis(50),
                finishedAt,
                false,
                ToolFailureKind.REPEAT_GUARD_STOP_TURN.name(),
                "sha256:stop",
                0,
                false,
                "stopped");
    }

    private ToolUseRecord successfulShell(Instant finishedAt, String outputDigest) {
        return new ToolUseRecord(
                SHELL_FINGERPRINT,
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
