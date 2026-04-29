package me.golemcore.bot.domain.system.toolloop.repeat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ToolUseLedgerTest {

    private static final Instant NOW = Instant.parse("2026-04-28T12:00:00Z");

    @Test
    void recordsSuccessfulToolUseByFingerprint() {
        ToolUseLedger ledger = new ToolUseLedger();
        ToolUseFingerprint fingerprint = fingerprint(ToolUseCategory.OBSERVE);

        ledger.recordUse(toolUseRecord(fingerprint, true, false));

        assertEquals(1, ledger.recordsFor(fingerprint).size());
        assertEquals(1, ledger.repeatCountInCurrentEnvironment(fingerprint));
    }

    @Test
    void incrementsEnvironmentVersionAfterMutation() {
        ToolUseLedger ledger = new ToolUseLedger();

        ledger.recordUse(toolUseRecord(fingerprint(ToolUseCategory.MUTATE_IDEMPOTENT), true, false));

        assertEquals(1, ledger.getEnvironmentVersion());
    }

    @Test
    void recordsSuccessfulMutationInAdvancedEnvironmentSoDuplicatesAreVisible() {
        ToolUseLedger ledger = new ToolUseLedger();
        ToolUseFingerprint mutation = fingerprint(ToolUseCategory.MUTATE_IDEMPOTENT);

        ledger.recordUse(toolUseRecord(mutation, true, false));

        assertEquals(1, ledger.getEnvironmentVersion());
        assertEquals(1, ledger.repeatCountInCurrentEnvironment(mutation));
    }

    @Test
    void successfulUnknownExecutionDoesNotAdvanceEnvironmentVersionWithoutVerifiedStateChange() {
        ToolUseLedger ledger = new ToolUseLedger();

        ledger.recordUse(toolUseRecord(fingerprint(ToolUseCategory.EXECUTE_UNKNOWN), true, false));

        assertEquals(0, ledger.getEnvironmentVersion());
    }

    @Test
    void successfulRepeatCountersIgnoreFailuresAndGuardBlocks() {
        ToolUseLedger ledger = new ToolUseLedger();
        ToolUseFingerprint fingerprint = fingerprint(ToolUseCategory.OBSERVE);

        ledger.recordUse(toolUseRecord(fingerprint, false, false));
        ledger.recordUse(toolUseRecord(fingerprint, false, true));
        ledger.recordUse(toolUseRecord(fingerprint, true, false));

        assertEquals(3, ledger.recordsFor(fingerprint).size());
        assertEquals(1, ledger.successfulRepeatCountInCurrentEnvironment(fingerprint));
        assertEquals(1, ledger.successfulRepeatCount(fingerprint));
    }

    @Test
    void doesNotIncrementEnvironmentVersionAfterObservation() {
        ToolUseLedger ledger = new ToolUseLedger();

        ledger.recordUse(toolUseRecord(fingerprint(ToolUseCategory.OBSERVE), true, false));

        assertEquals(0, ledger.getEnvironmentVersion());
    }

    @Test
    void returnsOnlyRecordsForSameEnvironmentVersionWhenAskedForUnchangedRepeats() {
        ToolUseLedger ledger = new ToolUseLedger();
        ToolUseFingerprint observation = fingerprint(ToolUseCategory.OBSERVE);
        ledger.recordUse(toolUseRecord(observation, true, false));
        ledger.recordUse(toolUseRecord(fingerprint(ToolUseCategory.MUTATE_IDEMPOTENT), true, false));
        ledger.recordUse(toolUseRecord(observation, true, false));

        assertEquals(1, ledger.recordsForCurrentEnvironment(observation).size());
        assertEquals(1, ledger.repeatCountInCurrentEnvironment(observation));
    }

    @Test
    void tracksBlockedRepeatCountSeparatelyFromActualToolRecords() {
        ToolUseLedger ledger = new ToolUseLedger();
        ToolUseFingerprint fingerprint = fingerprint(ToolUseCategory.OBSERVE);

        ledger.recordUse(toolUseRecord(fingerprint, true, false));
        ledger.recordUse(toolUseRecord(fingerprint, false, true));
        ledger.incrementBlockedRepeatCount();

        assertEquals(1, ledger.getBlockedRepeatCount());
        assertEquals(2, ledger.recordsFor(fingerprint).size());
    }

    @Test
    void ignoresInvalidEntriesAndReturnsEmptyRecordsForMissingFingerprint() {
        ToolUseLedger ledger = new ToolUseLedger();

        ledger.recordUse(null);
        ledger.recordUse(new ToolUseRecord(null, NOW, NOW, true, null, null, 0, false, null));

        assertEquals(0, ledger.snapshotRecords().size());
        assertEquals(0, ledger.recordsFor(null).size());
        assertEquals(0, ledger.recordsFor(new ToolUseFingerprint("tool", ToolUseCategory.OBSERVE, "hash", null, "{}"))
                .size());
    }

    @Test
    void capsEntriesPerFingerprintAndDefensivelyExposesSnapshot() {
        ToolUseLedger ledger = new ToolUseLedger(1);
        ToolUseFingerprint fingerprint = fingerprint(ToolUseCategory.OBSERVE);

        ledger.recordUse(toolUseRecord(fingerprint, true, false));
        ledger.recordUse(new ToolUseRecord(
                fingerprint, NOW.plusSeconds(1), NOW.plusSeconds(1), true, null, "sha256:new", 0, false, "allowed"));

        assertEquals(1, ledger.recordsFor(fingerprint).size());
        assertEquals("sha256:new", ledger.recordsFor(fingerprint).getFirst().outputDigest());
        assertThrows(UnsupportedOperationException.class,
                () -> ledger.snapshotRecords().put("other", java.util.List.of()));
    }

    @Test
    void failedOrGuardBlockedMutationsDoNotAdvanceEnvironmentVersion() {
        ToolUseLedger ledger = new ToolUseLedger();
        ToolUseFingerprint mutation = fingerprint(ToolUseCategory.MUTATE_NON_IDEMPOTENT);

        ledger.recordUse(toolUseRecord(mutation, false, false));
        ledger.recordUse(toolUseRecord(mutation, true, true));

        assertEquals(0, ledger.getEnvironmentVersion());
    }

    @Test
    void settersClampNegativeCountersAndEnvironmentVersion() {
        ToolUseLedger ledger = new ToolUseLedger();

        ledger.setEnvironmentVersion(-1);
        ledger.setBlockedRepeatCount(-1);
        ledger.setWarnedRepeatCount(-1);

        assertEquals(0, ledger.getEnvironmentVersion());
        assertEquals(0, ledger.getBlockedRepeatCount());
        assertEquals(0, ledger.getWarnedRepeatCount());
    }

    private ToolUseRecord toolUseRecord(ToolUseFingerprint fingerprint, boolean success, boolean guardBlocked) {
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
