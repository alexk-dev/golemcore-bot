package me.golemcore.bot.port.outbound;

import java.util.function.Supplier;
import me.golemcore.bot.domain.model.RuntimeConfig;

/**
 * Persistence contract for runtime configuration section storage and cloning.
 */
public interface RuntimeConfigPersistencePort {

    RuntimeConfig loadOrCreate();

    void persist(RuntimeConfig runtimeConfig);

    RuntimeConfig copy(RuntimeConfig runtimeConfig);

    <T> T loadSection(
            RuntimeConfig.ConfigSection section,
            Class<T> configClass,
            Supplier<T> defaultSupplier,
            boolean persistDefault);
}
