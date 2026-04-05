package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.EvolutionProposal;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvolutionCandidateDerivationServiceTest {

    @Test
    void shouldCreateFixCandidateWithToolPolicyFallbackDiff() {
        EvolutionCandidateDerivationService derivationService = new EvolutionCandidateDerivationService(
                Clock.fixed(Instant.parse("2026-04-05T20:00:00Z"), ZoneOffset.UTC));

        List<EvolutionCandidate> candidates = derivationService.deriveCandidates(
                RunRecord.builder().id("run-fix").golemId("golem-1").artifactBundleId("bundle-1").build(),
                RunVerdict.builder()
                        .outcomeStatus("FAILED")
                        .processFindings(List.of("tool_error:tool.exec"))
                        .evidenceRefs(List.of(VerdictEvidenceRef.builder()
                                .outputFragment("shell command failed because the binary was missing")
                                .build()))
                        .build(),
                null);

        assertEquals(1, candidates.size());
        assertEquals("fix", candidates.getFirst().getGoal());
        assertEquals("tool_policy", candidates.getFirst().getArtifactType());
        assertEquals("selfevolving:fix:tool_policy", candidates.getFirst().getProposedDiff());
        assertEquals("high", candidates.getFirst().getRiskLevel());
        assertEquals(Instant.parse("2026-04-05T20:00:00Z"), candidates.getFirst().getCreatedAt());
    }

    @Test
    void shouldDeriveRoutingPolicyCandidateFromProposalHint() {
        EvolutionCandidateDerivationService derivationService = new EvolutionCandidateDerivationService(
                Clock.fixed(Instant.parse("2026-04-05T20:00:00Z"), ZoneOffset.UTC));

        List<EvolutionCandidate> candidates = derivationService.deriveCandidates(
                RunRecord.builder().id("run-derive").golemId("golem-1").artifactBundleId("bundle-2").build(),
                RunVerdict.builder()
                        .outcomeStatus("COMPLETED")
                        .processStatus("CLEAN")
                        .confidence(0.94)
                        .evidenceRefs(List.of(VerdictEvidenceRef.builder()
                                .outputFragment("Escalating the model tier solved the task cleanly")
                                .build()))
                        .build(),
                EvolutionProposal.builder()
                        .summary("Capture routing policy for model escalation")
                        .behaviorInstructions("Route deep reasoning tasks to the stronger model tier.")
                        .expectedOutcome("Reuse the successful escalation heuristic.")
                        .build());

        assertEquals(1, candidates.size());
        assertEquals("derive", candidates.getFirst().getGoal());
        assertEquals("routing_policy", candidates.getFirst().getArtifactType());
        assertEquals("Reuse the successful escalation heuristic.", candidates.getFirst().getExpectedImpact());
        assertEquals("medium", candidates.getFirst().getRiskLevel());
        assertEquals("selfevolving:derive:routing_policy", candidates.getFirst().getProposedDiff());
    }

    @Test
    void shouldSkipDerivationWhenEvidenceRefsAreMissing() {
        EvolutionCandidateDerivationService derivationService = new EvolutionCandidateDerivationService(
                Clock.fixed(Instant.parse("2026-04-05T20:00:00Z"), ZoneOffset.UTC));

        List<EvolutionCandidate> candidates = derivationService.deriveCandidates(
                RunRecord.builder().id("run-empty").golemId("golem-1").build(),
                RunVerdict.builder()
                        .outcomeStatus("FAILED")
                        .processFindings(List.of("tool_error:tool.exec"))
                        .build(),
                null);

        assertTrue(candidates.isEmpty());
    }
}
