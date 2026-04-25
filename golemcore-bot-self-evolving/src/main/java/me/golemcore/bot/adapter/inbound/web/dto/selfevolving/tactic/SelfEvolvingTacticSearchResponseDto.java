package me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Workspace response for tactic search and browse mode.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelfEvolvingTacticSearchResponseDto {

    private String query;
    private SelfEvolvingTacticSearchStatusDto status;
    @Builder.Default
    private List<SelfEvolvingTacticSearchResultDto> results = new ArrayList<>();
}
