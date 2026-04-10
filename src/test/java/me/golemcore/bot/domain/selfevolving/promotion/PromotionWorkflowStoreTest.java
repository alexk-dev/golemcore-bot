package me.golemcore.bot.domain.selfevolving.promotion;

import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.port.outbound.selfevolving.PromotionWorkflowStatePort;
import me.golemcore.bot.port.outbound.selfevolving.PromotionWorkflowStatePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromotionWorkflowStoreTest {    private PromotionWorkflowStatePort promotionWorkflowStatePort;

    @BeforeEach
    void setUp() {        promotionWorkflowStatePort = new InMemoryPromotionWorkflowStatePort();
    }

    @Test
    void shouldLoadAndCacheCandidatesAndDecisionsFromStorage() throws Exception {        ((InMemoryPromotionWorkflowStatePort) promotionWorkflowStatePort).saveCandidates(List.of(EvolutionCandidate.builder().id("candidate-1").build()));
        ((InMemoryPromotionWorkflowStatePort) promotionWorkflowStatePort).savePromotionDecisions(List.of(PromotionDecision.builder().id("decision-1").build()));
        PromotionWorkflowStore store = new PromotionWorkflowStore(promotionWorkflowStatePort);

        List<EvolutionCandidate> firstCandidates = store.getCandidates();
        List<EvolutionCandidate> secondCandidates = store.getCandidates();
        List<PromotionDecision> firstDecisions = store.getPromotionDecisions();
        List<PromotionDecision> secondDecisions = store.getPromotionDecisions();

        assertEquals(1, firstCandidates.size());
        assertEquals(1, secondCandidates.size());
        assertEquals("candidate-1", firstCandidates.getFirst().getId());
        assertEquals(1, firstDecisions.size());
        assertEquals(1, secondDecisions.size());
        assertEquals("decision-1", firstDecisions.getFirst().getId());
    }

    @Test
    void shouldPersistCandidatesAndDecisionsAndRefreshCache() {        PromotionWorkflowStore store = new PromotionWorkflowStore(promotionWorkflowStatePort);
        List<EvolutionCandidate> candidates = List.of(EvolutionCandidate.builder()
                .id("candidate-2")
                .createdAt(Instant.parse("2026-04-06T00:00:00Z"))
                .build());
        List<PromotionDecision> decisions = List.of(PromotionDecision.builder().id("decision-2").build());

        store.saveCandidates(candidates);
        store.savePromotionDecisions(decisions);

        assertEquals("candidate-2", store.getCandidates().getFirst().getId());
        assertEquals("decision-2", store.getPromotionDecisions().getFirst().getId());
    }

    @Test
    void shouldReturnEmptyCollectionsWhenStoredJsonIsInvalid() {        PromotionWorkflowStatePort failingPort = new PromotionWorkflowStatePort() {
            @Override
            public List<EvolutionCandidate> loadCandidates() {
                return new ArrayList<>();
            }

            @Override
            public List<PromotionDecision> loadPromotionDecisions() {
                return new ArrayList<>();
            }

            @Override
            public void saveCandidates(List<EvolutionCandidate> candidates) {
            }

            @Override
            public void savePromotionDecisions(List<PromotionDecision> decisions) {
            }
        };
        PromotionWorkflowStore store = new PromotionWorkflowStore(failingPort);

        assertTrue(store.getCandidates().isEmpty());
        assertTrue(store.getPromotionDecisions().isEmpty());
    }
}
