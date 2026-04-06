package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HiveRuntimeEventDispatchSystemPackageTest {

    @Test
    void shouldLiveInOutboundHiveAdapterPackage() {
        assertEquals(
                "me.golemcore.bot.adapter.outbound.hive",
                HiveRuntimeEventDispatchSystem.class.getPackageName());
    }
}
