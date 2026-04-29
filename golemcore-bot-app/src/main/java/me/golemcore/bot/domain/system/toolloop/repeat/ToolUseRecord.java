package me.golemcore.bot.domain.system.toolloop.repeat;

import java.time.Instant;
import java.util.Map;

/**
 * Bounded ledger entry for a tool call result or synthetic guard outcome.
 */
// @formatter:off
public record ToolUseRecord(
        ToolUseFingerprint fingerprint,
        Instant startedAt,
        Instant finishedAt,
        boolean success,
        String failureKind,
        String outputDigest,
        int environmentVersion,
        Map<ToolStateDomain, Integer> environmentVersions,
        boolean guardBlocked,
        String decisionReason) {

    public ToolUseRecord {
        environmentVersions = environmentVersions != null ? Map.copyOf(environmentVersions) : Map.of();
    }

    public ToolUseRecord(
            ToolUseFingerprint fingerprint,
            Instant startedAt,
            Instant finishedAt,
            boolean success,
            String failureKind,
            String outputDigest,
            int environmentVersion,
            boolean guardBlocked,
            String decisionReason) {
        this(
                fingerprint,
                startedAt,
                finishedAt,
                success,
                failureKind,
                outputDigest,
                environmentVersion,
                Map.of(),
                guardBlocked,
                decisionReason);
    }

    public ToolUseRecord withEnvironmentVersion(int version) {
        return withEnvironmentSnapshot(version, environmentVersions);
    }

    public ToolUseRecord withEnvironmentSnapshot(int version, Map<ToolStateDomain, Integer> domainVersions) {
        return new ToolUseRecord(
                fingerprint,
                startedAt,
                finishedAt,
                success,
                failureKind,
                outputDigest,
                version,
                domainVersions,
                guardBlocked,
                decisionReason);
    }
}
// @formatter:on
