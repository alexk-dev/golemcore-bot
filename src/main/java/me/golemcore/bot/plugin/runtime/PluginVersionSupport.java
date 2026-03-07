package me.golemcore.bot.plugin.runtime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared SemVer helpers for plugin runtime and marketplace flows.
 */
final class PluginVersionSupport {

    private static final Pattern SEMVER_PATTERN = Pattern.compile(
            "^(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?$");

    private PluginVersionSupport() {
    }

    static boolean matchesVersionConstraint(String version, String constraint) {
        if (constraint == null || constraint.isBlank()) {
            return true;
        }
        for (String token : constraint.trim().split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            String normalizedToken = token.endsWith(">")
                    ? token.substring(0, token.length() - 1)
                    : token;
            String operator = extractOperator(normalizedToken);
            String expectedVersion = normalizedToken.substring(operator.length());
            int comparison = compareVersions(version, expectedVersion);
            boolean matches = switch (operator) {
            case ">=" -> comparison >= 0;
            case ">" -> comparison > 0;
            case "<=" -> comparison <= 0;
            case "<" -> comparison < 0;
            case "=" -> comparison == 0;
            default -> throw new IllegalArgumentException("Unsupported engineVersion constraint: " + token);
            };
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    static int compareVersions(String left, String right) {
        SemVer leftVersion = parseSemVer(left);
        SemVer rightVersion = parseSemVer(right);
        int mainComparison = Integer.compare(leftVersion.major(), rightVersion.major());
        if (mainComparison != 0) {
            return mainComparison;
        }
        int minorComparison = Integer.compare(leftVersion.minor(), rightVersion.minor());
        if (minorComparison != 0) {
            return minorComparison;
        }
        int patchComparison = Integer.compare(leftVersion.patch(), rightVersion.patch());
        if (patchComparison != 0) {
            return patchComparison;
        }
        if (leftVersion.preRelease() == null && rightVersion.preRelease() == null) {
            return 0;
        }
        if (leftVersion.preRelease() == null) {
            return 1;
        }
        if (rightVersion.preRelease() == null) {
            return -1;
        }
        return comparePreRelease(leftVersion.preRelease(), rightVersion.preRelease());
    }

    static String normalizeHostVersion(String version) {
        if (version == null || version.isBlank()) {
            return "0.0.0";
        }
        String normalized = version.trim();
        if (normalized.endsWith("-SNAPSHOT")) {
            return normalized.substring(0, normalized.length() - "-SNAPSHOT".length());
        }
        return normalized;
    }

    private static String extractOperator(String token) {
        if (token.startsWith(">=") || token.startsWith("<=")) {
            return token.substring(0, 2);
        }
        if (token.startsWith(">") || token.startsWith("<") || token.startsWith("=")) {
            return token.substring(0, 1);
        }
        throw new IllegalArgumentException("Constraint token must start with comparison operator: " + token);
    }

    private static int comparePreRelease(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            if (index >= leftParts.length) {
                return -1;
            }
            if (index >= rightParts.length) {
                return 1;
            }
            String leftPart = leftParts[index];
            String rightPart = rightParts[index];
            boolean leftNumeric = leftPart.chars().allMatch(Character::isDigit);
            boolean rightNumeric = rightPart.chars().allMatch(Character::isDigit);
            if (leftNumeric && rightNumeric) {
                int comparison = Integer.compare(Integer.parseInt(leftPart), Integer.parseInt(rightPart));
                if (comparison != 0) {
                    return comparison;
                }
                continue;
            }
            if (leftNumeric != rightNumeric) {
                return leftNumeric ? -1 : 1;
            }
            int comparison = leftPart.compareTo(rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static SemVer parseSemVer(String version) {
        Matcher matcher = SEMVER_PATTERN.matcher(version);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid SemVer: " + version);
        }
        return new SemVer(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0,
                matcher.group(4));
    }

    private record SemVer(int major, int minor, int patch, String preRelease) {
    }
}
