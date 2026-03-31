package me.golemcore.bot.adapter.inbound.web.dto.selfevolving;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelfEvolvingRunSummaryDto {
    private String id;
    private String golemId;
    private String sessionId;
    private String traceId;
    private String artifactBundleId;
    private String status;
    private String outcomeStatus;
    private String promotionRecommendation;
    private String startedAt;
    private String completedAt;
}
