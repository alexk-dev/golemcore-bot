package me.golemcore.bot.domain.service;

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
import me.golemcore.bot.domain.model.hive.HivePolicyBindingState;
import me.golemcore.bot.domain.model.hive.HivePolicyModelCatalog;
import me.golemcore.bot.domain.model.hive.HivePolicyPackage;
import me.golemcore.bot.port.outbound.HivePolicyStatePort;
import me.golemcore.bot.port.outbound.ModelCatalogAdminPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveManagedPolicyServiceTest {

    private RuntimeConfigService runtimeConfigService;
    private ModelCatalogAdminPort modelCatalogAdminPort;
    private HivePolicyStatePort hivePolicyStatePort;
    private HiveManagedPolicyService service;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        modelCatalogAdminPort = mock(ModelCatalogAdminPort.class);
        hivePolicyStatePort = mock(HivePolicyStatePort.class);
        service = new HiveManagedPolicyService(
                runtimeConfigService,
                modelCatalogAdminPort,
                hivePolicyStatePort,
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
                .modelCatalog(HivePolicyModelCatalog.builder()
                        .defaultModel("openai/gpt-5.1")
                        .build())
                .build();
        when(runtimeConfigService.snapshotRuntimeConfig()).thenReturn(RuntimeConfig.builder().build());
        when(modelCatalogAdminPort.getCatalogSnapshot()).thenReturn(HivePolicyModelCatalog.builder().build());

        HivePolicyApplyResult result = service.applyPolicyPackage(policyPackage);

        assertEquals("IN_SYNC", result.getSyncStatus());
        assertEquals(4, result.getAppliedVersion());
        verify(runtimeConfigService).replaceHiveManagedPolicySections(any(RuntimeConfig.LlmConfig.class),
                any(RuntimeConfig.ModelRouterConfig.class));
        verify(modelCatalogAdminPort).replaceCatalogSnapshot(policyPackage.getModelCatalog());
        verify(hivePolicyStatePort).save(HivePolicyBindingState.builder()
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
        HivePolicyBindingState existingState = HivePolicyBindingState.builder()
                .policyGroupId("pg-1")
                .targetVersion(2)
                .appliedVersion(1)
                .syncStatus("OUT_OF_SYNC")
                .build();
        when(hivePolicyStatePort.load()).thenReturn(Optional.of(existingState));

        Optional<HivePolicyBindingState> result = service.getBindingState();

        assertTrue(result.isPresent());
        assertSame(existingState, result.get());
        assertTrue(service.hasActiveBinding());
        assertTrue(service.isSyncPending());
    }

    @Test
    void shouldReturnFalseForBindingFlagsWhenStateIsMissing() {
        when(hivePolicyStatePort.load()).thenReturn(Optional.empty());

        assertTrue(service.getBindingState().isEmpty());
        assertFalse(service.hasActiveBinding());
        assertFalse(service.isSyncPending());
    }

    @Test
    void shouldClearBindingState() {
        service.clearBinding();

        verify(hivePolicyStatePort).clear();
    }

    @Test
    void shouldRollbackPreviousConfigAndCatalogWhenCatalogPersistFails() {
        RuntimeConfig previousRuntime = RuntimeConfig.builder().build();
        HivePolicyModelCatalog previousCatalog = HivePolicyModelCatalog.builder()
                .defaultModel("openai/gpt-4.1")
                .build();
        HivePolicyBindingState existingState = HivePolicyBindingState.builder()
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
                .modelCatalog(HivePolicyModelCatalog.builder()
                        .defaultModel("openai/gpt-5.1")
                        .build())
                .build();
        when(runtimeConfigService.snapshotRuntimeConfig()).thenReturn(previousRuntime);
        when(modelCatalogAdminPort.getCatalogSnapshot()).thenReturn(previousCatalog);
        when(hivePolicyStatePort.load()).thenReturn(Optional.of(existingState));
        doThrow(new IllegalStateException("models write failed"))
                .when(modelCatalogAdminPort).replaceCatalogSnapshot(policyPackage.getModelCatalog());

        HivePolicyApplyResult result = service.applyPolicyPackage(policyPackage);

        assertEquals("APPLY_FAILED", result.getSyncStatus());
        assertEquals(3, result.getAppliedVersion());
        assertNotNull(result.getErrorDigest());
        verify(runtimeConfigService).restoreRuntimeConfigSnapshot(previousRuntime);
        verify(modelCatalogAdminPort).replaceCatalogSnapshot(previousCatalog);
    }

    @Test
    void shouldMarkSyncRequestedWithoutChangingAppliedVersion() {
        when(hivePolicyStatePort.load()).thenReturn(Optional.of(HivePolicyBindingState.builder()
                .policyGroupId("pg-1")
                .targetVersion(3)
                .appliedVersion(2)
                .syncStatus("OUT_OF_SYNC")
                .checksum("sha256:old")
                .build()));

        service.markSyncRequested("pg-1", 4, "sha256:new");

        verify(hivePolicyStatePort).save(HivePolicyBindingState.builder()
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
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.applyPolicyPackage(policyPackage));

        assertEquals("Hive policy package targetVersion is required", error.getMessage());
    }

    @Test
    void shouldShortCircuitWhenPolicyPackageIsAlreadyApplied() {
        HivePolicyBindingState existingState = HivePolicyBindingState.builder()
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
        when(hivePolicyStatePort.load()).thenReturn(Optional.of(existingState));

        HivePolicyApplyResult result = service.applyPolicyPackage(policyPackage);

        assertEquals("IN_SYNC", result.getSyncStatus());
        assertEquals(4, result.getAppliedVersion());
        verify(runtimeConfigService, never()).replaceHiveManagedPolicySections(any(RuntimeConfig.LlmConfig.class),
                any(RuntimeConfig.ModelRouterConfig.class));
        verify(modelCatalogAdminPort, never()).replaceCatalogSnapshot(any(HivePolicyModelCatalog.class));
        verify(hivePolicyStatePort).save(HivePolicyBindingState.builder()
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
