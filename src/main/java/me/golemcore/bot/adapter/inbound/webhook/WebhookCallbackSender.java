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

package me.golemcore.bot.adapter.inbound.webhook;

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.inbound.webhook.dto.CallbackPayload;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends asynchronous POST callbacks to external URLs after an {@code /agent}
 * webhook run completes. Uses {@link WebClient} (reactive) with exponential
 * backoff retry (3 attempts).
 */
@Component
@Slf4j
public class WebhookCallbackSender {

    private static final int MAX_RETRIES = 3;
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;

    public WebhookCallbackSender() {
        this(createDefaultWebClient());
    }

    WebhookCallbackSender(WebClient webClient) {
        this.webClient = webClient;
    }

    private static WebClient createDefaultWebClient() {
        return WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(256 * 1024))
                .build();
    }

    /**
     * Callback delivery observer for runtime timeline tracking.
     */
    public interface DeliveryObserver {

        void onAttempt(String callbackUrl, CallbackPayload payload, int attemptNumber);

        void onSuccess(String callbackUrl, CallbackPayload payload, int totalAttempts);

        void onFailure(String callbackUrl, CallbackPayload payload, int totalAttempts, Throwable error);
    }

    /**
     * Posts the callback payload to the given URL. Fire-and-forget with retry.
     *
     * @param callbackUrl
     *            target URL
     * @param payload
     *            the agent run result
     */
    public void send(String callbackUrl, CallbackPayload payload) {
        send(callbackUrl, payload, null);
    }

    /**
     * Posts the callback payload to the given URL and reports lifecycle events to
     * an optional observer.
     */
    public void send(String callbackUrl, CallbackPayload payload, DeliveryObserver observer) {
        buildSendMono(callbackUrl, payload, observer).subscribe();
    }

    protected Mono<Void> buildSendMono(String callbackUrl, CallbackPayload payload) {
        return buildSendMono(callbackUrl, payload, null);
    }

    protected Mono<Void> buildSendMono(String callbackUrl, CallbackPayload payload, DeliveryObserver observer) {
        AtomicInteger attemptCounter = new AtomicInteger(1);
        notifyAttempt(observer, callbackUrl, payload, 1);

        return webClient.post()
                .uri(callbackUrl)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .timeout(getRequestTimeout())
                .retryWhen(buildRetry(callbackUrl)
                        .doBeforeRetry(signal -> {
                            int nextAttempt = attemptCounter.incrementAndGet();
                            notifyAttempt(observer, callbackUrl, payload, nextAttempt);
                            log.debug(
                                    "[Webhook] Retrying callback to {} (attempt {})",
                                    callbackUrl, nextAttempt);
                        }))
                .doOnSuccess(response -> {
                    notifySuccess(observer, callbackUrl, payload, attemptCounter.get());
                    log.info(
                            "[Webhook] Callback delivered to {} for run {}",
                            callbackUrl, payload.getRunId());
                })
                .doOnError(error -> {
                    notifyFailure(observer, callbackUrl, payload, attemptCounter.get(), error);
                    log.error(
                            "[Webhook] Callback to {} failed after retries: {}",
                            callbackUrl, error.getMessage());
                })
                .then();
    }

    protected Duration getRequestTimeout() {
        return REQUEST_TIMEOUT;
    }

    protected RetryBackoffSpec buildRetry(String callbackUrl) {
        return Retry.backoff(MAX_RETRIES, FIRST_BACKOFF);
    }

    private void notifyAttempt(DeliveryObserver observer, String callbackUrl, CallbackPayload payload,
            int attemptNumber) {
        if (observer == null) {
            return;
        }
        try {
            observer.onAttempt(callbackUrl, payload, attemptNumber);
        } catch (RuntimeException e) {
            log.debug("[Webhook] Delivery observer onAttempt failed: {}", e.getMessage());
        }
    }

    private void notifySuccess(DeliveryObserver observer, String callbackUrl, CallbackPayload payload,
            int totalAttempts) {
        if (observer == null) {
            return;
        }
        try {
            observer.onSuccess(callbackUrl, payload, totalAttempts);
        } catch (RuntimeException e) {
            log.debug("[Webhook] Delivery observer onSuccess failed: {}", e.getMessage());
        }
    }

    private void notifyFailure(DeliveryObserver observer, String callbackUrl, CallbackPayload payload,
            int totalAttempts, Throwable error) {
        if (observer == null) {
            return;
        }
        try {
            observer.onFailure(callbackUrl, payload, totalAttempts, error);
        } catch (RuntimeException e) {
            log.debug("[Webhook] Delivery observer onFailure failed: {}", e.getMessage());
        }
    }
}
