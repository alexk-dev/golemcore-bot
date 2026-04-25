package me.golemcore.bot.adapter.inbound.web.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ArtifactProjectionServicePackageTest {

    @Test
    void shouldLiveInInboundAdapterProjectionPackage() {
        assertEquals(
                "me.golemcore.bot.adapter.inbound.web.projection",
                ArtifactProjectionService.class.getPackageName());
    }
}
