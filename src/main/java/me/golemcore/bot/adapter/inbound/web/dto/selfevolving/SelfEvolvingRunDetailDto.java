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
public class SelfEvolvingRunDetailDto {
    private String id;
    private String golemId;
    private String sessionId;
    private String traceId;
    private String artifactBundleId;
    private String artifactBundleStatus;
    private String status;
    private String startedAt;
    private String completedAt;
    private VerdictDto verdict;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerdictDto {
        private String outcomeStatus;
        private String processStatus;
        private String outcomeSummary;
        private String processSummary;
        private String promotionRecommendation;
        private Double confidence;

        @Builder.Default
        private List<String> processFindings = new ArrayList<>();
    }
}
