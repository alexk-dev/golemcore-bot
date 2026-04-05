package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionWorkflowStoreTest {

    private StoragePort storagePort;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void shouldLoadAndCacheCandidatesAndDecisionsFromStorage() throws Exception {
        when(storagePort.getText("self-evolving", "candidates.json"))
                .thenReturn(CompletableFuture.completedFuture(objectMapper.writeValueAsString(List.of(
                        EvolutionCandidate.builder().id("candidate-1").build()))));
        when(storagePort.getText("self-evolving", "promotion-decisions.json"))
                .thenReturn(CompletableFuture.completedFuture(objectMapper.writeValueAsString(List.of(
                        PromotionDecision.builder().id("decision-1").build()))));
        PromotionWorkflowStore store = new PromotionWorkflowStore(storagePort);

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
        verify(storagePort, times(1)).getText("self-evolving", "candidates.json");
        verify(storagePort, times(1)).getText("self-evolving", "promotion-decisions.json");
    }

    @Test
    void shouldPersistCandidatesAndDecisionsAndRefreshCache() {
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(null));
        PromotionWorkflowStore store = new PromotionWorkflowStore(storagePort);
        List<EvolutionCandidate> candidates = List.of(EvolutionCandidate.builder()
                .id("candidate-2")
                .createdAt(Instant.parse("2026-04-06T00:00:00Z"))
                .build());
        List<PromotionDecision> decisions = List.of(PromotionDecision.builder().id("decision-2").build());

        store.saveCandidates(candidates);
        store.savePromotionDecisions(decisions);

        assertEquals("candidate-2", store.getCandidates().getFirst().getId());
        assertEquals("decision-2", store.getPromotionDecisions().getFirst().getId());
        verify(storagePort).putTextAtomic(eq("self-evolving"), eq("candidates.json"), anyString(), eq(true));
        verify(storagePort).putTextAtomic(eq("self-evolving"), eq("promotion-decisions.json"), anyString(), eq(true));
    }

    @Test
    void shouldReturnEmptyCollectionsWhenStoredJsonIsInvalid() {
        when(storagePort.getText(anyString(), eq("candidates.json")))
                .thenReturn(CompletableFuture.completedFuture("{"));
        when(storagePort.getText(anyString(), eq("promotion-decisions.json")))
                .thenReturn(CompletableFuture.completedFuture("{"));
        PromotionWorkflowStore store = new PromotionWorkflowStore(storagePort);

        assertTrue(store.getCandidates().isEmpty());
        assertTrue(store.getPromotionDecisions().isEmpty());
    }
}
