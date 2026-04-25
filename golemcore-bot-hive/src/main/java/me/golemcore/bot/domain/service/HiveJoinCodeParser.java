package me.golemcore.bot.domain.service;

import java.net.URI;

public final class HiveJoinCodeParser {

    private HiveJoinCodeParser() {
    }

    public static ParsedJoinCode parse(String joinCode) {
        if (joinCode == null || joinCode.isBlank()) {
            throw new IllegalArgumentException("Hive joinCode is required");
        }
        int delimiterIndex = joinCode.indexOf(':');
        if (delimiterIndex <= 0 || delimiterIndex >= joinCode.length() - 1) {
            throw new IllegalArgumentException("Hive joinCode must match <TOKEN>:<URL>");
        }
        String enrollmentToken = joinCode.substring(0, delimiterIndex).trim();
        String serverUrl = normalizeServerUrl(joinCode.substring(delimiterIndex + 1));
        if (enrollmentToken.isBlank()) {
            throw new IllegalArgumentException("Hive joinCode must include an enrollment token");
        }
        return new ParsedJoinCode(enrollmentToken, serverUrl);
    }

    public static String tryExtractServerUrl(String joinCode) {
        try {
            return parse(joinCode).serverUrl();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static String normalizeServerUrl(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException("Hive joinCode must include a server URL");
        }
        String trimmed = stripTrailingSlash(serverUrl.trim());
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Hive joinCode server URL must be a valid http(s) URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || uri.getHost() == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Hive joinCode server URL must be a valid http(s) URL");
        }
        return trimmed;
    }

    private static String stripTrailingSlash(String value) {
        String normalized = value;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public record ParsedJoinCode(String enrollmentToken, String serverUrl) {
    }
}
