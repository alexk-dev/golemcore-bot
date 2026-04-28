package me.golemcore.bot.domain.system.toolloop.repeat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable per-turn tool-use ledger.
 *
 * <p>
 * This class is intentionally not thread-safe; it follows the same single-turn
 * threading model as TurnState.
 */
public class ToolUseLedger {

    private static final int DEFAULT_MAX_RECORDS_PER_FINGERPRINT = 20;

    private final Map<String, List<ToolUseRecord>> recordsByFingerprint = new LinkedHashMap<>();
    private final int maxRecordsPerFingerprint;
    private int environmentVersion;
    private int blockedRepeatCount;
    private int warnedRepeatCount;

    public ToolUseLedger() {
        this(DEFAULT_MAX_RECORDS_PER_FINGERPRINT);
    }

    public ToolUseLedger(int maxRecordsPerFingerprint) {
        this.maxRecordsPerFingerprint = Math.max(1, maxRecordsPerFingerprint);
    }

    public void record(ToolUseRecord record) {
        if (record == null || record.fingerprint() == null || record.fingerprint().stableKey() == null) {
            return;
        }
        ToolUseRecord stored = record.withEnvironmentVersion(environmentVersion);
        List<ToolUseRecord> records = recordsByFingerprint.computeIfAbsent(record.fingerprint().stableKey(),
                ignored -> new ArrayList<>());
        records.add(stored);
        while (records.size() > maxRecordsPerFingerprint) {
            records.remove(0);
        }
        if (isStateChangingSuccess(record)) {
            environmentVersion++;
        }
    }

    public void restore(ToolUseRecord record) {
        if (record == null || record.fingerprint() == null || record.fingerprint().stableKey() == null) {
            return;
        }
        List<ToolUseRecord> records = recordsByFingerprint.computeIfAbsent(record.fingerprint().stableKey(),
                ignored -> new ArrayList<>());
        records.add(record);
        while (records.size() > maxRecordsPerFingerprint) {
            records.remove(0);
        }
    }

    public List<ToolUseRecord> recordsFor(ToolUseFingerprint fingerprint) {
        if (fingerprint == null || fingerprint.stableKey() == null) {
            return List.of();
        }
        return Collections.unmodifiableList(recordsByFingerprint.getOrDefault(fingerprint.stableKey(), List.of()));
    }

    public List<ToolUseRecord> recordsForCurrentEnvironment(ToolUseFingerprint fingerprint) {
        return recordsFor(fingerprint).stream()
                .filter(record -> record.environmentVersion() == environmentVersion)
                .toList();
    }

    public int repeatCountInCurrentEnvironment(ToolUseFingerprint fingerprint) {
        return recordsForCurrentEnvironment(fingerprint).size();
    }

    public Map<String, List<ToolUseRecord>> snapshotRecords() {
        Map<String, List<ToolUseRecord>> snapshot = new LinkedHashMap<>();
        recordsByFingerprint.forEach((key, value) -> snapshot.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(snapshot);
    }

    public int getEnvironmentVersion() {
        return environmentVersion;
    }

    public void setEnvironmentVersion(int environmentVersion) {
        this.environmentVersion = Math.max(0, environmentVersion);
    }

    public int getBlockedRepeatCount() {
        return blockedRepeatCount;
    }

    public int incrementBlockedRepeatCount() {
        blockedRepeatCount++;
        return blockedRepeatCount;
    }

    public void setBlockedRepeatCount(int blockedRepeatCount) {
        this.blockedRepeatCount = Math.max(0, blockedRepeatCount);
    }

    public int getWarnedRepeatCount() {
        return warnedRepeatCount;
    }

    public int incrementWarnedRepeatCount() {
        warnedRepeatCount++;
        return warnedRepeatCount;
    }

    public void setWarnedRepeatCount(int warnedRepeatCount) {
        this.warnedRepeatCount = Math.max(0, warnedRepeatCount);
    }

    private boolean isStateChangingSuccess(ToolUseRecord record) {
        if (!record.success() || record.guardBlocked() || record.fingerprint() == null) {
            return false;
        }
        return switch (record.fingerprint().category()) {
        case MUTATE_IDEMPOTENT, MUTATE_NON_IDEMPOTENT -> true;
        case OBSERVE, POLL, EXECUTE_UNKNOWN, CONTROL -> false;
        };
    }
}
