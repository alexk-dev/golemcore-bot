package me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Search status and degradation metadata for the tactic workspace.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelfEvolvingTacticSearchStatusDto {

    private String mode;
    private String reason;
    private Boolean degraded;
    private String updatedAt;
}
