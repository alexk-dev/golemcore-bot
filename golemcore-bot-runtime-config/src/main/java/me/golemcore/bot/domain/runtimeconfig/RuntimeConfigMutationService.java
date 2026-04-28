package me.golemcore.bot.domain.runtimeconfig;

import java.util.Objects;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.port.outbound.RuntimeConfigPersistencePort;

/**
 * Applies effective runtime config mutations and rolls back the cache if persistence fails.
 */
public class RuntimeConfigMutationService {

    private final RuntimeConfigPersistencePort runtimeConfigPersistencePort;
    private final RuntimeConfigSnapshotProvider snapshotProvider;

    public RuntimeConfigMutationService(RuntimeConfigPersistencePort runtimeConfigPersistencePort,
            RuntimeConfigSnapshotProvider snapshotProvider) {
        this.runtimeConfigPersistencePort = Objects.requireNonNull(runtimeConfigPersistencePort,
                "runtimeConfigPersistencePort must not be null");
        this.snapshotProvider = Objects.requireNonNull(snapshotProvider, "snapshotProvider must not be null");
    }

    public void persist(RuntimeConfig persistedConfig, RuntimeConfig effectiveConfig) {
        RuntimeConfig previousConfig = snapshotProvider.current();
        snapshotProvider.replace(effectiveConfig);
        try {
            runtimeConfigPersistencePort.persist(persistedConfig);
        } catch (Exception e) {
            snapshotProvider.replace(previousConfig);
            throw e;
        }
    }
}
