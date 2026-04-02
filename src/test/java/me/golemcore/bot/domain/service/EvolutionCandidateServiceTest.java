package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvolutionCandidateServiceTest {

    private EvolutionCandidateService evolutionCandidateService;
    private Map<String, String> persistedFiles;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        StoragePort storagePort = mock(StoragePort.class);
        persistedFiles = new ConcurrentHashMap<>();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        when(storagePort.getText(anyString(), anyString())).thenAnswer(invocation -> {
            String fileName = invocation.getArgument(1);
            return CompletableFuture.completedFuture(persistedFiles.get(fileName));
        });
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String fileName = invocation.getArgument(1);
                    String content = invocation.getArgument(2);
                    persistedFiles.put(fileName, content);
                    return CompletableFuture.completedFuture(null);
                });
        evolutionCandidateService = new EvolutionCandidateService(
                storagePort,
                mock(TacticRecordService.class),
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

    @Test
    void shouldSkipCandidateDerivationWhenEvidenceRefsAreMissing() {
        RunRecord runRecord = RunRecord.builder()
                .id("run-empty")
                .golemId("golem-1")
                .build();
        RunVerdict verdict = RunVerdict.builder()
                .outcomeStatus("FAILED")
                .processFindings(List.of("tool_error:tool.exec"))
                .build();

        List<EvolutionCandidate> candidates = evolutionCandidateService.deriveCandidates(runRecord, verdict);

        assertTrue(candidates.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "tool_error:tool.exec,tool_policy",
            "skill_transition:planner,skill",
            "tier_upgrade:premium,routing_policy",
            "needs_prompt_rewrite,prompt"
    })
    void shouldResolveFixArtifactTypesFromProcessFindings(String finding, String expectedArtifactType) {
        RunRecord runRecord = RunRecord.builder()
                .id("run-fix")
                .golemId("golem-1")
                .build();
        RunVerdict verdict = RunVerdict.builder()
                .outcomeStatus("FAILED")
                .processFindings(List.of(finding))
                .evidenceRefs(List.of(VerdictEvidenceRef.builder().traceId("trace-fix").spanId("span-fix").build()))
                .build();

        List<EvolutionCandidate> candidates = evolutionCandidateService.deriveCandidates(runRecord, verdict);

        assertEquals(expectedArtifactType, candidates.getFirst().getArtifactType());
    }

    @ParameterizedTest
    @CsvSource({
            "routing policy,replayed,routing_policy,routing_policy:tier,routing_policy:tier,candidate,replayed,replayed",
            "tool policy,canary,tool_policy,tool_policy:usage,tool_policy:usage,candidate,canary,canary",
            "memory policy,reverted,memory_policy,memory_policy:retrieval,memory_policy:retrieval,reverted,reverted,reverted",
            "approval policy preset,approved_pending,governance_policy,governance_policy:approval,governance_policy:approval,approved,approved,approved_pending",
            "context assembly policy,shadowed,context_policy,context_policy:assembly,context_policy:assembly,candidate,shadowed,shadowed"
    })
    void shouldNormalizeCanonicalArtifactIdentityAcrossSupportedAliases(
            String inputType,
            String inputStatus,
            String expectedType,
            String expectedSubtype,
            String expectedKey,
            String expectedLifecycle,
            String expectedRollout,
            String expectedStatus) {
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-" + expectedType)
                .artifactType(inputType)
                .status(inputStatus)
                .createdAt(Instant.parse("2026-03-31T16:30:00Z"))
                .build();

        EvolutionCandidate normalized = evolutionCandidateService.ensureArtifactIdentity(candidate);

        assertEquals(expectedType, normalized.getArtifactType());
        assertEquals(expectedSubtype, normalized.getArtifactSubtype());
        assertEquals(expectedKey, normalized.getArtifactKey());
        assertEquals(expectedLifecycle, normalized.getLifecycleState());
        assertEquals(expectedRollout, normalized.getRolloutStage());
        assertEquals(expectedStatus, normalized.getStatus());
        assertNotNull(normalized.getArtifactStreamId());
        assertNotNull(normalized.getContentRevisionId());
    }

    @Test
    void shouldReuseLatestArtifactRevisionIdentityForNewCandidatesInSameStream() {
        EvolutionCandidate activeCandidate = EvolutionCandidate.builder()
                .id("candidate-base")
                .artifactType("memory policy")
                .status("active")
                .createdAt(Instant.parse("2026-03-31T16:00:00Z"))
                .build();
        EvolutionCandidate nextCandidate = EvolutionCandidate.builder()
                .id("candidate-next")
                .artifactType("memory policy")
                .status("shadowed")
                .createdAt(Instant.parse("2026-03-31T16:30:00Z"))
                .build();

        EvolutionCandidate first = evolutionCandidateService.ensureArtifactIdentity(activeCandidate);
        EvolutionCandidate second = evolutionCandidateService.ensureArtifactIdentity(nextCandidate);

        assertEquals(first.getArtifactStreamId(), second.getArtifactStreamId());
        assertEquals(first.getOriginArtifactStreamId(), second.getOriginArtifactStreamId());
        assertEquals(first.getContentRevisionId(), second.getBaseContentRevisionId());
        assertEquals(2, evolutionCandidateService.getArtifactRevisionRecords().size());
        assertTrue(
                persistedFiles.get("artifact-revisions.json").contains("\"artifactKey\":\"memory_policy:retrieval\""));
    }

    @Test
    void shouldLoadStoredArtifactRevisionsAndFallbackWhenJsonIsInvalid() throws Exception {
        ArtifactRevisionRecord record = ArtifactRevisionRecord.builder()
                .artifactStreamId("stream-1")
                .originArtifactStreamId("origin-1")
                .artifactKey("skill:default")
                .artifactType("skill")
                .artifactSubtype("skill")
                .contentRevisionId("revision-1")
                .createdAt(Instant.parse("2026-03-31T15:00:00Z"))
                .build();
        persistedFiles.put("artifact-revisions.json", objectMapper.writeValueAsString(List.of(record)));

        List<ArtifactRevisionRecord> records = evolutionCandidateService.getArtifactRevisionRecords();

        assertEquals(1, records.size());
        assertEquals("revision-1", records.getFirst().getContentRevisionId());

        persistedFiles.put("artifact-revisions.json", "{");
        EvolutionCandidateService reloadedService = new EvolutionCandidateService(
                mockReloadingStoragePort(),
                mock(TacticRecordService.class),
                Clock.fixed(Instant.parse("2026-03-31T16:30:00Z"), ZoneOffset.UTC));
        assertTrue(reloadedService.getArtifactRevisionRecords().isEmpty());
    }

    private StoragePort mockReloadingStoragePort() {
        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.getText(anyString(), anyString())).thenAnswer(invocation -> {
            String fileName = invocation.getArgument(1);
            return CompletableFuture.completedFuture(persistedFiles.get(fileName));
        });
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String fileName = invocation.getArgument(1);
                    String content = invocation.getArgument(2);
                    persistedFiles.put(fileName, content);
                    return CompletableFuture.completedFuture(null);
                });
        return storagePort;
    }
}
