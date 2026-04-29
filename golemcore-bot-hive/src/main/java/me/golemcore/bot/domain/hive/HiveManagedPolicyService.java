package me.golemcore.bot.domain.hive;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.hive.HivePolicyApplyResult;
import me.golemcore.bot.domain.model.policy.ManagedPolicyBindingState;
import me.golemcore.bot.domain.model.policy.ManagedModelCatalog;
import me.golemcore.bot.domain.model.hive.HivePolicyPackage;
import me.golemcore.bot.port.outbound.ManagedPolicyQueryPort;
import me.golemcore.bot.port.outbound.ManagedPolicyRuntimeConfigPort;
import me.golemcore.bot.port.outbound.ManagedPolicyStatePort;
import me.golemcore.bot.port.outbound.ModelCatalogAdminPort;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class HiveManagedPolicyService implements ManagedPolicyQueryPort {

    public static final String SYNC_PENDING = "SYNC_PENDING";
    public static final String APPLYING = "APPLYING";
    public static final String IN_SYNC = "IN_SYNC";
    public static final String OUT_OF_SYNC = "OUT_OF_SYNC";
    public static final String APPLY_FAILED = "APPLY_FAILED";

    private final ManagedPolicyRuntimeConfigPort runtimeConfigPort;
    private final ModelCatalogAdminPort modelCatalogAdminPort;
    private final ManagedPolicyStatePort managedPolicyStatePort;
    private final Clock clock;

    public HiveManagedPolicyService(
            ManagedPolicyRuntimeConfigPort runtimeConfigPort,
            ModelCatalogAdminPort modelCatalogAdminPort,
            ManagedPolicyStatePort managedPolicyStatePort,
            Clock clock) {
        this.runtimeConfigPort = runtimeConfigPort;
        this.modelCatalogAdminPort = modelCatalogAdminPort;
        this.managedPolicyStatePort = managedPolicyStatePort;
        this.clock = clock;
    }

    @Override
    public Optional<ManagedPolicyBindingState> findBindingState() {
        return managedPolicyStatePort.load();
    }

    public Optional<ManagedPolicyBindingState> getBindingState() {
        return findBindingState();
    }

    @Override
    public boolean hasActiveBinding() {
        return findBindingState().map(ManagedPolicyBindingState::hasActiveBinding).orElse(false);
    }

    public boolean isSyncPending() {
        return getBindingState()
                .map(state -> SYNC_PENDING.equals(state.getSyncStatus()) || OUT_OF_SYNC.equals(state.getSyncStatus())
                        || APPLY_FAILED.equals(state.getSyncStatus()))
                .orElse(false);
    }

    public void markSyncRequested(String policyGroupId, Integer targetVersion, String checksum) {
        Instant now = Instant.now(clock);
        ManagedPolicyBindingState current = getBindingState().orElse(ManagedPolicyBindingState.builder().build());
        managedPolicyStatePort.save(ManagedPolicyBindingState.builder()
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
        managedPolicyStatePort.clear();
    }

    public HivePolicyApplyResult applyPolicyPackage(HivePolicyPackage policyPackage) {
        validatePolicyPackage(policyPackage);
        ManagedPolicyBindingState existingState = getBindingState().orElse(ManagedPolicyBindingState.builder().build());
        if (isAlreadyApplied(existingState, policyPackage)) {
            ManagedPolicyBindingState inSyncState = ManagedPolicyBindingState.builder()
                    .policyGroupId(policyPackage.getPolicyGroupId())
                    .targetVersion(policyPackage.getTargetVersion())
                    .appliedVersion(policyPackage.getTargetVersion())
                    .checksum(policyPackage.getChecksum())
                    .syncStatus(IN_SYNC)
                    .lastSyncRequestedAt(resolveLastSyncRequestedAt(existingState))
                    .lastAppliedAt(resolveLastAppliedAt(existingState))
                    .build();
            managedPolicyStatePort.save(inSyncState);
            return toApplyResult(inSyncState, null);
        }

        RuntimeConfig previousRuntime = runtimeConfigPort.snapshotRuntimeConfig();
        ManagedModelCatalog previousCatalog = modelCatalogAdminPort.getCatalogSnapshot();
        ManagedPolicyBindingState applyingState = buildApplyingState(existingState, policyPackage);
        managedPolicyStatePort.save(applyingState);

        boolean runtimeUpdated = false;
        boolean catalogUpdated = false;
        try {
            runtimeConfigPort.replaceManagedPolicySections(policyPackage.toLlmConfig(),
                    policyPackage.getModelRouter());
            runtimeUpdated = true;
            modelCatalogAdminPort.replaceCatalogSnapshot(policyPackage.getModelCatalog());
            catalogUpdated = true;
            ManagedPolicyBindingState syncedState = ManagedPolicyBindingState.builder()
                    .policyGroupId(policyPackage.getPolicyGroupId())
                    .targetVersion(policyPackage.getTargetVersion())
                    .appliedVersion(policyPackage.getTargetVersion())
                    .checksum(policyPackage.getChecksum())
                    .syncStatus(IN_SYNC)
                    .lastSyncRequestedAt(resolveLastSyncRequestedAt(applyingState))
                    .lastAppliedAt(Instant.now(clock))
                    .build();
            managedPolicyStatePort.save(syncedState);
            return toApplyResult(syncedState, null);
        } catch (RuntimeException exception) {
            rollback(previousRuntime, previousCatalog, runtimeUpdated, catalogUpdated);
            ManagedPolicyBindingState failedState = ManagedPolicyBindingState.builder()
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
            managedPolicyStatePort.save(failedState);
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
        if (policyPackage.getChecksum() == null || policyPackage.getChecksum().isBlank()) {
            throw new IllegalArgumentException("Hive policy package checksum is required");
        }
    }

    private boolean isAlreadyApplied(ManagedPolicyBindingState existingState, HivePolicyPackage policyPackage) {
        return existingState != null
                && policyPackage.getPolicyGroupId().equals(existingState.getPolicyGroupId())
                && policyPackage.getTargetVersion().equals(existingState.getAppliedVersion())
                && policyPackage.getChecksum() != null
                && policyPackage.getChecksum().equals(existingState.getChecksum())
                && IN_SYNC.equals(existingState.getSyncStatus());
    }

    private ManagedPolicyBindingState buildApplyingState(ManagedPolicyBindingState existingState,
            HivePolicyPackage policyPackage) {
        return ManagedPolicyBindingState.builder()
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

    private Instant resolveLastSyncRequestedAt(ManagedPolicyBindingState state) {
        return state != null && state.getLastSyncRequestedAt() != null ? state.getLastSyncRequestedAt()
                : Instant.now(clock);
    }

    private Instant resolveLastAppliedAt(ManagedPolicyBindingState state) {
        return state != null && state.getLastAppliedAt() != null ? state.getLastAppliedAt() : Instant.now(clock);
    }

    private void rollback(RuntimeConfig previousRuntime, ManagedModelCatalog previousCatalog, boolean runtimeUpdated,
            boolean catalogUpdated) {
        if (runtimeUpdated) {
            runtimeConfigPort.restoreRuntimeConfigSnapshot(previousRuntime);
        }
        if (catalogUpdated || previousCatalog != null) {
            modelCatalogAdminPort.replaceCatalogSnapshot(previousCatalog);
        }
    }

    private HivePolicyApplyResult toApplyResult(ManagedPolicyBindingState state, RuntimeException failure) {
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
