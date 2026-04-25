package me.golemcore.bot.plugin.runtime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Marketplace metadata for one installable plugin.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PluginMarketplaceItem {

    private String id;
    private String provider;
    private String name;
    private String description;
    private String version;
    private Integer pluginApiVersion;
    private String engineVersion;
    private String sourceUrl;
    private String license;
    @Builder.Default
    private List<String> maintainers = List.of();
    private boolean official;
    private boolean compatible;
    private boolean artifactAvailable;
    private boolean installed;
    private boolean loaded;
    private boolean updateAvailable;
    private String installedVersion;
    private String loadedVersion;
    private String settingsRouteKey;
}
