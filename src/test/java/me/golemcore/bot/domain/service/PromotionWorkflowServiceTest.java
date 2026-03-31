package me.golemcore.bot.domain.service;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromotionWorkflowServiceTest {

    private StoragePort storagePort;
    private RuntimeConfigService runtimeConfigService;
    private PromotionWorkflowService promotionWorkflowService;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(storagePort.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(runtimeConfigService.getSelfEvolvingPromotionMode()).thenReturn("approval_gate");
        promotionWorkflowService = new PromotionWorkflowService(
                storagePort,
                runtimeConfigService,
                Clock.fixed(Instant.parse("2026-03-31T16:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldDefaultPromotionToApprovalGateEvenWhenCandidateIsStrong() {
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-1")
                .golemId("golem-1")
                .goal("derive")
                .artifactType("skill")
                .status("proposed")
                .build();

        PromotionDecision decision = promotionWorkflowService.planPromotion(candidate);

        assertEquals("approved_pending", decision.getState());
        assertEquals("approval_gate", decision.getMode());
        assertEquals("approved_pending", promotionWorkflowService.getCandidates().getFirst().getStatus());
    }

    @Test
    void shouldAutoAcceptCandidateIntoShadowWhenConfigured() {
        when(runtimeConfigService.getSelfEvolvingPromotionMode()).thenReturn("auto_accept");
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-2")
                .golemId("golem-1")
                .goal("fix")
                .artifactType("tool_policy")
                .status("proposed")
                .build();

        PromotionDecision decision = promotionWorkflowService.planPromotion(candidate);

        assertEquals("shadowed", decision.getState());
        assertEquals("shadowed", promotionWorkflowService.getCandidates().getFirst().getStatus());
        assertFalse(promotionWorkflowService.getPromotionDecisions().isEmpty());
    }

    @Test
    void shouldRegisterCandidatesBeforePlanning() {
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-3")
                .golemId("golem-1")
                .goal("fix")
                .artifactType("prompt")
                .status("proposed")
                .build();

        List<EvolutionCandidate> registered = promotionWorkflowService.registerCandidates(List.of(candidate));

        assertEquals(1, registered.size());
        assertEquals("candidate-3", promotionWorkflowService.getCandidates().getFirst().getId());
    }
}
