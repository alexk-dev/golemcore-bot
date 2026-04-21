package me.golemcore.bot.domain.resilience.autoproceed;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.ModelSelectionService.ModelSelection;
import me.golemcore.bot.port.outbound.LlmPort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Domain service that runs the Auto-Proceed classifier LLM call.
 *
 * <p>
 * Wraps {@link LlmPort} directly (mirroring the Follow-Through classifier). No
 * extra outbound port is introduced. Model tier and per-call timeout are
 * supplied by the caller so that runtime config changes take effect on the next
 * turn without redeploying.
 *
 * <p>
 * The classifier is fail-closed: any error, timeout, empty response, or
 * malformed JSON collapses to an {@link IntentType#UNKNOWN} non-actionable
 * verdict so that a classifier hiccup never triggers a false-positive
 * affirmation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutoProceedClassifier {

    private static final double CLASSIFIER_TEMPERATURE = 0.1;

    private final LlmPort llmPort;
    private final ModelSelectionService modelSelectionService;
    private final AutoProceedPromptBuilder promptBuilder;
    private final AutoProceedVerdictParser verdictParser;

    public ClassifierVerdict classify(ClassifierRequest request, String modelTier, Duration timeout) {
        LlmRequest llmRequest;
        try {
            llmRequest = buildLlmRequest(request, modelTier);
        } catch (RuntimeException exception) {
            log.debug("[AutoProceed] failed to build classifier request: {}", exception.getMessage());
            return ClassifierVerdict.nonActionable(IntentType.UNKNOWN,
                    "failed to build classifier request: " + exception.getMessage());
        }

        LlmResponse response;
        try {
            CompletableFuture<LlmResponse> future = llmPort.chat(llmRequest);
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            log.debug("[AutoProceed] classifier timed out after {}ms", timeout.toMillis());
            return ClassifierVerdict.nonActionable(IntentType.UNKNOWN, "classifier call timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ClassifierVerdict.nonActionable(IntentType.UNKNOWN, "classifier call interrupted");
        } catch (ExecutionException exception) { // NOSONAR — domain swallow, fail closed
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            log.debug("[AutoProceed] classifier call failed: {}", cause.getMessage());
            return ClassifierVerdict.nonActionable(IntentType.UNKNOWN,
                    "classifier call failed: " + cause.getMessage());
        } catch (RuntimeException exception) { // NOSONAR — domain swallow, fail closed
            log.debug("[AutoProceed] classifier call failed: {}", exception.getMessage());
            return ClassifierVerdict.nonActionable(IntentType.UNKNOWN,
                    "classifier call failed: " + exception.getMessage());
        }

        if (response == null) {
            return ClassifierVerdict.nonActionable(IntentType.UNKNOWN, "classifier returned null response");
        }
        return verdictParser.parse(response.getContent());
    }

    private LlmRequest buildLlmRequest(ClassifierRequest request, String modelTier) {
        ModelSelection selection = modelSelectionService.resolveExplicitTier(modelTier);
        Message userMessage = Message.builder()
                .role("user")
                .content(promptBuilder.userPrompt(request))
                .build();
        return LlmRequest.builder()
                .model(selection.model())
                .modelTier(modelTier)
                .systemPrompt(promptBuilder.systemPrompt())
                .messages(List.of(userMessage))
                .temperature(CLASSIFIER_TEMPERATURE)
                .reasoningEffort(selection.reasoning())
                .build();
    }
}
