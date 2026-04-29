package me.golemcore.bot.domain.system.toolloop.repeat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Mutable per-turn tool-use ledger.
 *
 * <p>
 * This class is intentionally not thread-safe; it follows the same single-turn
 * threading model as TurnState.
 */
public class ToolUseLedger {

    private static final int DEFAULT_MAX_RECORDS_PER_FINGERPRINT = 20;
    private static final Set<String> SYNTHETIC_FAILURE_KINDS = Set.of(
            "POLICY_DENIED",
            "CONFIRMATION_DENIED",
            "REPEATED_TOOL_USE_BLOCKED",
            "REPEAT_GUARD_STOP_TURN");

    private final Map<String, List<ToolUseRecord>> recordsByFingerprint = new LinkedHashMap<>();
    private final Map<ToolStateDomain, Integer> environmentVersions = new EnumMap<>(ToolStateDomain.class);
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
            for (ToolStateDomain domain : entry.fingerprint().invalidatedDomains()) {
                environmentVersions.merge(domain, 1, Integer::sum);
            }
        }
        ToolUseRecord stored = entry.withEnvironmentSnapshot(environmentVersion,
                environmentSnapshot(entry.fingerprint()));
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
                .filter(entry -> isCurrentEnvironment(entry, fingerprint))
                .toList();
    }

    public int repeatCountInCurrentEnvironment(ToolUseFingerprint fingerprint) {
        return recordsForCurrentEnvironment(fingerprint).size();
    }

    public int repeatCount(ToolUseFingerprint fingerprint) {
        return recordsFor(fingerprint).size();
    }

    public int successfulRepeatCountInCurrentEnvironment(ToolUseFingerprint fingerprint) {
        return (int) recordsForCurrentEnvironment(fingerprint).stream()
                .filter(this::isSuccessfulActualExecution)
                .count();
    }

    public int successfulRepeatCount(ToolUseFingerprint fingerprint) {
        return (int) recordsFor(fingerprint).stream()
                .filter(this::isSuccessfulActualExecution)
                .count();
    }

    public List<ToolUseRecord> actualAttemptsFor(ToolUseFingerprint fingerprint) {
        return recordsFor(fingerprint).stream()
                .filter(this::isActualAttempt)
                .toList();
    }

    public Optional<Instant> lastActualAttemptAt(ToolUseFingerprint fingerprint) {
        return actualAttemptsFor(fingerprint).stream()
                .map(ToolUseRecord::finishedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo);
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

    public Map<ToolStateDomain, Integer> getEnvironmentVersions() {
        return Collections.unmodifiableMap(environmentVersions);
    }

    public void setEnvironmentVersions(Map<ToolStateDomain, Integer> versions) {
        environmentVersions.clear();
        if (versions == null) {
            return;
        }
        versions.forEach((domain, version) -> {
            if (domain != null && version != null && version > 0) {
                environmentVersions.put(domain, version);
            }
        });
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
        if (!entry.success() || entry.guardBlocked() || entry.fingerprint() == null
                || entry.fingerprint().invalidatedDomains().isEmpty()) {
            return false;
        }
        return isActualAttempt(entry);
    }

    private boolean isSuccessfulActualExecution(ToolUseRecord entry) {
        return entry != null && entry.success() && isActualAttempt(entry);
    }

    private boolean isActualAttempt(ToolUseRecord entry) {
        if (entry == null || entry.guardBlocked()) {
            return false;
        }
        String failureKind = entry.failureKind();
        return failureKind == null || !SYNTHETIC_FAILURE_KINDS.contains(failureKind);
    }

    private boolean isCurrentEnvironment(ToolUseRecord entry, ToolUseFingerprint fingerprint) {
        if (entry == null || fingerprint == null) {
            return false;
        }
        if (fingerprint.observedDomains().isEmpty()) {
            return entry.environmentVersion() == environmentVersion;
        }
        Map<ToolStateDomain, Integer> entryVersions = entry.environmentVersions();
        if (entryVersions.isEmpty()) {
            return entry.environmentVersion() == environmentVersion;
        }
        for (ToolStateDomain domain : fingerprint.observedDomains()) {
            if (!Objects.equals(
                    entryVersions.getOrDefault(domain, 0),
                    environmentVersions.getOrDefault(domain, 0))) {
                return false;
            }
        }
        return true;
    }

    private Map<ToolStateDomain, Integer> environmentSnapshot(ToolUseFingerprint fingerprint) {
        Map<ToolStateDomain, Integer> snapshot = new EnumMap<>(ToolStateDomain.class);
        snapshot.putAll(environmentVersions);
        if (fingerprint != null) {
            fingerprint.observedDomains().forEach(domain -> snapshot.putIfAbsent(domain, 0));
            fingerprint.invalidatedDomains().forEach(domain -> snapshot.putIfAbsent(domain, 0));
        }
        return snapshot.isEmpty() ? Map.of() : Map.copyOf(snapshot);
    }
}
