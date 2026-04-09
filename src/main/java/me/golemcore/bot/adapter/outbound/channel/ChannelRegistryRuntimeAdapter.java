package me.golemcore.bot.adapter.outbound.channel;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChannelRegistryRuntimeAdapter implements ChannelRuntimePort {

    private final ChannelRegistry channelRegistry;

    @Override
    public Optional<ChannelPort> findChannel(String channelType) {
        return channelRegistry.get(channelType);
    }

    @Override
    public List<ChannelPort> listChannels() {
        return channelRegistry.getAll();
    }
}
