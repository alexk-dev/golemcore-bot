package me.golemcore.bot.domain.selfevolving.promotion;

import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void shouldReturnFalseWhenDecisionIsNull() {
        // Covers the early-return branch of hydrate(null, ...) — kills the
        // NO_COVERAGE mutation on `return false;`.
        PromotionDecisionHydrationService hydrationService = new PromotionDecisionHydrationService();

        boolean mutated = hydrationService.hydrate(null,
                EvolutionCandidate.builder().id("c").build());

        assertFalse(mutated);
    }

    @Test
    void shouldReturnFalseWhenDecisionIsAlreadyFullyPopulated() {
        // Kills the "replaced boolean return with true" mutation on
        // `return mutated;` — every identity/target field is already set so
        // hydrate takes no action and must report `false`.
        PromotionDecisionHydrationService hydrationService = new PromotionDecisionHydrationService();
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-x")
                .artifactType("routing_policy")
                .artifactSubtype("routing_policy:tier")
                .artifactStreamId("stream-x")
                .originArtifactStreamId("origin-x")
                .artifactKey("routing_policy:tier")
                .contentRevisionId("rev-x")
                .baseContentRevisionId("rev-base-x")
                .baseVersion("bundle-x")
                .lifecycleState("candidate")
                .rolloutStage("proposed")
                .build();
        PromotionDecision decision = PromotionDecision.builder()
                .id("decision-x")
                .candidateId("candidate-x")
                .artifactType("routing_policy")
                .artifactSubtype("routing_policy:tier")
                .artifactStreamId("stream-x")
                .originArtifactStreamId("origin-x")
                .artifactKey("routing_policy:tier")
                .contentRevisionId("rev-x")
                .baseContentRevisionId("rev-base-x")
                .originBundleId("bundle-x")
                .fromLifecycleState("candidate")
                .fromRolloutStage("proposed")
                .toState("active")
                .toLifecycleState("active")
                .toRolloutStage("active")
                .bundleId("candidate-x:active")
                .build();

        boolean mutated = hydrationService.hydrate(decision, candidate);

        assertFalse(mutated);
    }

    @Test
    void shouldHydrateLegacyToStateFromLifecycleAndRolloutFallback() {
        // Pins the `target.legacyState()` branch — when decision has no toState
        // but has toLifecycleState/toRolloutStage, the resolver must synthesize
        // a legacy state string and write it back to decision.toState.
        PromotionDecisionHydrationService hydrationService = new PromotionDecisionHydrationService();
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-legacy")
                .baseVersion("bundle-legacy")
                .lifecycleState("candidate")
                .rolloutStage("proposed")
                .build();
        PromotionDecision decision = PromotionDecision.builder()
                .id("decision-legacy")
                .candidateId("candidate-legacy")
                .toLifecycleState("active")
                .toRolloutStage("active")
                .build();

        boolean mutated = hydrationService.hydrate(decision, candidate);

        assertTrue(mutated);
        assertEquals("active", decision.getToState());
    }

    @Test
    void shouldFallBackBundleIdToCandidateBaseVersionWhenCandidateIdIsBlank() {
        // Covers buildTargetBundleId's `candidate.getId() isBlank` branch —
        // kills the NO_COVERAGE mutations on `return candidate.getBaseVersion()`.
        PromotionDecisionHydrationService hydrationService = new PromotionDecisionHydrationService();
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("   ")
                .baseVersion("bundle-fallback")
                .lifecycleState("candidate")
                .rolloutStage("proposed")
                .build();
        PromotionDecision decision = PromotionDecision.builder()
                .id("decision-blank-candidate")
                .candidateId("   ")
                .build();

        boolean mutated = hydrationService.hydrate(decision, candidate);

        assertTrue(mutated);
        assertEquals("bundle-fallback", decision.getBundleId());
    }
}
