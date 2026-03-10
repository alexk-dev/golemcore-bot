package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Public marketplace metadata for one installable skill.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkillMarketplaceItem {

    private String id;
    private String name;
    private String description;
    private String modelTier;
    private String sourcePath;
    private boolean installed;
    private boolean updateAvailable;
}
