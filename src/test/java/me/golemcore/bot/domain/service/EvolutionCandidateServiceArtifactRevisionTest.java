package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvolutionCandidateServiceArtifactRevisionTest {

    private StoragePort storagePort;
    private EvolutionCandidateService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        when(storagePort.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        service = new EvolutionCandidateService(
                storagePort,
                Clock.fixed(Instant.parse("2026-03-31T18:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldPersistArtifactRevisionRecordsAndReuseStreamIdentityAcrossCandidates() {
        EvolutionCandidate firstCandidate = service.deriveCandidates(
                RunRecord.builder().id("run-1").golemId("golem-1").artifactBundleId("bundle-1").build(),
                RunVerdict.builder()
                        .outcomeStatus("FAILED")
                        .processFindings(List.of("tool_error:tool.exec"))
                        .evidenceRefs(List.of(VerdictEvidenceRef.builder().traceId("trace-1").spanId("tool-1").build()))
                        .build())
                .getFirst();

        EvolutionCandidate secondCandidate = service.deriveCandidates(
                RunRecord.builder().id("run-2").golemId("golem-1").artifactBundleId("bundle-2").build(),
                RunVerdict.builder()
                        .outcomeStatus("FAILED")
                        .processFindings(List.of("tool_error:tool.exec"))
                        .evidenceRefs(List.of(VerdictEvidenceRef.builder().traceId("trace-2").spanId("tool-2").build()))
                        .build())
                .getFirst();

        List<ArtifactRevisionRecord> records = service.getArtifactRevisionRecords();

        assertEquals(2, records.size());
        assertEquals(firstCandidate.getArtifactStreamId(), secondCandidate.getArtifactStreamId());
        assertNull(firstCandidate.getBaseContentRevisionId());
        assertEquals(firstCandidate.getContentRevisionId(), secondCandidate.getBaseContentRevisionId());
        verify(storagePort, times(2)).putText(eq("self-evolving"), eq("artifact-revisions.json"), anyString());
    }
}
