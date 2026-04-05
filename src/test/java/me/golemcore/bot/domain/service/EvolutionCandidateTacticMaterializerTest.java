package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.EvolutionProposal;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvolutionCandidateTacticMaterializerTest {

    @Test
    void shouldSkipPlaceholderOnlyCandidateWithoutSemanticProposalContent() {
        EvolutionCandidateTacticMaterializer materializer = new EvolutionCandidateTacticMaterializer(
                Clock.fixed(Instant.parse("2026-04-05T19:00:00Z"), ZoneOffset.UTC));

        Optional<TacticRecord> tactic = materializer.materialize(EvolutionCandidate.builder()
                .contentRevisionId("candidate-1")
                .artifactStreamId("stream-1")
                .originArtifactStreamId("stream-1")
                .artifactKey("skill:default")
                .artifactType("skill")
                .goal("derive")
                .proposedDiff("selfevolving:derive:skill")
                .build());

        assertTrue(tactic.isEmpty());
    }

    @Test
    void shouldBuildTacticRecordFromStructuredProposalAndEvidence() {
        EvolutionCandidateTacticMaterializer materializer = new EvolutionCandidateTacticMaterializer(
                Clock.fixed(Instant.parse("2026-04-05T19:00:00Z"), ZoneOffset.UTC));

        TacticRecord tactic = materializer.materialize(EvolutionCandidate.builder()
                .contentRevisionId("candidate-2")
                .artifactStreamId("stream-2")
                .originArtifactStreamId("origin-2")
                .artifactKey("skill:planner-sequence")
                .artifactAliases(List.of("skill:planner-sequence"))
                .artifactType("skill")
                .goal("derive")
                .lifecycleState("active")
                .rolloutStage("active")
                .createdAt(Instant.parse("2026-04-05T18:30:00Z"))
                .expectedImpact("Promote planner tactic")
                .proposedDiff("selfevolving:derive:skill")
                .proposal(EvolutionProposal.builder()
                        .summary("Capture the successful planner tactic as reusable guidance")
                        .behaviorInstructions(
                                "Reuse the planner sequence when the task requires stepwise decomposition.")
                        .toolInstructions("Prefer planning before tool execution.")
                        .expectedOutcome("Increase success on multi-step tasks.")
                        .approvalNotes("Promoted after review.")
                        .build())
                .evidenceRefs(List.of(
                        VerdictEvidenceRef.builder().outputFragment("planner tactic worked").build(),
                        VerdictEvidenceRef.builder().spanId("span-2").build()))
                .build()).orElseThrow();

        assertEquals("candidate-2", tactic.getTacticId());
        assertEquals("Skill planner-sequence", tactic.getTitle());
        assertEquals("Capture the successful planner tactic as reusable guidance", tactic.getIntentSummary());
        assertEquals("Reuse the planner sequence when the task requires stepwise decomposition.",
                tactic.getBehaviorSummary());
        assertEquals("Prefer planning before tool execution.", tactic.getToolSummary());
        assertEquals("Increase success on multi-step tasks.", tactic.getOutcomeSummary());
        assertEquals("Promoted after review.", tactic.getApprovalNotes());
        assertEquals(List.of("planner tactic worked", "span:span-2"), tactic.getEvidenceSnippets());
        assertEquals(List.of("skill"), tactic.getTaskFamilies());
        assertEquals(List.of("skill", "active", "derive"), tactic.getTags());
        assertEquals("active", tactic.getPromotionState());
        assertEquals("active", tactic.getRolloutStage());
        assertFalse(tactic.getUpdatedAt().isAfter(Instant.parse("2026-04-05T18:30:00Z")));
    }
}
