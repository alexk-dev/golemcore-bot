package me.golemcore.bot.adapter.outbound.hive;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import me.golemcore.bot.domain.service.HiveJoinCodeParser;

public final class HiveControlChannelUrlResolver {

    private HiveControlChannelUrlResolver() {
    }

    public static URI resolve(String serverUrl, String controlChannelUrl, String accessToken) {
        String normalizedServerUrl = HiveJoinCodeParser.normalizeServerUrl(serverUrl);
        if (controlChannelUrl == null || controlChannelUrl.isBlank()) {
            throw new IllegalArgumentException("Hive controlChannelUrl is required");
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Hive access token is required for control channel connection");
        }

        URI controlUri = URI.create(controlChannelUrl.trim());
        URI resolved = controlUri.isAbsolute() ? controlUri
                : resolveRelative(normalizedServerUrl, controlUri.toString());
        String queryPrefix = resolved.getQuery() == null || resolved.getQuery().isBlank()
                ? ""
                : resolved.getQuery() + "&";
        String query = queryPrefix + "access_token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8);
        try {
            return new URI(
                    resolved.getScheme(),
                    resolved.getUserInfo(),
                    resolved.getHost(),
                    resolved.getPort(),
                    resolved.getPath(),
                    query,
                    resolved.getFragment());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Failed to build Hive control channel URL", exception);
        }
    }

    private static URI resolveRelative(String serverUrl, String controlChannelUrl) {
        URI serverUri = URI.create(serverUrl);
        String scheme = "https".equalsIgnoreCase(serverUri.getScheme()) ? "wss" : "ws";
        String path = controlChannelUrl.startsWith("/") ? controlChannelUrl : "/" + controlChannelUrl;
        try {
            return new URI(
                    scheme,
                    serverUri.getUserInfo(),
                    serverUri.getHost(),
                    serverUri.getPort(),
                    path,
                    null,
                    null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Failed to resolve Hive control channel URL", exception);
        }
    }
}
