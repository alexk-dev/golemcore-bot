package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvolutionCandidateServiceTest {

    private EvolutionCandidateService evolutionCandidateService;

    @BeforeEach
    void setUp() {
        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        evolutionCandidateService = new EvolutionCandidateService(
                storagePort,
                Clock.fixed(Instant.parse("2026-03-31T16:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldCreateFixCandidateForFailedRunWithEvidenceRefs() {
        RunRecord runRecord = RunRecord.builder()
                .id("run-1")
                .golemId("golem-1")
                .build();
        RunVerdict verdict = RunVerdict.builder()
                .outcomeStatus("FAILED")
                .processFindings(List.of("tool_error:tool.exec"))
                .evidenceRefs(List.of(VerdictEvidenceRef.builder().traceId("trace-1").spanId("tool-1").build()))
                .build();

        List<EvolutionCandidate> candidates = evolutionCandidateService.deriveCandidates(runRecord, verdict);

        assertEquals(1, candidates.size());
        assertEquals("fix", candidates.getFirst().getGoal());
        assertEquals("tool_policy", candidates.getFirst().getArtifactType());
        assertEquals(List.of("run-1"), candidates.getFirst().getSourceRunIds());
    }

    @Test
    void shouldCreateDeriveCandidateForSuccessfulRunWithStrongEvidence() {
        RunRecord runRecord = RunRecord.builder()
                .id("run-2")
                .golemId("golem-1")
                .build();
        RunVerdict verdict = RunVerdict.builder()
                .outcomeStatus("COMPLETED")
                .processStatus("CLEAN")
                .confidence(0.91)
                .evidenceRefs(List.of(VerdictEvidenceRef.builder().traceId("trace-2").spanId("llm-1").build()))
                .build();

        List<EvolutionCandidate> candidates = evolutionCandidateService.deriveCandidates(runRecord, verdict);

        assertEquals(1, candidates.size());
        assertEquals("derive", candidates.getFirst().getGoal());
        assertEquals("skill", candidates.getFirst().getArtifactType());
        assertTrue(
                candidates.getFirst().getEvidenceRefs().stream().anyMatch(ref -> "trace-2".equals(ref.getTraceId())));
    }
}
