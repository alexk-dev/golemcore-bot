package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromotionDecisionHydrationServiceTest {

    @Test
    void shouldHydrateDecisionFieldsFromCandidateAndLegacyState() {
        PromotionDecisionHydrationService hydrationService = new PromotionDecisionHydrationService();
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-1")
                .artifactType("routing_policy")
                .artifactSubtype("routing_policy:tier")
                .artifactStreamId("stream-1")
                .originArtifactStreamId("origin-1")
                .artifactKey("routing_policy:tier")
                .contentRevisionId("revision-1")
                .baseContentRevisionId("revision-0")
                .baseVersion("bundle-1")
                .lifecycleState("candidate")
                .rolloutStage("shadowed")
                .build();
        PromotionDecision decision = PromotionDecision.builder()
                .id("decision-1")
                .candidateId("candidate-1")
                .toState("approved_pending")
                .build();

        boolean mutated = hydrationService.hydrate(decision, candidate);

        assertTrue(mutated);
        assertEquals("routing_policy", decision.getArtifactType());
        assertEquals("routing_policy:tier", decision.getArtifactSubtype());
        assertEquals("stream-1", decision.getArtifactStreamId());
        assertEquals("origin-1", decision.getOriginArtifactStreamId());
        assertEquals("routing_policy:tier", decision.getArtifactKey());
        assertEquals("revision-1", decision.getContentRevisionId());
        assertEquals("revision-0", decision.getBaseContentRevisionId());
        assertEquals("bundle-1", decision.getOriginBundleId());
        assertEquals("candidate", decision.getFromLifecycleState());
        assertEquals("shadowed", decision.getFromRolloutStage());
        assertEquals("approved", decision.getToLifecycleState());
        assertEquals("approved", decision.getToRolloutStage());
        assertEquals("candidate-1:approved", decision.getBundleId());
    }

    @Test
    void shouldResolveLifecycleAndRolloutFallbacksWithoutLegacyState() {
        PromotionDecisionHydrationService hydrationService = new PromotionDecisionHydrationService();
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-2")
                .baseVersion("bundle-2")
                .lifecycleState("candidate")
                .rolloutStage("proposed")
                .build();
        PromotionDecision decision = PromotionDecision.builder()
                .id("decision-2")
                .candidateId("candidate-2")
                .toRolloutStage("canary")
                .build();

        boolean mutated = hydrationService.hydrate(decision, candidate);

        assertTrue(mutated);
        assertEquals("candidate", decision.getToLifecycleState());
        assertEquals("canary", decision.getToRolloutStage());
        assertEquals("candidate-2:canary", decision.getBundleId());
    }

    @Test
    void shouldHydrateDecisionTargetDefaultsWithoutCandidate() {
        PromotionDecisionHydrationService hydrationService = new PromotionDecisionHydrationService();
        PromotionDecision decision = PromotionDecision.builder()
                .id("decision-3")
                .candidateId("missing")
                .build();

        boolean mutated = hydrationService.hydrate(decision, null);

        assertTrue(mutated);
        assertEquals("candidate", decision.getToLifecycleState());
        assertEquals("proposed", decision.getToRolloutStage());
        assertNull(decision.getBundleId());
    }
}
