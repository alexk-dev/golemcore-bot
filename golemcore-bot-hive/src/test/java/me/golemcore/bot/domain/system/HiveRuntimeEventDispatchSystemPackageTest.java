package me.golemcore.bot.domain.system;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HiveRuntimeEventDispatchSystemPackageTest {

    @Test
    void shouldLiveInDomainSystemPackage() {
        assertEquals(
                "me.golemcore.bot.domain.system",
                HiveRuntimeEventDispatchSystem.class.getPackageName());
    }
}
