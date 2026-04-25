package me.golemcore.bot.adapter.inbound.web.dto.selfevolving;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelfEvolvingPromotionDecisionDto {
    private String id;
    private String candidateId;
    private String bundleId;
    private String state;
    private String fromState;
    private String toState;
    private String mode;
    private String approvalRequestId;
    private String actorId;
    private String reason;
    private String decidedAt;
}
