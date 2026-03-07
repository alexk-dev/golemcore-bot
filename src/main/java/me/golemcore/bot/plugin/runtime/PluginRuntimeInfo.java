package me.golemcore.bot.plugin.runtime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Public runtime status for one loaded plugin artifact.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PluginRuntimeInfo {

    private String id;
    private String name;
    private String provider;
    private String version;
    private Integer pluginApiVersion;
    private String engineVersion;
    private String jarPath;
    private boolean loaded;
}
