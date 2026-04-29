package me.golemcore.bot.infrastructure.toolloop.repeat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.system.toolloop.repeat.AutonomyWorkKey;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolStateDomain;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseCategory;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseFingerprint;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseLedger;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseLedgerStore;
import me.golemcore.bot.domain.system.toolloop.repeat.ToolUseRecord;
import me.golemcore.bot.port.outbound.StoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON storage adapter for bounded repeat-guard ledgers.
 */
public class JsonToolUseLedgerStore implements ToolUseLedgerStore {

    private static final Logger log = LoggerFactory.getLogger(JsonToolUseLedgerStore.class);
    private static final int SCHEMA_VERSION = 2;
    private static final int MAX_STORED_RECORDS_PER_WORK_ITEM = 500;
    private static final Set<Integer> SUPPORTED_SCHEMA_VERSIONS = Set.of(1, SCHEMA_VERSION);
    private static final Set<String> REPEAT_GUARD_SYNTHETIC_FAILURE_KINDS = Set.of(
            ToolFailureKind.REPEATED_TOOL_USE_BLOCKED.name(),
            ToolFailureKind.REPEAT_GUARD_STOP_TURN.name());

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
                log.warn("Ignored repeat-guard ledger at {} because schema or work key did not match",
                        key.storageFile());
                return Optional.empty();
            }
            return Optional.of(toLedger(stored, ttl));
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("Failed to load repeat-guard ledger at {}: {} ({})",
                    key.storageFile(), e.getClass().getName(), e.getMessage());
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
        ledger.setEnvironmentVersions(stored.environmentVersions());
        Instant cutoff = clock.instant().minus(ttl != null ? ttl : Duration.ZERO);
        for (StoredRecord storedRecord : capStoredRecords(stored.records())) {
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
                ledger.getEnvironmentVersions(),
                0,
                0,
                capStoredRecords(storedRecords));
    }

    private boolean isLoadable(StoredLedger stored, AutonomyWorkKey requestedKey) {
        return stored != null
                && SUPPORTED_SCHEMA_VERSIONS.contains(stored.schemaVersion())
                && stored.workKey() != null
                && stored.workKey().matches(requestedKey);
    }

    private boolean isExpired(ToolUseRecord entry, Instant cutoff) {
        if (entry == null || entry.fingerprint() == null || entry.finishedAt() == null) {
            return false;
        }
        if (!entry.finishedAt().isBefore(cutoff)) {
            return false;
        }
        if (entry.failureKind() != null && REPEAT_GUARD_SYNTHETIC_FAILURE_KINDS.contains(entry.failureKind())) {
            return true;
        }
        ToolUseCategory category = entry.fingerprint().category();
        return switch (category) {
        case OBSERVE, POLL, EXECUTE_UNKNOWN, CONTROL -> true;
        case MUTATE_IDEMPOTENT, MUTATE_NON_IDEMPOTENT -> entry.guardBlocked();
        };
    }

    private List<StoredRecord> capStoredRecords(List<StoredRecord> records) {
        if (records == null || records.size() <= MAX_STORED_RECORDS_PER_WORK_ITEM) {
            return records == null ? List.of() : List.copyOf(records);
        }
        List<StoredRecord> sorted = new ArrayList<>(records);
        sorted.sort(Comparator.comparing(
                StoredRecord::finishedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        return List.copyOf(sorted.subList(
                sorted.size() - MAX_STORED_RECORDS_PER_WORK_ITEM,
                sorted.size()));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoredLedger(
            int schemaVersion,
            StoredWorkKey workKey,
            Instant savedAt,
            int environmentVersion,
            Map<ToolStateDomain, Integer> environmentVersions,
            int blockedRepeatCount,
            int warnedRepeatCount,
            List<StoredRecord> records) {

        private StoredLedger {
            environmentVersions = environmentVersions != null ? Map.copyOf(environmentVersions) : Map.of();
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
            // scheduleId is audit-only: a task ledger intentionally survives schedule replacement.
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
            String stableKey,
            Set<ToolStateDomain> observedDomains,
            Set<ToolStateDomain> invalidatedDomains) {

        static StoredFingerprint from(ToolUseFingerprint fingerprint) {
            return new StoredFingerprint(
                    fingerprint.toolName(),
                    fingerprint.category(),
                    fingerprint.canonicalArgumentsHash(),
                    fingerprint.stableKey(),
                    fingerprint.observedDomains(),
                    fingerprint.invalidatedDomains());
        }

        ToolUseFingerprint toFingerprint() {
            return new ToolUseFingerprint(
                    toolName,
                    category,
                    canonicalArgumentsHash,
                    stableKey,
                    null,
                    observedDomains,
                    invalidatedDomains);
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
            Map<ToolStateDomain, Integer> environmentVersions,
            boolean guardBlocked,
            String decisionReason) {

        private StoredRecord {
            environmentVersions = environmentVersions != null ? Map.copyOf(environmentVersions) : Map.of();
        }

        static StoredRecord from(ToolUseRecord entry) {
            return new StoredRecord(
                    StoredFingerprint.from(entry.fingerprint()),
                    entry.startedAt(),
                    entry.finishedAt(),
                    entry.success(),
                    entry.failureKind(),
                    entry.outputDigest(),
                    entry.environmentVersion(),
                    entry.environmentVersions(),
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
                    environmentVersions,
                    guardBlocked,
                    decisionReason);
        }
    }
}
