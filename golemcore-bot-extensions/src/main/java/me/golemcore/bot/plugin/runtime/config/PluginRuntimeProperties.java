package me.golemcore.bot.plugin.runtime.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Extension platform runtime settings bound from {@code bot.plugins.*}.
 */
@Component
@ConfigurationProperties(prefix = "bot.plugins")
@Data
public class PluginRuntimeProperties {

    private boolean enabled = true;
    private boolean autoStart = true;
    private boolean autoReload = true;
    private String directory = "${bot.storage.local.base-path}/plugins";
    private Duration pollInterval = Duration.ofSeconds(5);
    private MarketplaceProperties marketplace = new MarketplaceProperties();

    @Data
    public static class MarketplaceProperties {
        private boolean enabled = true;
        private String repositoryDirectory = "";
        private String repositoryUrl = "https://github.com/alexk-dev/golemcore-plugins";
        private String branch = "main";
        private String apiBaseUrl = "https://api.github.com";
        private String rawBaseUrl = "https://raw.githubusercontent.com";
        private Duration remoteCacheTtl = Duration.ofMinutes(5);
    }
}
