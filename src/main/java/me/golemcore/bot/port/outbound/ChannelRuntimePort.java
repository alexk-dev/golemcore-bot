package me.golemcore.bot.port.outbound;

import java.util.List;
import java.util.Optional;
import me.golemcore.bot.port.inbound.ChannelPort;

/**
 * Resolves the currently registered runtime channels without exposing the
 * concrete runtime registry to the domain layer.
 */
public interface ChannelRuntimePort {

    Optional<ChannelPort> findChannel(String channelType);

    List<ChannelPort> listChannels();
}
