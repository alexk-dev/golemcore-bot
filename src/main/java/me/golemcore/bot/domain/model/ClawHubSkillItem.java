package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Public ClawHub listing item used by the dashboard.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClawHubSkillItem {

    private String slug;
    private String displayName;
    private String summary;
    private String version;
    private Long updatedAt;
    private boolean installed;
    private String installedVersion;
    private String runtimeName;
}
