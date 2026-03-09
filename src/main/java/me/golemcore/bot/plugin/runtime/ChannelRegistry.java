package me.golemcore.bot.plugin.runtime;

import me.golemcore.bot.port.inbound.ChannelPort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dynamic registry of built-in and plugin-backed channels.
 */
@Component
public class ChannelRegistry {

    private final Map<String, ChannelPort> builtInChannels = new LinkedHashMap<>();
    private final Map<String, Map<String, ChannelPort>> pluginChannels = new LinkedHashMap<>();

    public ChannelRegistry(List<ChannelPort> builtInChannels) {
        for (ChannelPort channel : builtInChannels) {
            this.builtInChannels.put(channel.getChannelType(), channel);
        }
    }

    public synchronized void replacePluginChannels(String pluginId, Collection<ChannelPort> channels) {
        Map<String, ChannelPort> byType = new LinkedHashMap<>();
        for (ChannelPort channel : channels) {
            byType.put(channel.getChannelType(), channel);
        }
        pluginChannels.put(pluginId, byType);
    }

    public synchronized void removePluginChannels(String pluginId) {
        pluginChannels.remove(pluginId);
    }

    public synchronized Optional<ChannelPort> get(String channelType) {
        if (builtInChannels.containsKey(channelType)) {
            return Optional.ofNullable(builtInChannels.get(channelType));
        }
        return pluginChannels.values().stream()
                .map(channels -> channels.get(channelType))
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    public synchronized List<ChannelPort> getAll() {
        List<ChannelPort> channels = new ArrayList<>(builtInChannels.values());
        for (Map<String, ChannelPort> entries : pluginChannels.values()) {
            channels.addAll(entries.values());
        }
        return List.copyOf(channels);
    }
}
