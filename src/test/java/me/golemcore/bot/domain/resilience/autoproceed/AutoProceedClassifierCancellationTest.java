package me.golemcore.bot.domain.resilience.autoproceed;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.service.ModelSelectionService;
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

class AutoProceedClassifierCancellationTest {

    private static final String TIER = "routing";
    private static final String MODEL_ID = "test/router-model";

    private LlmPort llmPort;
    private AutoProceedClassifier classifier;

    @BeforeEach
    void setUp() {
        llmPort = mock(LlmPort.class);
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveExplicitTier(TIER))
                .thenReturn(new ModelSelectionService.ModelSelection(MODEL_ID, null));
        classifier = new AutoProceedClassifier(llmPort, modelSelectionService,
                new AutoProceedPromptBuilder(), new AutoProceedVerdictParser(new FakeCodec()));
    }

    @Test
    void shouldCancelUnderlyingFutureWhenClassifierTimesOut() {
        CompletableFuture<LlmResponse> pending = new CompletableFuture<>();
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(pending);

        ClassifierVerdict verdict = classifier.classify(
                new ClassifierRequest("q", "I'll continue", List.of()), TIER, Duration.ofMillis(50));

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertTrue(pending.isCancelled(),
                "Timed-out AutoProceed classifier must cancel the underlying future to "
                        + "stop orphaned rate-limit retries outliving the turn");
    }

    @Test
    void shouldTagClassifierLlmRequestWithAutoProceedCallerTag() {
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        LlmResponse.builder().content("""
                                {"intent_type":"other","should_auto_affirm":false,"reason":"none"}
                                """).build()));

        classifier.classify(new ClassifierRequest("q", "Ready to continue?", List.of()),
                TIER, Duration.ofSeconds(5));

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort).chat(captor.capture());
        assertEquals("auto_proceed", captor.getValue().getCallerTag(),
                "Classifier LLM requests must be tagged so adapter logs can identify the caller");
    }
}
