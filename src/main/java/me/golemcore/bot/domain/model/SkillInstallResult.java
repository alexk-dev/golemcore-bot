package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result returned after a skill installation attempt.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillInstallResult {

    private String status;
    private String message;
    private SkillMarketplaceItem skill;
}
