package me.golemcore.bot.domain.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HivePolicySyncCommandHandlerTest {

    private HiveManagedPolicyService hiveManagedPolicyService;
    private HivePolicySyncCommandHandler handler;

    @BeforeEach
    void setUp() {
        hiveManagedPolicyService = mock(HiveManagedPolicyService.class);
        handler = new HivePolicySyncCommandHandler(hiveManagedPolicyService);
    }

    @Test
    void shouldRejectNullEnvelope() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> handler.handle(null));

        assertEquals("Hive policy sync command is required", error.getMessage());
    }

    @Test
    void shouldRejectBlankPolicyGroupId() {
        HiveControlCommandEnvelope envelope = HiveControlCommandEnvelope.builder()
                .targetVersion(4)
                .checksum("sha256:abcd")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> handler.handle(envelope));

        assertEquals("Hive policy sync policyGroupId is required", error.getMessage());
    }

    @Test
    void shouldRejectNullTargetVersion() {
        HiveControlCommandEnvelope envelope = HiveControlCommandEnvelope.builder()
                .policyGroupId("pg-1")
                .checksum("sha256:abcd")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> handler.handle(envelope));

        assertEquals("Hive policy sync targetVersion is required", error.getMessage());
    }

    @Test
    void shouldRejectBlankChecksum() {
        HiveControlCommandEnvelope envelope = HiveControlCommandEnvelope.builder()
                .policyGroupId("pg-1")
                .targetVersion(4)
                .checksum(" ")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> handler.handle(envelope));

        assertEquals("Hive policy sync checksum is required", error.getMessage());
    }

    @Test
    void shouldMarkSyncRequestedWhenEnvelopeIsValid() {
        HiveControlCommandEnvelope envelope = HiveControlCommandEnvelope.builder()
                .policyGroupId("pg-1")
                .targetVersion(7)
                .checksum("sha256:abcd")
                .build();

        handler.handle(envelope);

        verify(hiveManagedPolicyService).markSyncRequested("pg-1", 7, "sha256:abcd");
    }
}
