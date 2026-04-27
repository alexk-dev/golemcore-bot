package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.port.outbound.RuntimeConfigPersistencePort;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeConfigMutationServiceTest {

    @Test
    void shouldRollbackSnapshotWhenPersistFails() {
        RuntimeConfigSnapshotProvider snapshotProvider = new RuntimeConfigSnapshotProvider();
        RuntimeConfig previous = RuntimeConfig.builder().build();
        RuntimeConfig effective = RuntimeConfig.builder().build();
        RuntimeConfig persisted = RuntimeConfig.builder().build();
        snapshotProvider.replace(previous);

        RuntimeConfigMutationService mutationService = new RuntimeConfigMutationService(new FailingPersistencePort(),
                snapshotProvider);

        assertThrows(IllegalStateException.class, () -> mutationService.persist(persisted, effective));
        assertSame(previous, snapshotProvider.current());
    }

    private static final class FailingPersistencePort implements RuntimeConfigPersistencePort {

        @Override
        public RuntimeConfig loadOrCreate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void persist(RuntimeConfig runtimeConfig) {
            throw new IllegalStateException("persist failed");
        }

        @Override
        public RuntimeConfig copy(RuntimeConfig runtimeConfig) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T loadSection(RuntimeConfig.ConfigSection section, Class<T> configClass, Supplier<T> defaultSupplier,
                boolean persistDefault) {
            throw new UnsupportedOperationException();
        }
    }
}
