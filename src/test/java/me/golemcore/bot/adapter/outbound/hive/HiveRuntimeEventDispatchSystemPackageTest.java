package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.golemcore.bot.domain.system.HiveRuntimeEventDispatchSystem;
import org.junit.jupiter.api.Test;

class HiveRuntimeEventDispatchSystemPackageTest {

    @Test
    void shouldLiveInDomainSystemPackage() {
        assertEquals(
                "me.golemcore.bot.domain.system",
                HiveRuntimeEventDispatchSystem.class.getPackageName());
    }
}
