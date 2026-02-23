package me.golemcore.bot.plugin.context;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import jakarta.annotation.PostConstruct;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.ChannelCatalogPort;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified channel catalog with strict plugin-managed channels.
 */
@Component
public class PluginChannelCatalog implements ChannelCatalogPort {

    private static final Map<String, String> MANAGED_CHANNELS = Map.of(
            "telegram", "channel.telegram",
            "webhook", "channel.webhooks");

    private final PluginRegistryService pluginRegistryService;
    private final List<ChannelPort> springChannels;
    private Map<String, ChannelPort> channelRegistry = Map.of();

    public PluginChannelCatalog(PluginRegistryService pluginRegistryService, List<ChannelPort> springChannels) {
        this.pluginRegistryService = pluginRegistryService;
        this.springChannels = springChannels != null ? springChannels : List.of();
    }

    public static PluginChannelCatalog forTesting(List<ChannelPort> channelPorts) {
        PluginChannelCatalog catalog = new PluginChannelCatalog(null, List.of());
        catalog.seedRegistryFromChannels(channelPorts);
        return catalog;
    }

    private void seedRegistryFromChannels(List<ChannelPort> channelPorts) {
        if (channelPorts == null) {
            return;
        }
        Map<String, ChannelPort> localRegistry = new LinkedHashMap<>();
        int fallbackIndex = 0;
        for (ChannelPort channelPort : channelPorts) {
            if (channelPort == null) {
                continue;
            }
            String channelType = channelPort.getChannelType();
            if (channelType == null || channelType.isBlank()) {
                channelType = "compat-channel-" + fallbackIndex;
                fallbackIndex++;
            }
            localRegistry.put(channelType, channelPort);
        }
        this.channelRegistry = Collections.unmodifiableMap(new LinkedHashMap<>(localRegistry));
    }

    @PostConstruct
    void init() {
        if (pluginRegistryService == null) {
            return;
        }
        pluginRegistryService.ensureInitialized();
        rebuildRegistry();
    }

    @Override
    public List<ChannelPort> getAllChannels() {
        return List.copyOf(channelRegistry.values());
    }

    @Override
    public ChannelPort getChannel(String channelType) {
        return channelRegistry.get(channelType);
    }

    private void rebuildRegistry() {
        Map<String, ChannelPort> rebuilt = new LinkedHashMap<>();

        for (ChannelPort channelPort : springChannels) {
            if (!MANAGED_CHANNELS.containsKey(channelPort.getChannelType())) {
                rebuilt.put(channelPort.getChannelType(), channelPort);
            }
        }

        for (Map.Entry<String, String> entry : MANAGED_CHANNELS.entrySet()) {
            String expectedChannelType = entry.getKey();
            String contributionId = entry.getValue();
            ChannelPort pluginChannel = pluginRegistryService.requireContribution(contributionId, ChannelPort.class);
            if (!expectedChannelType.equals(pluginChannel.getChannelType())) {
                throw new IllegalStateException("Plugin contribution " + contributionId
                        + " exposed unexpected channel type: " + pluginChannel.getChannelType()
                        + ", expected: " + expectedChannelType);
            }
            rebuilt.put(expectedChannelType, pluginChannel);
        }

        this.channelRegistry = Collections.unmodifiableMap(new LinkedHashMap<>(rebuilt));
    }
}
