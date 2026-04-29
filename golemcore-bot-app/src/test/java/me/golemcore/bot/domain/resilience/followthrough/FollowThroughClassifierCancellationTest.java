package me.golemcore.bot.domain.resilience.followthrough;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.ModelSelectionService;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.resilience.ClassifierRequest;
import me.golemcore.bot.domain.resilience.FakeCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FollowThroughClassifierCancellationTest {

    private static final String TIER = "routing";
    private static final String MODEL_ID = "test/router-model";

    private LlmPort llmPort;
    private FollowThroughClassifier classifier;

    @BeforeEach
    void setUp() {
        llmPort = mock(LlmPort.class);
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveExplicitTier(TIER))
                .thenReturn(new ModelSelectionService.ModelSelection(MODEL_ID, null));
        classifier = new FollowThroughClassifier(llmPort, modelSelectionService,
                new FollowThroughPromptBuilder(), new FollowThroughVerdictParser(new FakeCodec()));
    }

    @Test
    void shouldCancelUnderlyingFutureWhenClassifierTimesOut() {
        CompletableFuture<me.golemcore.bot.domain.model.LlmResponse> pending = new CompletableFuture<>();
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(pending);

        ClassifierVerdict verdict = classifier.classify(
                new ClassifierRequest("q", "I'll continue", List.of()), TIER, Duration.ofMillis(50));

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertTrue(pending.isCancelled(),
                "Timed-out classifier must cancel the underlying future so its supplier "
                        + "(e.g. rate-limit retries on ForkJoinPool) stops instead of running orphaned");
    }

    @Test
    void shouldTagClassifierLlmRequestWithFollowThroughCallerTag() {
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        me.golemcore.bot.domain.model.LlmResponse.builder().content("""
                                {"intent_type":"completion","has_unfulfilled_commitment":false,"reason":"done"}
                                """).build()));

        classifier.classify(new ClassifierRequest("do", "done", List.of("t1")), TIER, Duration.ofSeconds(5));

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort).chat(captor.capture());
        assertEquals("follow_through", captor.getValue().getCallerTag(),
                "Classifier LLM requests must be tagged so adapter logs can identify the caller");
    }
}
