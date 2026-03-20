package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

class HiveControlChannelUrlResolverTest {

    @Test
    void shouldResolveRelativeControlChannelAgainstHttpsServerUrl() {
        URI uri = HiveControlChannelUrlResolver.resolve(
                "https://hive.example.com",
                "/ws/golems/control",
                "token-value");

        assertEquals("wss", uri.getScheme());
        assertEquals("/ws/golems/control", uri.getPath());
        assertTrue(uri.getQuery().contains("access_token=token-value"));
    }

    @Test
    void shouldPreserveAbsoluteControlChannelUrl() {
        URI uri = HiveControlChannelUrlResolver.resolve(
                "https://hive.example.com",
                "ws://localhost:8081/ws/golems/control",
                "token-value");

        assertEquals("ws", uri.getScheme());
        assertEquals("localhost", uri.getHost());
    }
}
