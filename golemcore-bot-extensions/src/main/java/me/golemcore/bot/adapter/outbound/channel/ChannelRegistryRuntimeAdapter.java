package me.golemcore.bot.adapter.outbound.channel;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.outbound.ChannelDeliveryPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChannelRegistryRuntimeAdapter implements ChannelRuntimePort {

    private final ChannelRegistry channelRegistry;

    @Override
    public Optional<ChannelDeliveryPort> findChannel(String channelType) {
        return channelRegistry.get(channelType).map(channel -> channel);
    }

    @Override
    public List<ChannelDeliveryPort> listChannels() {
        return channelRegistry.getAll().stream()
                .map(channel -> (ChannelDeliveryPort) channel)
                .collect(Collectors.toList());
    }

    @Override
    public boolean isChannelRunning(String channelType) {
        return channelRegistry.get(channelType)
                .map(channel -> channel.isRunning())
                .orElse(false);
    }
}
