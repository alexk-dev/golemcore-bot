package me.golemcore.bot.runtime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared runtime-version helpers used by update, launcher, and cleanup flows.
 */
public class RuntimeVersionSupport {

    private static final Pattern SEMVER_PATTERN = Pattern
            .compile("^(\\d++)\\.(\\d++)\\.(\\d++)(?:-([0-9A-Za-z.-]++))?$");

    /**
     * Extract the semantic version embedded in a runtime asset name such as
     * {@code bot-0.4.2.jar} or {@code bot-0.4.2-rc.1-exec.jar}.
     *
     * @param assetName
     *            runtime asset file name
     * @return normalized version or {@code null} when the file name does not
     *         contain a supported semantic version
     */
    public String extractVersionFromAssetName(String assetName) {
        if (assetName == null || assetName.isBlank()) {
            return null;
        }
        int length = assetName.length();
        for (int index = 0; index < length; index++) {
            char current = assetName.charAt(index);
            if (!isAsciiDigit(current)) {
                continue;
            }
            if (index > 0 && isAsciiDigit(assetName.charAt(index - 1))) {
                continue;
            }

            String candidate = readVersionCandidate(assetName, index);
            if (candidate != null) {
                return normalizeVersion(candidate);
            }
        }
        return null;
    }

    /**
     * Normalize raw runtime version strings for comparison.
     *
     * @param version
     *            raw version string
     * @return normalized semantic version, or the original blank/null value
     */
    public String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return version;
        }
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith(".jar")) {
            normalized = normalized.substring(0, normalized.length() - ".jar".length());
        }
        if (normalized.endsWith("-exec")) {
            String withoutClassifier = normalized.substring(0, normalized.length() - "-exec".length());
            Matcher matcher = SEMVER_PATTERN.matcher(withoutClassifier);
            if (matcher.matches()) {
                normalized = withoutClassifier;
            }
        }
        return normalized;
    }

    /**
     * Check whether the supplied version string can be treated as semantic version
     * by the shared comparison rules.
     *
     * @param version
     *            raw or normalized version string
     * @return {@code true} when the version parses as semantic version
     */
    public boolean isSemanticVersion(String version) {
        return parseSemver(version) != null;
    }

    /**
     * Check whether the candidate version is newer than the current version.
     *
     * @param candidateVersion
     *            candidate runtime version
     * @param currentVersion
     *            currently active runtime version
     * @return {@code true} when the candidate is strictly newer
     */
    public boolean isRemoteVersionNewer(String candidateVersion, String currentVersion) {
        return compareVersions(candidateVersion, currentVersion) > 0;
    }

    /**
     * Compare two semantic versions.
     *
     * @param left
     *            left-side version
     * @param right
     *            right-side version
     * @return positive when {@code left > right}, negative when
     *         {@code left < right}, zero when equal
     */
    public int compareVersions(String left, String right) {
        Semver leftSemver = parseSemver(left);
        Semver rightSemver = parseSemver(right);

        if (leftSemver == null || rightSemver == null) {
            return normalizeVersion(left).compareTo(normalizeVersion(right));
        }
        if (leftSemver.major() != rightSemver.major()) {
            return Integer.compare(leftSemver.major(), rightSemver.major());
        }
        if (leftSemver.minor() != rightSemver.minor()) {
            return Integer.compare(leftSemver.minor(), rightSemver.minor());
        }
        if (leftSemver.patch() != rightSemver.patch()) {
            return Integer.compare(leftSemver.patch(), rightSemver.patch());
        }

        String leftPrerelease = leftSemver.preRelease();
        String rightPrerelease = rightSemver.preRelease();
        if (leftPrerelease == null && rightPrerelease == null) {
            return 0;
        }
        if (leftPrerelease == null) {
            return 1;
        }
        if (rightPrerelease == null) {
            return -1;
        }
        return comparePrerelease(leftPrerelease, rightPrerelease);
    }

    private String readVersionCandidate(String value, int start) {
        int length = value.length();
        int majorEnd = consumeDigits(value, start);
        if (majorEnd == start || majorEnd >= length || value.charAt(majorEnd) != '.') {
            return null;
        }

        int minorStart = majorEnd + 1;
        int minorEnd = consumeDigits(value, minorStart);
        if (minorEnd == minorStart || minorEnd >= length || value.charAt(minorEnd) != '.') {
            return null;
        }

        int patchStart = minorEnd + 1;
        int patchEnd = consumeDigits(value, patchStart);
        if (patchEnd == patchStart) {
            return null;
        }

        int versionEnd = patchEnd;
        if (patchEnd < length && value.charAt(patchEnd) == '-') {
            int prereleaseStart = patchEnd + 1;
            if (prereleaseStart < length && isSemverPrereleaseStartChar(value.charAt(prereleaseStart))) {
                int prereleaseEnd = consumePrereleaseChars(value, prereleaseStart);
                if (prereleaseEnd > prereleaseStart) {
                    versionEnd = prereleaseEnd;
                }
            }
        }

        return value.substring(start, versionEnd);
    }

    private int consumeDigits(String value, int start) {
        int cursor = start;
        int length = value.length();
        while (cursor < length && isAsciiDigit(value.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private int consumePrereleaseChars(String value, int start) {
        int cursor = start;
        int length = value.length();
        while (cursor < length && isSemverPrereleaseChar(value.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private int comparePrerelease(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int max = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < max; index++) {
            if (index >= leftParts.length) {
                return -1;
            }
            if (index >= rightParts.length) {
                return 1;
            }

            String leftPart = leftParts[index];
            String rightPart = rightParts[index];
            boolean leftNumeric = leftPart.matches("\\d+");
            boolean rightNumeric = rightPart.matches("\\d+");
            if (leftNumeric && rightNumeric) {
                int comparison = Integer.compare(Integer.parseInt(leftPart), Integer.parseInt(rightPart));
                if (comparison != 0) {
                    return comparison;
                }
                continue;
            }
            if (leftNumeric) {
                return -1;
            }
            if (rightNumeric) {
                return 1;
            }

            int comparison = leftPart.compareTo(rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private Semver parseSemver(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String normalized = normalizeVersion(version);
        int plusIndex = normalized.indexOf('+');
        if (plusIndex >= 0) {
            normalized = normalized.substring(0, plusIndex);
        }
        Matcher matcher = SEMVER_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }
        return new Semver(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                matcher.group(4));
    }

    private boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private boolean isAsciiLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    private boolean isSemverPrereleaseChar(char value) {
        return isAsciiDigit(value) || isAsciiLetter(value) || value == '.' || value == '-';
    }

    private boolean isSemverPrereleaseStartChar(char value) {
        return isAsciiDigit(value) || isAsciiLetter(value);
    }

    private record Semver(int major, int minor, int patch, String preRelease) {
    }
}
