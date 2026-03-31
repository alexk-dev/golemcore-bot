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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromotionWorkflowServiceArtifactLineageTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-31T19:00:00Z"), ZoneOffset.UTC);

    private StoragePort storagePort;
    private RuntimeConfigService runtimeConfigService;
    private EvolutionCandidateService evolutionCandidateService;
    private PromotionWorkflowService service;

    @BeforeEach
    void setUp() throws Exception {
        storagePort = mock(StoragePort.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getSelfEvolvingPromotionMode()).thenReturn("approval_gate");
        when(storagePort.putText(anyString(), anyString(), anyString()))
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

        evolutionCandidateService = new EvolutionCandidateService(storagePort, FIXED_CLOCK);
        service = new PromotionWorkflowService(storagePort, runtimeConfigService, evolutionCandidateService,
                FIXED_CLOCK);
    }

    @Test
    void shouldBackfillLegacyCandidateIdentityAndCreateApprovedLifecycleDecision() {
        EvolutionCandidate candidate = service.getCandidates().getFirst();

        assertNotNull(candidate.getArtifactStreamId());
        assertEquals("prompt:section", candidate.getArtifactSubtype());
        assertEquals("candidate", candidate.getLifecycleState());
        assertEquals("proposed", candidate.getRolloutStage());

        PromotionDecision decision = service.planPromotion(candidate.getId());

        assertEquals(candidate.getArtifactStreamId(), decision.getArtifactStreamId());
        assertEquals(candidate.getContentRevisionId(), decision.getContentRevisionId());
        assertEquals("candidate", decision.getFromLifecycleState());
        assertEquals("approved", decision.getToLifecycleState());
        assertEquals("proposed", decision.getFromRolloutStage());
        assertEquals("approved", decision.getToRolloutStage());
        assertEquals("bundle-1", decision.getOriginBundleId());
    }
}
