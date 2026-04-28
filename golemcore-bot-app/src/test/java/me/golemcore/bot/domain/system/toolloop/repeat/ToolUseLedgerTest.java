package me.golemcore.bot.domain.system.toolloop.repeat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ToolUseLedgerTest {

    private static final Instant NOW = Instant.parse("2026-04-28T12:00:00Z");

    @Test
    void recordsSuccessfulToolUseByFingerprint() {
        ToolUseLedger ledger = new ToolUseLedger();
        ToolUseFingerprint fingerprint = fingerprint(ToolUseCategory.OBSERVE);

        ledger.record(record(fingerprint, true, false));

        assertEquals(1, ledger.recordsFor(fingerprint).size());
        assertEquals(1, ledger.repeatCountInCurrentEnvironment(fingerprint));
    }

    @Test
    void incrementsEnvironmentVersionAfterMutation() {
        ToolUseLedger ledger = new ToolUseLedger();

        ledger.record(record(fingerprint(ToolUseCategory.MUTATE_IDEMPOTENT), true, false));

        assertEquals(1, ledger.getEnvironmentVersion());
    }

    @Test
    void doesNotIncrementEnvironmentVersionAfterObservation() {
        ToolUseLedger ledger = new ToolUseLedger();

        ledger.record(record(fingerprint(ToolUseCategory.OBSERVE), true, false));

        assertEquals(0, ledger.getEnvironmentVersion());
    }

    @Test
    void returnsOnlyRecordsForSameEnvironmentVersionWhenAskedForUnchangedRepeats() {
        ToolUseLedger ledger = new ToolUseLedger();
        ToolUseFingerprint observation = fingerprint(ToolUseCategory.OBSERVE);
        ledger.record(record(observation, true, false));
        ledger.record(record(fingerprint(ToolUseCategory.MUTATE_IDEMPOTENT), true, false));
        ledger.record(record(observation, true, false));

        assertEquals(1, ledger.recordsForCurrentEnvironment(observation).size());
        assertEquals(1, ledger.repeatCountInCurrentEnvironment(observation));
    }

    @Test
    void tracksBlockedRepeatCountSeparatelyFromActualToolRecords() {
        ToolUseLedger ledger = new ToolUseLedger();
        ToolUseFingerprint fingerprint = fingerprint(ToolUseCategory.OBSERVE);

        ledger.record(record(fingerprint, true, false));
        ledger.record(record(fingerprint, false, true));
        ledger.incrementBlockedRepeatCount();

        assertEquals(1, ledger.getBlockedRepeatCount());
        assertEquals(2, ledger.recordsFor(fingerprint).size());
    }

    private ToolUseRecord record(ToolUseFingerprint fingerprint, boolean success, boolean guardBlocked) {
        return new ToolUseRecord(
                fingerprint,
                NOW,
                NOW.plusMillis(5),
                success,
                success ? null : "REPEATED_TOOL_USE_BLOCKED",
                "sha256:output",
                0,
                guardBlocked,
                guardBlocked ? "blocked" : "allowed");
    }

    private ToolUseFingerprint fingerprint(ToolUseCategory category) {
        return new ToolUseFingerprint("filesystem", category, "sha256:abc", "filesystem:" + category + ":abc", "{}");
    }
}
