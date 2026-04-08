package me.golemcore.bot.domain.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.hive.HivePolicyApplyResult;
import me.golemcore.bot.domain.model.hive.HivePolicyBindingState;
import me.golemcore.bot.domain.model.hive.HivePolicyModelCatalog;
import me.golemcore.bot.domain.model.hive.HivePolicyPackage;
import me.golemcore.bot.port.outbound.ModelCatalogAdminPort;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class HiveManagedPolicyService {

    public static final String SYNC_PENDING = "SYNC_PENDING";
    public static final String APPLYING = "APPLYING";
    public static final String IN_SYNC = "IN_SYNC";
    public static final String OUT_OF_SYNC = "OUT_OF_SYNC";
    public static final String APPLY_FAILED = "APPLY_FAILED";

    private final RuntimeConfigService runtimeConfigService;
    private final ModelCatalogAdminPort modelCatalogAdminPort;
    private final HivePolicyStateStore hivePolicyStateStore;
    private final Clock clock;

    public Optional<HivePolicyBindingState> getBindingState() {
        return hivePolicyStateStore.load();
    }

    public boolean hasActiveBinding() {
        return getBindingState().map(HivePolicyBindingState::hasActiveBinding).orElse(false);
    }

    public boolean isSyncPending() {
        return getBindingState()
                .map(state -> SYNC_PENDING.equals(state.getSyncStatus()) || OUT_OF_SYNC.equals(state.getSyncStatus())
                        || APPLY_FAILED.equals(state.getSyncStatus()))
                .orElse(false);
    }

    public void markSyncRequested(String policyGroupId, Integer targetVersion, String checksum) {
        Instant now = Instant.now(clock);
        HivePolicyBindingState current = getBindingState().orElse(HivePolicyBindingState.builder().build());
        hivePolicyStateStore.save(HivePolicyBindingState.builder()
                .policyGroupId(policyGroupId)
                .targetVersion(targetVersion)
                .appliedVersion(current.getAppliedVersion())
                .checksum(checksum)
                .syncStatus(SYNC_PENDING)
                .lastSyncRequestedAt(now)
                .lastAppliedAt(current.getLastAppliedAt())
                .lastErrorDigest(current.getLastErrorDigest())
                .lastErrorAt(current.getLastErrorAt())
                .build());
    }

    public void clearBinding() {
        hivePolicyStateStore.clear();
    }

    public HivePolicyApplyResult applyPolicyPackage(HivePolicyPackage policyPackage) {
        validatePolicyPackage(policyPackage);
        HivePolicyBindingState existingState = getBindingState().orElse(HivePolicyBindingState.builder().build());
        if (isAlreadyApplied(existingState, policyPackage)) {
            HivePolicyBindingState inSyncState = HivePolicyBindingState.builder()
                    .policyGroupId(policyPackage.getPolicyGroupId())
                    .targetVersion(policyPackage.getTargetVersion())
                    .appliedVersion(policyPackage.getTargetVersion())
                    .checksum(policyPackage.getChecksum())
                    .syncStatus(IN_SYNC)
                    .lastSyncRequestedAt(resolveLastSyncRequestedAt(existingState))
                    .lastAppliedAt(resolveLastAppliedAt(existingState))
                    .build();
            hivePolicyStateStore.save(inSyncState);
            return toApplyResult(inSyncState, null);
        }

        RuntimeConfig previousRuntime = runtimeConfigService.snapshotRuntimeConfig();
        HivePolicyModelCatalog previousCatalog = modelCatalogAdminPort.getCatalogSnapshot();
        HivePolicyBindingState applyingState = buildApplyingState(existingState, policyPackage);
        hivePolicyStateStore.save(applyingState);

        boolean runtimeUpdated = false;
        boolean catalogUpdated = false;
        try {
            runtimeConfigService.replaceHiveManagedPolicySections(policyPackage.toLlmConfig(),
                    policyPackage.getModelRouter());
            runtimeUpdated = true;
            modelCatalogAdminPort.replaceCatalogSnapshot(policyPackage.getModelCatalog());
            catalogUpdated = true;
            HivePolicyBindingState syncedState = HivePolicyBindingState.builder()
                    .policyGroupId(policyPackage.getPolicyGroupId())
                    .targetVersion(policyPackage.getTargetVersion())
                    .appliedVersion(policyPackage.getTargetVersion())
                    .checksum(policyPackage.getChecksum())
                    .syncStatus(IN_SYNC)
                    .lastSyncRequestedAt(resolveLastSyncRequestedAt(applyingState))
                    .lastAppliedAt(Instant.now(clock))
                    .build();
            hivePolicyStateStore.save(syncedState);
            return toApplyResult(syncedState, null);
        } catch (RuntimeException exception) {
            rollback(previousRuntime, previousCatalog, runtimeUpdated, catalogUpdated);
            HivePolicyBindingState failedState = HivePolicyBindingState.builder()
                    .policyGroupId(policyPackage.getPolicyGroupId())
                    .targetVersion(policyPackage.getTargetVersion())
                    .appliedVersion(existingState.getAppliedVersion())
                    .checksum(policyPackage.getChecksum())
                    .syncStatus(APPLY_FAILED)
                    .lastSyncRequestedAt(resolveLastSyncRequestedAt(applyingState))
                    .lastAppliedAt(existingState.getLastAppliedAt())
                    .lastErrorDigest(exception.getMessage())
                    .lastErrorAt(Instant.now(clock))
                    .build();
            hivePolicyStateStore.save(failedState);
            return toApplyResult(failedState, exception);
        }
    }

    private void validatePolicyPackage(HivePolicyPackage policyPackage) {
        if (policyPackage == null) {
            throw new IllegalArgumentException("Hive policy package is required");
        }
        if (policyPackage.getPolicyGroupId() == null || policyPackage.getPolicyGroupId().isBlank()) {
            throw new IllegalArgumentException("Hive policy package policyGroupId is required");
        }
        if (policyPackage.getTargetVersion() == null) {
            throw new IllegalArgumentException("Hive policy package targetVersion is required");
        }
    }

    private boolean isAlreadyApplied(HivePolicyBindingState existingState, HivePolicyPackage policyPackage) {
        return existingState != null
                && policyPackage.getPolicyGroupId().equals(existingState.getPolicyGroupId())
                && policyPackage.getTargetVersion().equals(existingState.getAppliedVersion())
                && policyPackage.getChecksum() != null
                && policyPackage.getChecksum().equals(existingState.getChecksum())
                && IN_SYNC.equals(existingState.getSyncStatus());
    }

    private HivePolicyBindingState buildApplyingState(HivePolicyBindingState existingState,
            HivePolicyPackage policyPackage) {
        return HivePolicyBindingState.builder()
                .policyGroupId(policyPackage.getPolicyGroupId())
                .targetVersion(policyPackage.getTargetVersion())
                .appliedVersion(existingState.getAppliedVersion())
                .checksum(policyPackage.getChecksum())
                .syncStatus(APPLYING)
                .lastSyncRequestedAt(resolveLastSyncRequestedAt(existingState))
                .lastAppliedAt(existingState.getLastAppliedAt())
                .lastErrorDigest(null)
                .lastErrorAt(null)
                .build();
    }

    private Instant resolveLastSyncRequestedAt(HivePolicyBindingState state) {
        return state != null && state.getLastSyncRequestedAt() != null ? state.getLastSyncRequestedAt()
                : Instant.now(clock);
    }

    private Instant resolveLastAppliedAt(HivePolicyBindingState state) {
        return state != null && state.getLastAppliedAt() != null ? state.getLastAppliedAt() : Instant.now(clock);
    }

    private void rollback(RuntimeConfig previousRuntime, HivePolicyModelCatalog previousCatalog, boolean runtimeUpdated,
            boolean catalogUpdated) {
        if (runtimeUpdated) {
            runtimeConfigService.restoreRuntimeConfigSnapshot(previousRuntime);
        }
        if (catalogUpdated || previousCatalog != null) {
            modelCatalogAdminPort.replaceCatalogSnapshot(previousCatalog);
        }
    }

    private HivePolicyApplyResult toApplyResult(HivePolicyBindingState state, RuntimeException failure) {
        return HivePolicyApplyResult.builder()
                .policyGroupId(state.getPolicyGroupId())
                .targetVersion(state.getTargetVersion())
                .appliedVersion(state.getAppliedVersion())
                .syncStatus(state.getSyncStatus())
                .checksum(state.getChecksum())
                .errorDigest(state.getLastErrorDigest())
                .errorDetails(failure != null ? failure.getMessage() : null)
                .build();
    }
}
