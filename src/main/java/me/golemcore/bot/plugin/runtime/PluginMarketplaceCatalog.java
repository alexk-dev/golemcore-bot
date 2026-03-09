package me.golemcore.bot.plugin.runtime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Public marketplace view used by the dashboard.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PluginMarketplaceCatalog {

    private boolean available;
    private String message;
    private String sourceDirectory;
    @Builder.Default
    private List<PluginMarketplaceItem> items = List.of();
}
