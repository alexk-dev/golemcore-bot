package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvolutionArtifactIdentityServiceTest {

    private StoragePort storagePort;
    private EvolutionArtifactIdentityService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        when(storagePort.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(null));
        service = new EvolutionArtifactIdentityService(
                storagePort,
                Clock.fixed(Instant.parse("2026-04-05T18:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldReuseArtifactStreamIdentityAndDeduplicatePersistedRevision() {
        EvolutionCandidate first = service.ensureArtifactIdentity(EvolutionCandidate.builder()
                .id("first-candidate")
                .artifactType("tool policy")
                .status("proposed")
                .proposedDiff("selfevolving:fix:tool_policy")
                .evidenceRefs(List.of(VerdictEvidenceRef.builder().traceId("trace-1").spanId("span-1").build()))
                .build());
        EvolutionCandidate second = service.ensureArtifactIdentity(EvolutionCandidate.builder()
                .id("second-candidate")
                .artifactType("tool_policy")
                .status("proposed")
                .proposedDiff("selfevolving:fix:tool_policy")
                .evidenceRefs(List.of(VerdictEvidenceRef.builder().traceId("trace-2").spanId("span-2").build()))
                .build());

        assertEquals("tool_policy", first.getArtifactType());
        assertEquals("tool_policy:usage", first.getArtifactSubtype());
        assertEquals(first.getArtifactStreamId(), second.getArtifactStreamId());
        assertEquals(first.getContentRevisionId(), second.getContentRevisionId());
        assertNotNull(first.getContentRevisionId());
        assertEquals(1, service.getArtifactRevisionRecords().size());
        verify(storagePort, times(1)).putTextAtomic(eq("self-evolving"), eq("artifact-revisions.json"), anyString(),
                eq(true));
    }
}
