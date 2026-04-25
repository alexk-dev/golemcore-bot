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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import me.golemcore.bot.adapter.outbound.selfevolving.JsonPromotionWorkflowStateAdapter;
import me.golemcore.bot.domain.selfevolving.artifact.ArtifactBundleService;
import me.golemcore.bot.domain.selfevolving.artifact.EvolutionArtifactIdentityService;
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionCandidateDerivationService;
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionCandidateService;
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionCandidateTacticMaterializer;
import me.golemcore.bot.domain.selfevolving.tactic.TacticRecordService;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;
import me.golemcore.bot.adapter.outbound.selfevolving.JsonArtifactRepositoryAdapter;

class PromotionWorkflowServiceArtifactLineageTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-31T19:00:00Z"), ZoneOffset.UTC);

    private StoragePort storagePort;
    private SelfEvolvingRuntimeConfigPort runtimeConfigPort;
    private EvolutionCandidateService evolutionCandidateService;
    private PromotionWorkflowService service;

    @BeforeEach
    void setUp() throws Exception {
        storagePort = mock(StoragePort.class);
        runtimeConfigPort = mock(SelfEvolvingRuntimeConfigPort.class);
        when(runtimeConfigPort.getSelfEvolvingPromotionMode()).thenReturn("approval_gate");
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EvolutionCandidate legacyCandidate = EvolutionCandidate.builder()
                .id("candidate-1")
                .golemId("golem-1")
                .goal("fix")
                .artifactType("prompt")
                .baseVersion("bundle-1")
                .proposedDiff("selfevolving:fix:prompt")
                .status("proposed")
                .createdAt(Instant.parse("2026-03-31T18:30:00Z"))
                .sourceRunIds(List.of("run-1"))
                .build();
        when(storagePort.getText("self-evolving", "candidates.json"))
                .thenReturn(
                        CompletableFuture.completedFuture(objectMapper.writeValueAsString(List.of(legacyCandidate))));

        evolutionCandidateService = new EvolutionCandidateService(
                mock(TacticRecordService.class),
                mock(ArtifactBundleService.class),
                new EvolutionArtifactIdentityService(new JsonArtifactRepositoryAdapter(storagePort), FIXED_CLOCK),
                new EvolutionCandidateDerivationService(FIXED_CLOCK),
                new EvolutionCandidateTacticMaterializer(FIXED_CLOCK));
        PromotionWorkflowStateService promotionWorkflowStateService = new PromotionWorkflowStateService(
                new PromotionWorkflowStore(new JsonPromotionWorkflowStateAdapter(storagePort)),
                evolutionCandidateService,
                new PromotionDecisionHydrationService());
        service = new PromotionWorkflowService(runtimeConfigPort,
                promotionWorkflowStateService,
                new PromotionTargetResolver(runtimeConfigPort),
                new PromotionExecutionService(null, FIXED_CLOCK),
                null);
    }

    @Test
    void shouldBackfillLegacyCandidateIdentityAndCreateActiveLifecycleDecision() {
        EvolutionCandidate candidate = service.getCandidates().getFirst();

        assertNotNull(candidate.getArtifactStreamId());
        assertEquals("prompt:section", candidate.getArtifactSubtype());
        assertEquals("candidate", candidate.getLifecycleState());
        assertEquals("proposed", candidate.getRolloutStage());

        PromotionDecision decision = service.planPromotion(candidate.getId());

        assertEquals(candidate.getArtifactStreamId(), decision.getArtifactStreamId());
        assertEquals(candidate.getContentRevisionId(), decision.getContentRevisionId());
        assertEquals("candidate", decision.getFromLifecycleState());
        assertEquals("active", decision.getToLifecycleState());
        assertEquals("proposed", decision.getFromRolloutStage());
        assertEquals("active", decision.getToRolloutStage());
        assertEquals("bundle-1", decision.getOriginBundleId());
    }
}
