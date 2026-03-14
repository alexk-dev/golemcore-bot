package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Install request for ClawHub skills.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClawHubInstallRequest {

    private String slug;
    private String version;
}
