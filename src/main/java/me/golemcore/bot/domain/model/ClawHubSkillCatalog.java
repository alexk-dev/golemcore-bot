package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Public ClawHub catalog view for the dashboard.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClawHubSkillCatalog {

    private boolean available;
    private String message;
    private String siteUrl;
    @Builder.Default
    private List<ClawHubSkillItem> items = List.of();
}
