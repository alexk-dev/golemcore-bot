package me.golemcore.bot.domain.selfevolving.promotion;

import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import me.golemcore.bot.domain.selfevolving.artifact.ArtifactBundleService;

class PromotionExecutionServiceTest {

    @Test
    void shouldPromoteBundleAndReturnUpdatedCandidateState() {
        ArtifactBundleService artifactBundleService = mock(ArtifactBundleService.class);
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-1")
                .baseVersion("bundle-1")
                .status("shadowed")
                .lifecycleState("candidate")
                .rolloutStage("shadowed")
                .artifactType("skill")
                .artifactSubtype("skill")
                .artifactStreamId("stream-1")
                .originArtifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .contentRevisionId("revision-1")
                .baseContentRevisionId("revision-0")
                .build();
        when(artifactBundleService.promoteCandidateBundle(eq("candidate-1:canary"), eq(candidate), eq("canary")))
                .thenReturn(ArtifactBundleRecord.builder().id("bundle-promoted").build());
        PromotionExecutionService service = new PromotionExecutionService(
                artifactBundleService,
                Clock.fixed(Instant.parse("2026-04-05T22:00:00Z"), ZoneOffset.UTC));

        PromotionExecutionService.PromotionExecutionResult result = service.execute(
                candidate,
                new PromotionTarget("canary", "candidate", "canary"),
                "auto_accept");

        PromotionDecision decision = result.decision();
        assertEquals("bundle-promoted", decision.getBundleId());
        assertEquals("candidate", decision.getToLifecycleState());
        assertEquals("canary", decision.getToRolloutStage());
        assertEquals("auto_accept", decision.getMode());
        assertEquals("Advanced into canary rollout", decision.getReason());
        assertEquals("canary", result.updatedCandidate().getStatus());
        assertEquals("candidate", result.updatedCandidate().getLifecycleState());
        assertEquals("canary", result.updatedCandidate().getRolloutStage());
    }

    @Test
    void shouldCreateApprovalRequestForApprovedPendingTarget() {
        PromotionExecutionService service = new PromotionExecutionService(
                null,
                Clock.fixed(Instant.parse("2026-04-05T22:00:00Z"), ZoneOffset.UTC));
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-2")
                .baseVersion("bundle-2")
                .status("proposed")
                .lifecycleState("candidate")
                .rolloutStage("proposed")
                .artifactType("prompt")
                .artifactSubtype("prompt:section")
                .artifactStreamId("stream-2")
                .originArtifactStreamId("stream-2")
                .artifactKey("prompt:section")
                .contentRevisionId("revision-2")
                .build();

        PromotionExecutionService.PromotionExecutionResult result = service.execute(
                candidate,
                new PromotionTarget("approved_pending", "approved", "approved"),
                "approval_gate");

        assertEquals("candidate-2-approval", result.decision().getApprovalRequestId());
        assertEquals("Queued for approval", result.decision().getReason());
        assertEquals("approved_pending", result.updatedCandidate().getStatus());
        assertEquals("approved", result.updatedCandidate().getLifecycleState());
        assertEquals("approved", result.updatedCandidate().getRolloutStage());
        assertEquals("candidate-2:approved", result.decision().getBundleId());
    }
}
