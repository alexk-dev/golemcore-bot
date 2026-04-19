package me.golemcore.bot.domain.resilience.followthrough;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FollowThroughClassifierTest {

    private static final String TIER = "routing";
    private static final String MODEL_ID = "test/router-model";

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private LlmPort llmPort;
    private ModelSelectionService modelSelectionService;
    private FollowThroughClassifier classifier;

    @BeforeEach
    void setUp() {
        llmPort = mock(LlmPort.class);
        modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveExplicitTier(TIER))
                .thenReturn(new ModelSelectionService.ModelSelection(MODEL_ID, null));
        classifier = new FollowThroughClassifier(llmPort, modelSelectionService,
                new FollowThroughPromptBuilder(), new FollowThroughVerdictParser(new FakeCodec()));
    }

    @Test
    void shouldReturnUnfulfilledCommitmentWhenLlmRespondsWithCommitmentVerdict() {
        String responseJson = """
                {"intent_type":"commitment","has_unfulfilled_commitment":true,
                 "commitment_text":"gather the files","continuation_prompt":"Gather the files now.",
                 "reason":"committed but no tool invoked"}
                """;
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        LlmResponse.builder().content(responseJson).build()));

        ClassifierVerdict verdict = classifier.classify(
                new ClassifierRequest("please gather", "I'll now gather the files.", List.of()),
                TIER, TIMEOUT);

        assertEquals(IntentType.COMMITMENT, verdict.intentType());
        assertTrue(verdict.hasUnfulfilledCommitment());
        assertEquals("Gather the files now.", verdict.continuationPrompt());
    }

    @Test
    void shouldBuildLlmRequestWithConfiguredTierModelAndLowTemperature() {
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        LlmResponse.builder().content("""
                                {"intent_type":"completion","has_unfulfilled_commitment":false,"reason":"done"}
                                """).build()));

        classifier.classify(new ClassifierRequest("do", "done", List.of("t1")), TIER, TIMEOUT);

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort).chat(captor.capture());
        LlmRequest sent = captor.getValue();
        assertEquals(MODEL_ID, sent.getModel());
        assertEquals(TIER, sent.getModelTier());
        assertNotNull(sent.getSystemPrompt());
        assertTrue(sent.getSystemPrompt().contains("follow-through classifier"));
        assertEquals(1, sent.getMessages().size());
        assertEquals("user", sent.getMessages().get(0).getRole());
        assertTrue(sent.getMessages().get(0).getContent().contains("done"));
        assertTrue(sent.getTemperature() <= 0.2,
                "classifier must use a low-temperature deterministic call");
    }

    @Test
    void shouldReturnUnknownNonCommitmentWhenLlmReturnsEmptyContent() {
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        LlmResponse.builder().content("").build()));

        ClassifierVerdict verdict = classifier.classify(
                new ClassifierRequest("q", "I'll continue", List.of()), TIER, TIMEOUT);

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment());
    }

    @Test
    void shouldReturnUnknownNonCommitmentWhenLlmFails() {
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));

        ClassifierVerdict verdict = classifier.classify(
                new ClassifierRequest("q", "I'll continue", List.of()), TIER, TIMEOUT);

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment());
    }

    @Test
    void shouldReturnUnknownNonCommitmentWhenLlmChatThrowsSynchronously() {
        when(llmPort.chat(any(LlmRequest.class))).thenThrow(new RuntimeException("chat exploded"));

        ClassifierVerdict verdict = classifier.classify(
                new ClassifierRequest("q", "I'll continue", List.of()), TIER, TIMEOUT);

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment());
        assertTrue(verdict.reason().contains("classifier call failed"));
    }

    @Test
    void shouldReturnUnknownNonCommitmentWhenLlmResponseContentIsNull() {
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        LlmResponse.builder().content(null).build()));

        ClassifierVerdict verdict = classifier.classify(
                new ClassifierRequest("q", "I'll continue", List.of()), TIER, TIMEOUT);

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment());
    }

    @Test
    void shouldReturnUnknownNonCommitmentWhenBuildRequestFailsBecauseTierIsUnresolvable() {
        when(modelSelectionService.resolveExplicitTier(TIER))
                .thenThrow(new IllegalStateException("tier not configured"));

        ClassifierVerdict verdict = classifier.classify(
                new ClassifierRequest("q", "I'll continue", List.of()), TIER, TIMEOUT);

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment());
        assertTrue(verdict.reason().contains("failed to build classifier request"));
    }

    @Test
    void shouldReturnUnknownNonCommitmentWhenCallTimesOut() {
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(new CompletableFuture<>());

        ClassifierVerdict verdict = classifier.classify(
                new ClassifierRequest("q", "I'll continue", List.of()), TIER, Duration.ofMillis(50));

        assertEquals(IntentType.UNKNOWN, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment());
    }

    @Test
    void shouldDowngradeCommitmentWithoutContinuationPromptToNonActionable() {
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        LlmResponse.builder().content("""
                                {"intent_type":"commitment","has_unfulfilled_commitment":true,
                                 "commitment_text":"do X","reason":"missing prompt"}
                                """).build()));

        ClassifierVerdict verdict = classifier.classify(
                new ClassifierRequest("q", "I'll do X", List.of()), TIER, TIMEOUT);

        assertEquals(IntentType.COMMITMENT, verdict.intentType());
        assertFalse(verdict.hasUnfulfilledCommitment());
    }
}
