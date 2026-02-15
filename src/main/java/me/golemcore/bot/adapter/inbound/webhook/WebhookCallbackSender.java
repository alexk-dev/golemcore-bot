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

import me.golemcore.bot.adapter.inbound.webhook.dto.CallbackPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;

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
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(256 * 1024))
                .build();
    }

    /**
     * Posts the callback payload to the given URL. Fire-and-forget with retry.
     *
     * @param callbackUrl target URL
     * @param payload     the agent run result
     */
    public void send(String callbackUrl, CallbackPayload payload) {
        webClient.post()
                .uri(callbackUrl)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .timeout(REQUEST_TIMEOUT)
                .retryWhen(Retry.backoff(MAX_RETRIES, FIRST_BACKOFF)
                        .doBeforeRetry(signal -> log.debug(
                                "[Webhook] Retrying callback to {} (attempt {})",
                                callbackUrl, signal.totalRetries() + 1)))
                .doOnSuccess(response -> log.info(
                        "[Webhook] Callback delivered to {} for run {}",
                        callbackUrl, payload.getRunId()))
                .doOnError(error -> log.error(
                        "[Webhook] Callback to {} failed after retries: {}",
                        callbackUrl, error.getMessage()))
                .subscribe();
    }
}
