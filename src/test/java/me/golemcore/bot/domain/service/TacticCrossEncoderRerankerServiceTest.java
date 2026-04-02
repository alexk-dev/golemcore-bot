package me.golemcore.bot.domain.service;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TacticCrossEncoderRerankerServiceTest {

    private ModelSelectionService modelSelectionService;
    private LlmPort llmPort;
    private TacticCrossEncoderRerankerService rerankerService;

    @BeforeEach
    void setUp() {
        modelSelectionService = mock(ModelSelectionService.class);
        llmPort = mock(LlmPort.class);
        rerankerService = new TacticCrossEncoderRerankerService(modelSelectionService, llmPort, new ObjectMapper());
    }

    @Test
    void shouldResolveTierAndCallLlmForCrossEncoderReranking() {
        when(modelSelectionService.resolveExplicitTier("deep"))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5.4", "high"));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                .content("""
                        {
                          "results": [
                            {
                              "tacticId": "planner",
                              "score": 0.08,
                              "verdict": "tier deep via gpt-5.4/high"
                            },
                            {
                              "tacticId": "rollback",
                              "score": 0.01,
                              "verdict": "fallback only"
                            }
                          ]
                        }
                        """)
                .build()));

        List<TacticCrossEncoderRerankerService.RerankedCandidate> results = rerankerService.rerank(
                TacticSearchQuery.builder()
                        .rawQuery("recover failed shell command")
                        .queryViews(List.of("recover", "shell", "failure"))
                        .build(),
                List.of(
                        tactic("planner", "Planner tactic", "Recover via shell and git"),
                        tactic("rollback", "Rollback tactic", "Revert the last broken step")),
                "deep",
                2500);

        assertEquals(2, results.size());
        assertEquals("planner", results.getFirst().tacticId());
        assertEquals(0.08d, results.getFirst().score());
        assertTrue(results.getFirst().verdict().contains("gpt-5.4"));
        verify(llmPort).chat(any());
    }

    @Test
    void shouldFailFastWhenRerankerDoesNotReturnBeforeTimeout() {
        when(modelSelectionService.resolveExplicitTier("deep"))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5.4", "high"));
        when(llmPort.chat(any())).thenReturn(new CompletableFuture<>());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> rerankerService.rerank(
                TacticSearchQuery.builder()
                        .rawQuery("recover failed shell command")
                        .queryViews(List.of("recover", "shell", "failure"))
                        .build(),
                List.of(tactic("planner", "Planner tactic", "Recover via shell and git")),
                "deep",
                10));

        assertTrue(error.getCause() instanceof TimeoutException);
    }

    @Test
    void shouldIgnoreBlankTacticIdsInRerankerResponse() {
        when(modelSelectionService.resolveExplicitTier("deep"))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5.4", "high"));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                .content("""
                        {
                          "results": [
                            {"tacticId": "", "score": 0.09, "verdict": "ignore"},
                            {"tacticId": "planner", "score": 0.08, "verdict": "keep"}
                          ]
                        }
                        """)
                .build()));

        List<TacticCrossEncoderRerankerService.RerankedCandidate> results = rerankerService.rerank(
                TacticSearchQuery.builder().rawQuery("recover failed shell command").build(),
                List.of(tactic("planner", "Planner tactic", "Recover via shell and git")),
                "deep",
                2500);

        assertEquals(1, results.size());
        assertEquals("planner", results.getFirst().tacticId());
    }

    @Test
    void shouldFailWhenRerankerReturnsEmptyResponse() {
        when(modelSelectionService.resolveExplicitTier("deep"))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5.4", "high"));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                .content("  ")
                .build()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> rerankerService.rerank(
                TacticSearchQuery.builder().rawQuery("recover failed shell command").build(),
                List.of(tactic("planner", "Planner tactic", "Recover via shell and git")),
                "deep",
                2500));

        assertTrue(error.getMessage().contains("empty response"));
    }

    @Test
    void shouldFailWhenRerankerReturnsInvalidJson() {
        when(modelSelectionService.resolveExplicitTier("deep"))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5.4", "high"));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                .content("not-json")
                .build()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> rerankerService.rerank(
                TacticSearchQuery.builder().rawQuery("recover failed shell command").build(),
                List.of(tactic("planner", "Planner tactic", "Recover via shell and git")),
                "deep",
                2500));

        assertTrue(error.getMessage().contains("Unable to parse"));
    }

    @Test
    void shouldWrapExecutionFailureFromRerankerModel() {
        when(modelSelectionService.resolveExplicitTier("deep"))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5.4", "high"));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> rerankerService.rerank(
                TacticSearchQuery.builder().rawQuery("recover failed shell command").build(),
                List.of(tactic("planner", "Planner tactic", "Recover via shell and git")),
                "deep",
                2500));

        assertTrue(error.getCause() instanceof ExecutionException);
    }

    @Test
    void shouldWrapInterruptedFailureFromRerankerModel() {
        when(modelSelectionService.resolveExplicitTier("deep"))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5.4", "high"));
        CompletableFuture<LlmResponse> interruptedFuture = new CompletableFuture<>() {
            @Override
            public LlmResponse get(long timeout, TimeUnit unit) throws InterruptedException {
                throw new InterruptedException("interrupted");
            }
        };
        when(llmPort.chat(any())).thenReturn(interruptedFuture);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> rerankerService.rerank(
                TacticSearchQuery.builder().rawQuery("recover failed shell command").build(),
                List.of(tactic("planner", "Planner tactic", "Recover via shell and git")),
                "deep",
                2500));

        assertTrue(error.getMessage().contains("interrupted"));
        assertTrue(Thread.interrupted());
    }

    private TacticSearchResult tactic(String tacticId, String title, String behaviorSummary) {
        return TacticSearchResult.builder()
                .tacticId(tacticId)
                .artifactStreamId("stream-" + tacticId)
                .artifactKey("skill:" + tacticId)
                .artifactType("skill")
                .title(title)
                .behaviorSummary(behaviorSummary)
                .toolSummary("shell git")
                .promotionState("active")
                .rolloutStage("active")
                .updatedAt(Instant.parse("2026-04-01T23:30:00Z"))
                .build();
    }
}
