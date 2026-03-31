package me.golemcore.bot.adapter.inbound.web.dto.selfevolving;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelfEvolvingCandidateDto {
    private String id;
    private String goal;
    private String artifactType;
    private String status;
    private String riskLevel;
    private String expectedImpact;

    @Builder.Default
    private List<String> sourceRunIds = new ArrayList<>();
}
