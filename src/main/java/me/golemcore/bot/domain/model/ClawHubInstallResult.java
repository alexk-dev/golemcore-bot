package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result returned after a ClawHub installation attempt.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClawHubInstallResult {

    private String status;
    private String message;
    private ClawHubSkillItem skill;
}
