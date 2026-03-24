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
    private String maintainer;
    private String maintainerDisplayName;
    private String artifactId;
    private String artifactType;
    private String version;
    private String modelTier;
    private String sourcePath;
    @Builder.Default
    private java.util.List<String> skillRefs = java.util.List.of();
    private int skillCount;
    private boolean installed;
    private boolean updateAvailable;
}
