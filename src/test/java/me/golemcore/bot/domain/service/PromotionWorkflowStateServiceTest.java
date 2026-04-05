package me.golemcore.bot.domain.service;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        TacticRecordService tacticRecordService = new TacticRecordService(storagePort, clock, null, null);
        ArtifactBundleService artifactBundleService = new ArtifactBundleService(storagePort, runtimeConfigService,
                clock);
        EvolutionCandidateService evolutionCandidateService = new EvolutionCandidateService(
                tacticRecordService,
                artifactBundleService,
                new EvolutionArtifactIdentityService(storagePort, clock),
                new EvolutionCandidateDerivationService(clock),
                new EvolutionCandidateTacticMaterializer(clock));
        stateService = new PromotionWorkflowStateService(
                new PromotionWorkflowStore(storagePort),
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
}
