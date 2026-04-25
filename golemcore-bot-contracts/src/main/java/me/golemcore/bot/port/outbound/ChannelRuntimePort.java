package me.golemcore.bot.port.outbound;

import java.util.List;
import java.util.Optional;

/**
 * Resolves the currently registered runtime delivery channels without exposing
 * the concrete runtime registry to the domain layer.
 */
public interface ChannelRuntimePort {

    Optional<ChannelDeliveryPort> findChannel(String channelType);

    List<ChannelDeliveryPort> listChannels();

    default boolean isChannelRunning(String channelType) {
        return findChannel(channelType).isPresent();
    }
}
