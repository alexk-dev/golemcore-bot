package me.golemcore.bot.support;

import java.util.List;
import me.golemcore.bot.adapter.outbound.channel.ChannelRegistryRuntimeAdapter;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import me.golemcore.bot.domain.tracing.TraceService;
import me.golemcore.bot.domain.runtimeconfig.UserPreferencesService;
import me.golemcore.bot.domain.voice.VoiceResponseHandler;
import me.golemcore.bot.domain.system.ResponseRoutingSystem;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.channel.ChannelPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;

public final class ChannelRuntimeTestSupport {

    private ChannelRuntimeTestSupport() {
    }

    public static ChannelRuntimePort runtime(ChannelPort... channels) {
        return runtime(List.of(channels));
    }

    public static ChannelRuntimePort runtime(List<ChannelPort> channels) {
        return new ChannelRegistryRuntimeAdapter(new ChannelRegistry(channels));
    }

    public static ResponseRoutingSystem responseRoutingSystem(List<ChannelPort> channels,
            UserPreferencesService preferencesService,
            VoiceResponseHandler voiceHandler) {
        return responseRoutingSystem(channels, preferencesService, voiceHandler, null, null);
    }

    public static ResponseRoutingSystem responseRoutingSystem(List<ChannelPort> channels,
            UserPreferencesService preferencesService,
            VoiceResponseHandler voiceHandler,
            RuntimeConfigService runtimeConfigService,
            TraceService traceService) {
        return new ResponseRoutingSystem(
                runtime(channels),
                preferencesService,
                voiceHandler,
                runtimeConfigService,
                traceService);
    }
}
