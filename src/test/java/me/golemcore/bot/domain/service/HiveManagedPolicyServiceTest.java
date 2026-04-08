package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
import me.golemcore.bot.port.outbound.ModelCatalogAdminPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveManagedPolicyServiceTest {

    private RuntimeConfigService runtimeConfigService;
    private ModelCatalogAdminPort modelCatalogAdminPort;
    private HivePolicyStateStore hivePolicyStateStore;
    private HiveManagedPolicyService service;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        modelCatalogAdminPort = mock(ModelCatalogAdminPort.class);
        hivePolicyStateStore = mock(HivePolicyStateStore.class);
        service = new HiveManagedPolicyService(
                runtimeConfigService,
                modelCatalogAdminPort,
                hivePolicyStateStore,
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
        verify(hivePolicyStateStore).save(HivePolicyBindingState.builder()
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
        when(hivePolicyStateStore.load()).thenReturn(Optional.of(existingState));
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
        when(hivePolicyStateStore.load()).thenReturn(Optional.of(HivePolicyBindingState.builder()
                .policyGroupId("pg-1")
                .targetVersion(3)
                .appliedVersion(2)
                .syncStatus("OUT_OF_SYNC")
                .checksum("sha256:old")
                .build()));

        service.markSyncRequested("pg-1", 4, "sha256:new");

        verify(hivePolicyStateStore).save(HivePolicyBindingState.builder()
                .policyGroupId("pg-1")
                .targetVersion(4)
                .appliedVersion(2)
                .checksum("sha256:new")
                .syncStatus("SYNC_PENDING")
                .lastSyncRequestedAt(Instant.parse("2026-04-08T12:00:00Z"))
                .build());
    }
}
