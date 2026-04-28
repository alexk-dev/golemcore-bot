package me.golemcore.bot.domain.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.hive.HivePolicyApplyResult;
import me.golemcore.bot.domain.model.policy.ManagedPolicyBindingState;
import me.golemcore.bot.domain.model.policy.ManagedModelCatalog;
import me.golemcore.bot.domain.model.hive.HivePolicyPackage;
import me.golemcore.bot.port.outbound.ManagedPolicyRuntimeConfigPort;
import me.golemcore.bot.port.outbound.ManagedPolicyStatePort;
import me.golemcore.bot.port.outbound.ModelCatalogAdminPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveManagedPolicyServiceTest {

    private ManagedPolicyRuntimeConfigPort runtimeConfigPort;
    private ModelCatalogAdminPort modelCatalogAdminPort;
    private ManagedPolicyStatePort managedPolicyStatePort;
    private HiveManagedPolicyService service;

    @BeforeEach
    void setUp() {
        runtimeConfigPort = mock(ManagedPolicyRuntimeConfigPort.class);
        modelCatalogAdminPort = mock(ModelCatalogAdminPort.class);
        managedPolicyStatePort = mock(ManagedPolicyStatePort.class);
        service = new HiveManagedPolicyService(
                runtimeConfigPort,
                modelCatalogAdminPort,
                managedPolicyStatePort,
                Clock.fixed(Instant.parse("2026-04-08T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldApplyPolicyPackageAtomicallyAndPersistInSyncState() {
        HivePolicyPackage policyPackage = HivePolicyPackage.builder()
                .policyGroupId("pg-1")
                .targetVersion(4)
                .checksum("sha256:abcd")
                .llmProviders(Map.of("openai", RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of("sk-test"))
                        .apiType("openai")
                        .build()))
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder()
                        .balancedModel("openai/gpt-5.1")
                        .build())
                .modelCatalog(ManagedModelCatalog.builder()
                        .defaultModel("openai/gpt-5.1")
                        .build())
                .build();
        when(runtimeConfigPort.snapshotRuntimeConfig()).thenReturn(RuntimeConfig.builder().build());
        when(modelCatalogAdminPort.getCatalogSnapshot()).thenReturn(ManagedModelCatalog.builder().build());

        HivePolicyApplyResult result = service.applyPolicyPackage(policyPackage);

        assertEquals("IN_SYNC", result.getSyncStatus());
        assertEquals(4, result.getAppliedVersion());
        verify(runtimeConfigPort).replaceManagedPolicySections(any(RuntimeConfig.LlmConfig.class),
                any(RuntimeConfig.ModelRouterConfig.class));
        verify(modelCatalogAdminPort).replaceCatalogSnapshot(policyPackage.getModelCatalog());
        verify(managedPolicyStatePort).save(ManagedPolicyBindingState.builder()
                .policyGroupId("pg-1")
                .targetVersion(4)
                .appliedVersion(4)
                .checksum("sha256:abcd")
                .syncStatus("IN_SYNC")
                .lastAppliedAt(Instant.parse("2026-04-08T12:00:00Z"))
                .lastSyncRequestedAt(Instant.parse("2026-04-08T12:00:00Z"))
                .build());
    }

    @Test
    void shouldReturnExistingStateWhenBindingStateIsPresent() {
        ManagedPolicyBindingState existingState = ManagedPolicyBindingState.builder()
                .policyGroupId("pg-1")
                .targetVersion(2)
                .appliedVersion(1)
                .syncStatus("OUT_OF_SYNC")
                .build();
        when(managedPolicyStatePort.load()).thenReturn(Optional.of(existingState));

        Optional<ManagedPolicyBindingState> result = service.getBindingState();

        assertTrue(result.isPresent());
        assertSame(existingState, result.get());
        assertTrue(service.hasActiveBinding());
        assertTrue(service.isSyncPending());
    }

    @Test
    void shouldReturnFalseForBindingFlagsWhenStateIsMissing() {
        when(managedPolicyStatePort.load()).thenReturn(Optional.empty());

        assertTrue(service.getBindingState().isEmpty());
        assertFalse(service.hasActiveBinding());
        assertFalse(service.isSyncPending());
    }

    @Test
    void shouldClearBindingState() {
        service.clearBinding();

        verify(managedPolicyStatePort).clear();
    }

    @Test
    void shouldRollbackPreviousConfigAndCatalogWhenCatalogPersistFails() {
        RuntimeConfig previousRuntime = RuntimeConfig.builder().build();
        ManagedModelCatalog previousCatalog = ManagedModelCatalog.builder()
                .defaultModel("openai/gpt-4.1")
                .build();
        ManagedPolicyBindingState existingState = ManagedPolicyBindingState.builder()
                .policyGroupId("pg-1")
                .targetVersion(3)
                .appliedVersion(3)
                .checksum("sha256:old")
                .syncStatus("IN_SYNC")
                .build();
        HivePolicyPackage policyPackage = HivePolicyPackage.builder()
                .policyGroupId("pg-1")
                .targetVersion(4)
                .checksum("sha256:new")
                .llmProviders(Map.of("openai", RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of("sk-test"))
                        .apiType("openai")
                        .build()))
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder()
                        .balancedModel("openai/gpt-5.1")
                        .build())
                .modelCatalog(ManagedModelCatalog.builder()
                        .defaultModel("openai/gpt-5.1")
                        .build())
                .build();
        when(runtimeConfigPort.snapshotRuntimeConfig()).thenReturn(previousRuntime);
        when(modelCatalogAdminPort.getCatalogSnapshot()).thenReturn(previousCatalog);
        when(managedPolicyStatePort.load()).thenReturn(Optional.of(existingState));
        doThrow(new IllegalStateException("models write failed"))
                .when(modelCatalogAdminPort).replaceCatalogSnapshot(policyPackage.getModelCatalog());

        HivePolicyApplyResult result = service.applyPolicyPackage(policyPackage);

        assertEquals("APPLY_FAILED", result.getSyncStatus());
        assertEquals(3, result.getAppliedVersion());
        assertNotNull(result.getErrorDigest());
        verify(runtimeConfigPort).restoreRuntimeConfigSnapshot(previousRuntime);
        verify(modelCatalogAdminPort).replaceCatalogSnapshot(previousCatalog);
    }

    @Test
    void shouldMarkSyncRequestedWithoutChangingAppliedVersion() {
        when(managedPolicyStatePort.load()).thenReturn(Optional.of(ManagedPolicyBindingState.builder()
                .policyGroupId("pg-1")
                .targetVersion(3)
                .appliedVersion(2)
                .syncStatus("OUT_OF_SYNC")
                .checksum("sha256:old")
                .build()));

        service.markSyncRequested("pg-1", 4, "sha256:new");

        verify(managedPolicyStatePort).save(ManagedPolicyBindingState.builder()
                .policyGroupId("pg-1")
                .targetVersion(4)
                .appliedVersion(2)
                .checksum("sha256:new")
                .syncStatus("SYNC_PENDING")
                .lastSyncRequestedAt(Instant.parse("2026-04-08T12:00:00Z"))
                .build());
    }

    @Test
    void shouldRejectMissingPolicyPackage() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.applyPolicyPackage(null));

        assertEquals("Hive policy package is required", error.getMessage());
    }

    @Test
    void shouldRejectMissingPolicyGroupId() {
        HivePolicyPackage policyPackage = HivePolicyPackage.builder()
                .targetVersion(4)
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.applyPolicyPackage(policyPackage));

        assertEquals("Hive policy package policyGroupId is required", error.getMessage());
    }

    @Test
    void shouldRejectMissingTargetVersion() {
        HivePolicyPackage policyPackage = HivePolicyPackage.builder()
                .policyGroupId("pg-1")
                .checksum("sha256:abcd")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.applyPolicyPackage(policyPackage));

        assertEquals("Hive policy package targetVersion is required", error.getMessage());
    }

    @Test
    void shouldRejectMissingChecksum() {
        HivePolicyPackage policyPackage = HivePolicyPackage.builder()
                .policyGroupId("pg-1")
                .targetVersion(4)
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.applyPolicyPackage(policyPackage));

        assertEquals("Hive policy package checksum is required", error.getMessage());
    }

    @Test
    void shouldShortCircuitWhenPolicyPackageIsAlreadyApplied() {
        ManagedPolicyBindingState existingState = ManagedPolicyBindingState.builder()
                .policyGroupId("pg-1")
                .targetVersion(4)
                .appliedVersion(4)
                .checksum("sha256:abcd")
                .syncStatus("IN_SYNC")
                .lastSyncRequestedAt(Instant.parse("2026-04-08T10:00:00Z"))
                .lastAppliedAt(Instant.parse("2026-04-08T11:00:00Z"))
                .build();
        HivePolicyPackage policyPackage = HivePolicyPackage.builder()
                .policyGroupId("pg-1")
                .targetVersion(4)
                .checksum("sha256:abcd")
                .build();
        when(managedPolicyStatePort.load()).thenReturn(Optional.of(existingState));

        HivePolicyApplyResult result = service.applyPolicyPackage(policyPackage);

        assertEquals("IN_SYNC", result.getSyncStatus());
        assertEquals(4, result.getAppliedVersion());
        verify(runtimeConfigPort, never()).replaceManagedPolicySections(any(RuntimeConfig.LlmConfig.class),
                any(RuntimeConfig.ModelRouterConfig.class));
        verify(modelCatalogAdminPort, never()).replaceCatalogSnapshot(any(ManagedModelCatalog.class));
        verify(managedPolicyStatePort).save(ManagedPolicyBindingState.builder()
                .policyGroupId("pg-1")
                .targetVersion(4)
                .appliedVersion(4)
                .checksum("sha256:abcd")
                .syncStatus("IN_SYNC")
                .lastSyncRequestedAt(Instant.parse("2026-04-08T10:00:00Z"))
                .lastAppliedAt(Instant.parse("2026-04-08T11:00:00Z"))
                .build());
    }
}
