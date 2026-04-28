package me.golemcore.bot.domain.runtimeconfig;

import me.golemcore.bot.domain.model.RuntimeConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeConfigSnapshotProviderTest {

    @Test
    void shouldLazyLoadOnlyOnceUntilReloaded() {
        RuntimeConfigSnapshotProvider provider = new RuntimeConfigSnapshotProvider();
        AtomicInteger loads = new AtomicInteger();
        RuntimeConfig first = RuntimeConfig.builder().build();
        RuntimeConfig second = RuntimeConfig.builder().build();

        RuntimeConfig loaded = provider.getOrLoad(() -> {
            loads.incrementAndGet();
            return first;
        });
        RuntimeConfig cached = provider.getOrLoad(() -> second);

        assertSame(first, loaded);
        assertSame(first, cached);
        assertEquals(1, loads.get());

        RuntimeConfig reloaded = provider.reload(() -> second);

        assertSame(second, reloaded);
        assertSame(second, provider.current());
    }
}
