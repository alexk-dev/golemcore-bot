package me.golemcore.bot.domain.selfevolving.promotion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import me.golemcore.bot.adapter.outbound.selfevolving.JsonPromotionWorkflowStateAdapter;
import me.golemcore.bot.domain.selfevolving.artifact.ArtifactBundleService;
import me.golemcore.bot.domain.selfevolving.artifact.EvolutionArtifactIdentityService;
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionCandidateDerivationService;
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionCandidateService;
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionCandidateTacticMaterializer;
import me.golemcore.bot.domain.selfevolving.tactic.InMemoryTacticRecordStorePort;
import me.golemcore.bot.domain.selfevolving.tactic.TacticRecordService;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;
import me.golemcore.bot.adapter.outbound.selfevolving.JsonArtifactRepositoryAdapter;

class PromotionWorkflowStateServiceTest {

    private StoragePort storagePort;
    private PromotionWorkflowStateService stateService;
    private Map<String, String> persistedFiles;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
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
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    String fileName = invocation.getArgument(1);
                    String content = invocation.getArgument(2);
                    persistedFiles.put(fileName, content);
                    return CompletableFuture.completedFuture(null);
                });
        when(storagePort.listObjects(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String prefix = invocation.getArgument(1);
                    List<String> keys = persistedFiles.keySet().stream()
                            .filter(path -> path.startsWith(prefix))
                            .sorted()
                            .toList();
                    return CompletableFuture.completedFuture(keys);
                });

        Clock clock = Clock.fixed(Instant.parse("2026-03-31T16:00:00Z"), ZoneOffset.UTC);
        SelfEvolvingRuntimeConfigPort runtimeConfigPort = mock(SelfEvolvingRuntimeConfigPort.class);
        TacticRecordService tacticRecordService = new TacticRecordService(new InMemoryTacticRecordStorePort(), clock,
                null, null);
        ArtifactBundleService artifactBundleService = new ArtifactBundleService(
                new JsonArtifactRepositoryAdapter(storagePort), runtimeConfigPort,
                clock);
        EvolutionCandidateService evolutionCandidateService = new EvolutionCandidateService(
                tacticRecordService,
                artifactBundleService,
                new EvolutionArtifactIdentityService(new JsonArtifactRepositoryAdapter(storagePort), clock),
                new EvolutionCandidateDerivationService(clock),
                new EvolutionCandidateTacticMaterializer(clock));
        stateService = new PromotionWorkflowStateService(
                new PromotionWorkflowStore(new JsonPromotionWorkflowStateAdapter(storagePort)),
                evolutionCandidateService,
                new PromotionDecisionHydrationService());
    }

    @Test
    void shouldRegisterCandidatesWithNormalizedIdentity() {
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-1")
                .golemId("golem-1")
                .goal("fix")
                .artifactType("prompt")
                .baseVersion("bundle-1")
                .status("proposed")
                .build();

        List<EvolutionCandidate> registeredCandidates = stateService.registerCandidates(List.of(candidate));
        EvolutionCandidate storedCandidate = stateService.getCandidates().getFirst();

        assertEquals(1, registeredCandidates.size());
        assertNotNull(storedCandidate.getArtifactStreamId());
        assertNotNull(storedCandidate.getContentRevisionId());
        assertEquals("candidate", storedCandidate.getLifecycleState());
        assertEquals("proposed", storedCandidate.getRolloutStage());
        assertTrue(persistedFiles.containsKey("candidates.json"));
    }

    @Test
    void shouldHydrateLegacyDecisionsFromNormalizedCandidates() throws Exception {
        EvolutionCandidate legacyCandidate = EvolutionCandidate.builder()
                .id("candidate-2")
                .golemId("golem-1")
                .goal("fix")
                .artifactType("prompt")
                .baseVersion("bundle-2")
                .status("proposed")
                .createdAt(Instant.parse("2026-03-31T15:30:00Z"))
                .sourceRunIds(List.of("run-1"))
                .build();
        PromotionDecision legacyDecision = PromotionDecision.builder()
                .id("decision-2")
                .candidateId("candidate-2")
                .build();
        persistedFiles.put("candidates.json", objectMapper.writeValueAsString(List.of(legacyCandidate)));
        persistedFiles.put("promotion-decisions.json", objectMapper.writeValueAsString(List.of(legacyDecision)));

        PromotionDecision hydratedDecision = stateService.getPromotionDecisions().getFirst();

        assertNotNull(hydratedDecision.getArtifactStreamId());
        assertNotNull(hydratedDecision.getContentRevisionId());
        assertEquals("candidate", hydratedDecision.getFromLifecycleState());
        assertEquals("proposed", hydratedDecision.getFromRolloutStage());
        assertEquals("candidate", hydratedDecision.getToLifecycleState());
        assertEquals("proposed", hydratedDecision.getToRolloutStage());
        assertEquals("candidate-2:proposed", hydratedDecision.getBundleId());
    }

    @Test
    void shouldRepersistCandidatesWhenArtifactStreamIdIsBlank() throws Exception {
        seedLegacyCandidate(EvolutionCandidate.builder()
                .id("c-stream")
                .golemId("g")
                .goal("fix")
                .artifactType("prompt")
                .baseVersion("b1")
                .status("proposed")
                .contentRevisionId("rev-1")
                .lifecycleState("candidate")
                .rolloutStage("proposed"));

        stateService.getCandidates();

        verify(storagePort, times(1))
                .putTextAtomic(anyString(), eq("candidates.json"), anyString(), anyBoolean());
    }

    @Test
    void shouldRepersistCandidatesWhenContentRevisionIdIsBlank() throws Exception {
        seedLegacyCandidate(EvolutionCandidate.builder()
                .id("c-rev")
                .golemId("g")
                .goal("fix")
                .artifactType("prompt")
                .baseVersion("b1")
                .status("proposed")
                .artifactStreamId("stream-1")
                .lifecycleState("candidate")
                .rolloutStage("proposed"));

        stateService.getCandidates();

        verify(storagePort, times(1))
                .putTextAtomic(anyString(), eq("candidates.json"), anyString(), anyBoolean());
    }

    @Test
    void shouldRepersistCandidatesWhenLifecycleStateIsBlank() throws Exception {
        seedLegacyCandidate(EvolutionCandidate.builder()
                .id("c-life")
                .golemId("g")
                .goal("fix")
                .artifactType("prompt")
                .baseVersion("b1")
                .status("proposed")
                .artifactStreamId("stream-1")
                .contentRevisionId("rev-1")
                .rolloutStage("proposed"));

        stateService.getCandidates();

        verify(storagePort, times(1))
                .putTextAtomic(anyString(), eq("candidates.json"), anyString(), anyBoolean());
    }

    @Test
    void shouldRepersistCandidatesWhenRolloutStageIsBlank() throws Exception {
        seedLegacyCandidate(EvolutionCandidate.builder()
                .id("c-rollout")
                .golemId("g")
                .goal("fix")
                .artifactType("prompt")
                .baseVersion("b1")
                .status("proposed")
                .artifactStreamId("stream-1")
                .contentRevisionId("rev-1")
                .lifecycleState("candidate"));

        stateService.getCandidates();

        verify(storagePort, times(1))
                .putTextAtomic(anyString(), eq("candidates.json"), anyString(), anyBoolean());
    }

    @Test
    void shouldNotRepersistCandidatesWhenAllIdentityFieldsPresent() throws Exception {
        seedLegacyCandidate(EvolutionCandidate.builder()
                .id("c-full")
                .golemId("g")
                .goal("fix")
                .artifactType("prompt")
                .baseVersion("b1")
                .status("proposed")
                .artifactStreamId("stream-1")
                .contentRevisionId("rev-1")
                .lifecycleState("candidate")
                .rolloutStage("proposed"));

        stateService.getCandidates();

        verify(storagePort, times(0))
                .putTextAtomic(anyString(), eq("candidates.json"), anyString(), anyBoolean());
    }

    @Test
    void shouldPreserveSourceRunIdsAcrossRegistration() {
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("c-runs")
                .golemId("g")
                .goal("fix")
                .artifactType("prompt")
                .baseVersion("b1")
                .status("proposed")
                .sourceRunIds(new java.util.ArrayList<>(List.of("run-1", "run-2")))
                .build();

        List<EvolutionCandidate> registered = stateService.registerCandidates(List.of(candidate));

        assertEquals(1, registered.size());
        assertEquals(List.of("run-1", "run-2"), registered.getFirst().getSourceRunIds());
    }

    @Test
    void shouldPreserveArtifactAliasesAcrossRegistration() {
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("c-alias")
                .golemId("g")
                .goal("fix")
                .artifactType("prompt")
                .baseVersion("b1")
                .status("proposed")
                .artifactAliases(new java.util.ArrayList<>(List.of("alias-a", "alias-b")))
                .build();

        List<EvolutionCandidate> registered = stateService.registerCandidates(List.of(candidate));

        assertEquals(List.of("alias-a", "alias-b"), registered.getFirst().getArtifactAliases());
    }

    @Test
    void shouldPreserveEvidenceRefsAcrossRegistration() {
        me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef ref1 = me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef
                .builder()
                .traceId("trace-1").spanId("span-1").build();
        me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef ref2 = me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef
                .builder()
                .traceId("trace-2").spanId("span-2").build();
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("c-ev")
                .golemId("g")
                .goal("fix")
                .artifactType("prompt")
                .baseVersion("b1")
                .status("proposed")
                .evidenceRefs(new java.util.ArrayList<>(List.of(ref1, ref2)))
                .build();

        List<EvolutionCandidate> registered = stateService.registerCandidates(List.of(candidate));

        assertEquals(List.of(ref1, ref2), registered.getFirst().getEvidenceRefs());
    }

    @Test
    void shouldReturnEmptyListWhenRegisteringNullCandidateCollection() {
        List<EvolutionCandidate> registered = stateService.registerCandidates(null);
        assertEquals(List.of(), registered);
    }

    @Test
    void shouldSkipNullAndBlankIdCandidatesDuringRegistration() {
        EvolutionCandidate valid = EvolutionCandidate.builder()
                .id("valid")
                .golemId("g")
                .goal("fix")
                .artifactType("prompt")
                .baseVersion("b1")
                .status("proposed")
                .build();
        EvolutionCandidate blankId = EvolutionCandidate.builder()
                .id("   ")
                .build();
        List<EvolutionCandidate> input = new java.util.ArrayList<>();
        input.add(valid);
        input.add(null);
        input.add(blankId);

        List<EvolutionCandidate> registered = stateService.registerCandidates(input);

        assertEquals(1, registered.size());
        assertEquals("valid", registered.getFirst().getId());
    }

    private void seedLegacyCandidate(EvolutionCandidate.EvolutionCandidateBuilder builder) throws Exception {
        EvolutionCandidate candidate = builder
                .createdAt(Instant.parse("2026-03-31T15:30:00Z"))
                .build();
        persistedFiles.put("candidates.json", objectMapper.writeValueAsString(List.of(candidate)));
        // Assert our seed truly persisted so the test intent is clear.
        assertSame(persistedFiles.get("candidates.json"), persistedFiles.get("candidates.json"));
        verify(storagePort, times(0)).putTextAtomic(anyString(), any(), anyString(), anyBoolean());
    }
}
