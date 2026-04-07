package me.golemcore.bot.infrastructure.telemetry;

import java.util.Set;
import java.util.regex.Pattern;

public final class TelemetrySanitizer {

    private static final Pattern UUID_SEGMENT_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LONG_HEX_SEGMENT_PATTERN = Pattern.compile("^[0-9a-f]{16,}$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LONG_NUMERIC_SEGMENT_PATTERN = Pattern.compile("^\\d{6,}$");
    private static final Pattern OPAQUE_SEGMENT_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{20,}$");
    private static final Set<String> KNOWN_SAFE_SEGMENTS = Set.of(
            "chat",
            "settings",
            "sessions",
            "analytics",
            "setup",
            "self-evolving");

    private TelemetrySanitizer() {
    }

    public static String sanitizeRoute(String route) {
        if (route == null || route.isBlank()) {
            return "unknown";
        }

        String normalizedRoute = route.trim().split("[?#]", 2)[0];
        if (!normalizedRoute.startsWith("/")) {
            normalizedRoute = "/" + normalizedRoute;
        }
        if ("/".equals(normalizedRoute)) {
            return normalizedRoute;
        }

        String[] rawSegments = normalizedRoute.split("/");
        StringBuilder builder = new StringBuilder();
        for (String rawSegment : rawSegments) {
            if (rawSegment == null || rawSegment.isBlank()) {
                continue;
            }
            builder.append('/').append(shouldReplacePathSegment(rawSegment) ? ":id" : rawSegment);
        }
        return builder.length() == 0 ? "/" : builder.toString();
    }

    public static String sanitizeErrorName(String errorName) {
        if (errorName == null || errorName.isBlank()) {
            return "UnknownError";
        }
        return errorName.trim();
    }

    public static String sanitizeErrorSource(String source) {
        if (source == null || source.isBlank()) {
            return "unknown";
        }
        return source.trim();
    }

    public static String sanitizeUsageValue(String key, String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        if ("route_view_count".equals(key)) {
            return sanitizeRoute(value);
        }
        return value.trim();
    }

    public static String createUiErrorFingerprint(String source, String route, String errorName) {
        return sanitizeErrorSource(source) + "|"
                + sanitizeRoute(route) + "|"
                + sanitizeErrorName(errorName);
    }

    private static boolean shouldReplacePathSegment(String segment) {
        if (segment == null || segment.isBlank() || KNOWN_SAFE_SEGMENTS.contains(segment)) {
            return false;
        }
        return UUID_SEGMENT_PATTERN.matcher(segment).matches()
                || LONG_HEX_SEGMENT_PATTERN.matcher(segment).matches()
                || LONG_NUMERIC_SEGMENT_PATTERN.matcher(segment).matches()
                || OPAQUE_SEGMENT_PATTERN.matcher(segment).matches();
    }
}
