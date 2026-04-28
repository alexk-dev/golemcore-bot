package me.golemcore.bot.domain.runtimeconfig;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import me.golemcore.bot.domain.model.RuntimeConfig;

/**
 * Owns the cached effective runtime configuration snapshot.
 */
public class RuntimeConfigSnapshotProvider {

    private final AtomicReference<RuntimeConfig> configRef = new AtomicReference<>();

    public RuntimeConfig getOrLoad(Supplier<RuntimeConfig> loader) {
        Objects.requireNonNull(loader, "loader must not be null");
        RuntimeConfig current = configRef.get();
        if (current == null) {
            synchronized (this) {
                current = configRef.get();
                if (current == null) {
                    current = loader.get();
                    configRef.set(current);
                }
            }
        }
        return current;
    }

    public RuntimeConfig current() {
        return configRef.get();
    }

    public void replace(RuntimeConfig config) {
        configRef.set(config);
    }

    public RuntimeConfig reload(Supplier<RuntimeConfig> loader) {
        Objects.requireNonNull(loader, "loader must not be null");
        RuntimeConfig reloaded = loader.get();
        configRef.set(reloaded);
        return reloaded;
    }
}
