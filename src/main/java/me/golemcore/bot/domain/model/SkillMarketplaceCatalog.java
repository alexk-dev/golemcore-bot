package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Public marketplace view used by the dashboard for skills.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkillMarketplaceCatalog {

    private boolean available;
    private String message;
    private String sourceType;
    private String sourceDirectory;
    @Builder.Default
    private List<SkillMarketplaceItem> items = List.of();
}
