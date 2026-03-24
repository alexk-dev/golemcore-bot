package me.golemcore.bot.plugin.runtime;

import me.golemcore.bot.port.inbound.ChannelPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelRegistryTest {

    @Test
    void shouldPreferBuiltInChannelWhenPluginUsesSameType() {
        ChannelPort builtIn = channel("telegram");
        ChannelPort pluginChannel = channel("telegram");
        ChannelRegistry registry = new ChannelRegistry(List.of(builtIn));

        registry.replacePluginChannels("plugin-a", List.of(pluginChannel));

        assertSame(builtIn, registry.get("telegram").orElseThrow());
    }

    @Test
    void shouldReplaceAndRemovePluginChannels() {
        ChannelRegistry registry = new ChannelRegistry(List.of());
        ChannelPort first = channel("discord");
        ChannelPort replacement = channel("discord");

        registry.replacePluginChannels("plugin-a", List.of(first));
        assertSame(first, registry.get("discord").orElseThrow());

        registry.replacePluginChannels("plugin-a", List.of(replacement));
        assertSame(replacement, registry.get("discord").orElseThrow());

        registry.removePluginChannels("plugin-a");
        assertTrue(registry.get("discord").isEmpty());
    }

    @Test
    void shouldReturnAllChannelsInStableOrder() {
        ChannelPort builtIn = channel("telegram");
        ChannelPort pluginA = channel("discord");
        ChannelPort pluginB = channel("web");
        ChannelRegistry registry = new ChannelRegistry(List.of(builtIn));

        registry.replacePluginChannels("plugin-a", List.of(pluginA));
        registry.replacePluginChannels("plugin-b", List.of(pluginB));

        List<ChannelPort> channels = registry.getAll();

        assertEquals(List.of(builtIn, pluginA, pluginB), channels);
    }

    private ChannelPort channel(String type) {
        ChannelPort port = mock(ChannelPort.class);
        when(port.getChannelType()).thenReturn(type);
        return port;
    }
}
