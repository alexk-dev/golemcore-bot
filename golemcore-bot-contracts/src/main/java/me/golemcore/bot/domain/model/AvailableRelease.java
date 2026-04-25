package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Describes a release discovered from a release source (Maven Central, GitHub,
 * etc.).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AvailableRelease {
    private String version;
    private String tagName;
    private String assetName;
    private String downloadUrl;
    private String checksumUrl;
    private Instant publishedAt;
    private String source;
}
