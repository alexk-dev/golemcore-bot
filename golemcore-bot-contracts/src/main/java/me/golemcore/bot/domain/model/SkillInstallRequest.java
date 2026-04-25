package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Install request for marketplace-driven skill installation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillInstallRequest {

    private String skillId;
}
