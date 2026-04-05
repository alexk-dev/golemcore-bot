package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.EvolutionProposal;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import me.golemcore.bot.domain.selfevolving.artifact.ArtifactBundleService;
import me.golemcore.bot.domain.selfevolving.artifact.EvolutionArtifactIdentityService;
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionCandidateDerivationService;
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionCandidateService;
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionCandidateTacticMaterializer;
import me.golemcore.bot.domain.selfevolving.tactic.TacticRecordService;

class EvolutionCandidateArtifactIdentityTest {

    private EvolutionCandidateService service;

    @BeforeEach
    void setUp() {
        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(null));
        Clock clock = Clock.fixed(Instant.parse("2026-03-31T17:00:00Z"), ZoneOffset.UTC);
        service = new EvolutionCandidateService(
                mock(TacticRecordService.class),
                mock(ArtifactBundleService.class),
                new EvolutionArtifactIdentityService(storagePort, clock),
                new EvolutionCandidateDerivationService(clock),
                new EvolutionCandidateTacticMaterializer(clock));
    }

    @Test
    void shouldAssignCanonicalArtifactIdentityForFailedToolPolicyCandidate() {
        RunRecord runRecord = RunRecord.builder()
                .id("run-1")
                .golemId("golem-1")
                .artifactBundleId("bundle-1")
                .build();
        RunVerdict runVerdict = RunVerdict.builder()
                .outcomeStatus("FAILED")
                .processFindings(List.of("tool_error:tool.exec"))
                .evidenceRefs(List.of(VerdictEvidenceRef.builder().traceId("trace-1").spanId("tool-1").build()))
                .build();

        EvolutionCandidate candidate = service.deriveCandidates(runRecord, runVerdict).getFirst();

        assertEquals("tool_policy", candidate.getArtifactType());
        assertEquals("tool_policy:usage", candidate.getArtifactSubtype());
        assertEquals("tool_policy:usage", candidate.getArtifactKey());
        assertEquals(List.of("tool_policy:usage"), candidate.getArtifactAliases());
        assertEquals("candidate", candidate.getLifecycleState());
        assertEquals("proposed", candidate.getRolloutStage());
        assertNotNull(candidate.getArtifactStreamId());
        assertEquals(candidate.getArtifactStreamId(), candidate.getOriginArtifactStreamId());
        assertNotNull(candidate.getContentRevisionId());
    }

    @Test
    void shouldProduceSameContentRevisionIdForIdenticalSemanticCandidates() {
        EvolutionCandidate first = service.ensureArtifactIdentity(EvolutionCandidate.builder()
                .id("first-id")
                .artifactType("skill")
                .proposedDiff("selfevolving:derive:skill")
                .proposal(EvolutionProposal.builder()
                        .summary("Capture the planner tactic")
                        .behaviorInstructions("Reuse the planner sequence when needed")
                        .toolInstructions("Prefer planning")
                        .expectedOutcome("Fewer retries")
                        .build())
                .build());
        EvolutionCandidate second = service.ensureArtifactIdentity(EvolutionCandidate.builder()
                .id("second-id")
                .artifactType("skill")
                .proposedDiff("selfevolving:derive:skill")
                .proposal(EvolutionProposal.builder()
                        .summary("Capture the planner tactic")
                        .behaviorInstructions("Reuse the planner sequence when needed")
                        .toolInstructions("Prefer planning")
                        .expectedOutcome("Fewer retries")
                        .build())
                .build());

        assertNotNull(first.getContentRevisionId());
        assertEquals(first.getContentRevisionId(), second.getContentRevisionId());
    }

    @Test
    void shouldProduceDifferentContentRevisionIdWhenSemanticContentDiffers() {
        EvolutionCandidate first = service.ensureArtifactIdentity(EvolutionCandidate.builder()
                .id("first-id")
                .artifactType("skill")
                .proposedDiff("selfevolving:derive:skill")
                .proposal(EvolutionProposal.builder()
                        .summary("Capture the planner tactic")
                        .behaviorInstructions("Reuse the planner sequence when needed")
                        .build())
                .build());
        EvolutionCandidate second = service.ensureArtifactIdentity(EvolutionCandidate.builder()
                .id("second-id")
                .artifactType("skill")
                .proposedDiff("selfevolving:derive:skill")
                .proposal(EvolutionProposal.builder()
                        .summary("Capture a different planner tactic")
                        .behaviorInstructions("Reuse the planner sequence when needed")
                        .build())
                .build());

        assertNotEquals(first.getContentRevisionId(), second.getContentRevisionId());
    }
}
