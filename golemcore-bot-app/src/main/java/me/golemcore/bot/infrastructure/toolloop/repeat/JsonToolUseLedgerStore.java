package me.golemcore.bot.infrastructure.toolloop.repeat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import me.golemcore.bot.domain.system.toolloop.repeat.AutonomyWorkKey;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseCategory;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseFingerprint;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseLedger;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseLedgerStore;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseRecord;
import me.golemcore.bot.port.outbound.StoragePort;

/**
 * JSON storage adapter for bounded repeat-guard ledgers.
 */
public class JsonToolUseLedgerStore implements ToolUseLedgerStore {

    private static final int SCHEMA_VERSION = 1;

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JsonToolUseLedgerStore(StoragePort storagePort, ObjectMapper objectMapper, Clock clock) {
        this.storagePort = Objects.requireNonNull(storagePort);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Optional<ToolUseLedger> load(AutonomyWorkKey key, Duration ttl) {
        if (key == null) {
            return Optional.empty();
        }
        try {
            boolean ledgerExists = Boolean.TRUE
                    .equals(storagePort.exists(key.storageDirectory(), key.storageFile()).join());
            if (!ledgerExists) {
                return Optional.empty();
            }
            String json = storagePort.getText(key.storageDirectory(), key.storageFile()).join();
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            StoredLedger stored = objectMapper.readValue(json, StoredLedger.class);
            if (!isLoadable(stored, key)) {
                return Optional.empty();
            }
            return Optional.of(toLedger(stored, ttl));
        } catch (RuntimeException | java.io.IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void save(AutonomyWorkKey key, ToolUseLedger ledger) {
        if (key == null || ledger == null) {
            return;
        }
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(toStored(key, ledger));
            storagePort.putTextAtomic(key.storageDirectory(), key.storageFile(), json, true).join();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to serialize tool-use ledger", e);
        }
    }

    private ToolUseLedger toLedger(StoredLedger stored, Duration ttl) {
        ToolUseLedger ledger = new ToolUseLedger();
        if (stored == null) {
            return ledger;
        }
        ledger.setEnvironmentVersion(stored.environmentVersion());
        Instant cutoff = clock.instant().minus(ttl != null ? ttl : Duration.ZERO);
        for (StoredRecord storedRecord : stored.records()) {
            ToolUseRecord entry = storedRecord.toRecord();
            if (!isExpired(entry, cutoff)) {
                ledger.restore(entry);
            }
        }
        return ledger;
    }

    private StoredLedger toStored(AutonomyWorkKey key, ToolUseLedger ledger) {
        List<StoredRecord> storedRecords = new ArrayList<>();
        ledger.snapshotRecords().values()
                .forEach(entries -> entries.forEach(entry -> storedRecords.add(StoredRecord.from(entry))));
        return new StoredLedger(
                SCHEMA_VERSION,
                StoredWorkKey.from(key),
                clock.instant(),
                ledger.getEnvironmentVersion(),
                0,
                0,
                storedRecords);
    }

    private boolean isLoadable(StoredLedger stored, AutonomyWorkKey requestedKey) {
        return stored != null
                && stored.schemaVersion() == SCHEMA_VERSION
                && stored.workKey() != null
                && stored.workKey().matches(requestedKey);
    }

    private boolean isExpired(ToolUseRecord entry, Instant cutoff) {
        if (entry == null || entry.fingerprint() == null || entry.finishedAt() == null) {
            return false;
        }
        ToolUseCategory category = entry.fingerprint().category();
        return (category == ToolUseCategory.OBSERVE || category == ToolUseCategory.POLL)
                && entry.finishedAt().isBefore(cutoff);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoredLedger(
            int schemaVersion,
            StoredWorkKey workKey,
            Instant savedAt,
            int environmentVersion,
            int blockedRepeatCount,
            int warnedRepeatCount,
            List<StoredRecord> records) {

        private StoredLedger {
            records = records != null ? List.copyOf(records) : List.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoredWorkKey(String sessionKey, String goalId, String taskId, String scheduleId) {

        static StoredWorkKey from(AutonomyWorkKey key) {
            return new StoredWorkKey(key.sessionKey(), key.goalId(), key.taskId(), key.scheduleId());
        }

        boolean matches(AutonomyWorkKey key) {
            if (key == null) {
                return false;
            }
            return Objects.equals(sessionKey, key.sessionKey())
                    && Objects.equals(goalId, key.goalId())
                    && Objects.equals(taskId, key.taskId());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoredFingerprint(
            String toolName,
            ToolUseCategory category,
            String canonicalArgumentsHash,
            String stableKey) {

        static StoredFingerprint from(ToolUseFingerprint fingerprint) {
            return new StoredFingerprint(
                    fingerprint.toolName(),
                    fingerprint.category(),
                    fingerprint.canonicalArgumentsHash(),
                    fingerprint.stableKey());
        }

        ToolUseFingerprint toFingerprint() {
            return new ToolUseFingerprint(toolName, category, canonicalArgumentsHash, stableKey, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoredRecord(
            StoredFingerprint fingerprint,
            Instant startedAt,
            Instant finishedAt,
            boolean success,
            String failureKind,
            String outputDigest,
            int environmentVersion,
            boolean guardBlocked,
            String decisionReason) {

        static StoredRecord from(ToolUseRecord entry) {
            return new StoredRecord(
                    StoredFingerprint.from(entry.fingerprint()),
                    entry.startedAt(),
                    entry.finishedAt(),
                    entry.success(),
                    entry.failureKind(),
                    entry.outputDigest(),
                    entry.environmentVersion(),
                    entry.guardBlocked(),
                    entry.guardBlocked() ? entry.decisionReason() : null);
        }

        ToolUseRecord toRecord() {
            return new ToolUseRecord(
                    fingerprint.toFingerprint(),
                    startedAt,
                    finishedAt,
                    success,
                    failureKind,
                    outputDigest,
                    environmentVersion,
                    guardBlocked,
                    decisionReason);
        }
    }
}
