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
public class SelfEvolvingCampaignDto {
    private String id;
    private String suiteId;
    private String baselineBundleId;
    private String candidateBundleId;
    private String status;
    private String startedAt;
    private String completedAt;

    @Builder.Default
    private List<String> runIds = new ArrayList<>();
}
