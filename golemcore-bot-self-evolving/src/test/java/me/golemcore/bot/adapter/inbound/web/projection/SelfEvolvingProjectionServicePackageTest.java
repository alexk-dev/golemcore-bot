package me.golemcore.bot.adapter.inbound.web.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SelfEvolvingProjectionServicePackageTest {

    @Test
    void shouldLiveInInboundAdapterProjectionPackage() {
        assertEquals(
                "me.golemcore.bot.adapter.inbound.web.projection",
                SelfEvolvingProjectionService.class.getPackageName());
    }
}
