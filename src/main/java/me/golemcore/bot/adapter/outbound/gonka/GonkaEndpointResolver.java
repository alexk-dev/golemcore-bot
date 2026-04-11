package me.golemcore.bot.adapter.outbound.gonka;

import java.net.URI;
import java.time.Duration;
import java.util.List;

public interface GonkaEndpointResolver {

    GonkaResolvedEndpoint resolve(GonkaEndpointResolutionRequest request);

    record GonkaEndpointResolutionRequest(URI sourceUri, List<GonkaConfiguredEndpoint> configuredEndpoints,
            Duration timeout) {
    }

    record GonkaConfiguredEndpoint(String url, String transferAddress) {
    }

    record GonkaResolvedEndpoint(String url, String transferAddress) {
    }
}
