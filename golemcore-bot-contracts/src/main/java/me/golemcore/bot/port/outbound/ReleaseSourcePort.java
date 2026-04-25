package me.golemcore.bot.port.outbound;

import me.golemcore.bot.domain.model.AvailableRelease;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Port for discovering and downloading application releases from an external
 * source (Maven Central, GitHub Releases, etc.).
 */
public interface ReleaseSourcePort {

    /**
     * Unique name of this release source (e.g. "maven-central", "github").
     */
    String name();

    /**
     * Look up the latest available release.
     *
     * @return the latest release, or empty if none found
     */
    Optional<AvailableRelease> fetchLatestRelease() throws IOException, InterruptedException;

    /**
     * Download the release JAR as a stream.
     *
     * @param release
     *            the release to download
     * @return input stream of the JAR binary
     */
    InputStream downloadAsset(AvailableRelease release) throws IOException, InterruptedException;

    /**
     * Download the checksum for the release.
     *
     * @param release
     *            the release whose checksum to fetch
     * @return checksum info containing the hex digest, algorithm, and asset name
     */
    ChecksumInfo downloadChecksum(AvailableRelease release) throws IOException, InterruptedException;

    /**
     * Whether this source is currently enabled.
     */
    boolean isEnabled();

    /**
     * Checksum information returned by a release source.
     *
     * @param hexDigest
     *            the checksum hex string
     * @param algorithm
     *            the digest algorithm (e.g. "SHA-256", "SHA-1")
     * @param assetName
     *            the asset name the checksum applies to
     */
    record ChecksumInfo(String hexDigest, String algorithm, String assetName) {
    }
}
