package me.golemcore.bot.adapter.outbound.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.channel.ChannelPort;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ChannelRegistryRuntimeAdapterTest {

    @Test
    void shouldResolveChannelsByTypeAndExposeRegisteredChannels() {
        ChannelPort telegram = Mockito.mock(ChannelPort.class);
        Mockito.when(telegram.getChannelType()).thenReturn("telegram");
        ChannelPort web = Mockito.mock(ChannelPort.class);
        Mockito.when(web.getChannelType()).thenReturn("web");

        ChannelRegistryRuntimeAdapter adapter = new ChannelRegistryRuntimeAdapter(
                new ChannelRegistry(List.of(telegram, web)));

        assertThat(adapter.findChannel("telegram")).containsSame(telegram);
        assertThat(adapter.findChannel("missing")).isEmpty();
        assertThat(adapter.listChannels()).containsExactly(telegram, web);
    }
}
