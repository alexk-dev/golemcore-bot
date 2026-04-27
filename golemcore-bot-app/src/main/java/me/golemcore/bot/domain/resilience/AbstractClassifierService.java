package me.golemcore.bot.domain.resilience;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ModelSelectionService;
import me.golemcore.bot.domain.model.ModelSelectionService.ModelSelection;
import me.golemcore.bot.port.outbound.LlmPort;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared fail-closed execution flow for resilience classifiers backed by the
 * LLM port.
 *
 * @param <V>
 *            classifier verdict type
 */
public abstract class AbstractClassifierService<V> {

    private static final double CLASSIFIER_TEMPERATURE = 0.1;

    private final LlmPort llmPort;
    private final ModelSelectionService modelSelectionService;

    protected AbstractClassifierService(LlmPort llmPort, ModelSelectionService modelSelectionService) {
        this.llmPort = llmPort;
        this.modelSelectionService = modelSelectionService;
    }

    public V classify(ClassifierRequest request, String modelTier, Duration timeout) {
        LlmRequest llmRequest;
        try {
            llmRequest = buildLlmRequest(request, modelTier);
        } catch (RuntimeException exception) {
            logger().debug("[{}] failed to build classifier request: {}", logPrefix(), exception.getMessage());
            return failureVerdict("failed to build classifier request: " + exception.getMessage());
        }

        LlmResponse response;
        CompletableFuture<LlmResponse> future = null;
        try {
            future = llmPort.chat(llmRequest);
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            logger().debug("[{}] classifier timed out after {}ms", logPrefix(), timeout.toMillis());
            cancelQuietly(future);
            return failureVerdict("classifier call timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancelQuietly(future);
            return failureVerdict("classifier call interrupted");
        } catch (ExecutionException exception) { // NOSONAR - fail closed by design
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            logger().debug("[{}] classifier call failed: {}", logPrefix(), cause.getMessage());
            return failureVerdict("classifier call failed: " + cause.getMessage());
        } catch (RuntimeException exception) { // NOSONAR - fail closed by design
            logger().debug("[{}] classifier call failed: {}", logPrefix(), exception.getMessage());
            cancelQuietly(future);
            return failureVerdict("classifier call failed: " + exception.getMessage());
        }

        if (response == null) {
            return failureVerdict("classifier returned null response");
        }
        return parseResponse(response.getContent());
    }

    private LlmRequest buildLlmRequest(ClassifierRequest request, String modelTier) {
        ModelSelection selection = modelSelectionService.resolveExplicitTier(modelTier);
        Message userMessage = Message.builder()
                .role("user")
                .content(promptBuilder().userPrompt(request))
                .build();
        return LlmRequest.builder()
                .model(selection.model())
                .modelTier(modelTier)
                .callerTag(callerTag())
                .systemPrompt(promptBuilder().systemPrompt())
                .messages(List.of(userMessage))
                .temperature(CLASSIFIER_TEMPERATURE)
                .reasoningEffort(selection.reasoning())
                .build();
    }

    protected abstract Logger logger();

    protected abstract String logPrefix();

    protected abstract String callerTag();

    protected abstract ClassifierPromptBuilder promptBuilder();

    protected abstract V parseResponse(String rawResponse);

    protected abstract V failureVerdict(String reason);

    private static void cancelQuietly(CompletableFuture<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }
}
