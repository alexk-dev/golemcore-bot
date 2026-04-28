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

    public void recordUse(ToolUseRecord entry) {
        if (entry == null || entry.fingerprint() == null || entry.fingerprint().stableKey() == null) {
            return;
        }
        boolean stateChangingSuccess = isStateChangingSuccess(entry);
        if (stateChangingSuccess) {
            environmentVersion++;
        }
        ToolUseRecord stored = entry.withEnvironmentVersion(environmentVersion);
        List<ToolUseRecord> entries = recordsByFingerprint.computeIfAbsent(entry.fingerprint().stableKey(),
                ignored -> new ArrayList<>());
        entries.add(stored);
        while (entries.size() > maxRecordsPerFingerprint) {
            entries.remove(0);
        }
    }

    public void restore(ToolUseRecord entry) {
        if (entry == null || entry.fingerprint() == null || entry.fingerprint().stableKey() == null) {
            return;
        }
        List<ToolUseRecord> entries = recordsByFingerprint.computeIfAbsent(entry.fingerprint().stableKey(),
                ignored -> new ArrayList<>());
        entries.add(entry);
        while (entries.size() > maxRecordsPerFingerprint) {
            entries.remove(0);
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
                .filter(entry -> entry.environmentVersion() == environmentVersion)
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

    private boolean isStateChangingSuccess(ToolUseRecord entry) {
        if (!entry.success() || entry.guardBlocked() || entry.fingerprint() == null) {
            return false;
        }
        return switch (entry.fingerprint().category()) {
        case MUTATE_IDEMPOTENT, MUTATE_NON_IDEMPOTENT -> true;
        case OBSERVE, POLL, EXECUTE_UNKNOWN, CONTROL -> false;
        };
    }
}
