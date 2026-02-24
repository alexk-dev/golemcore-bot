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
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.inbound.ChannelPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * {@link ChannelPort} implementation for the webhook channel.
 *
 * <p>
 * When the agent pipeline produces a response via
 * {@code ResponseRoutingSystem}, this adapter captures it. For {@code /agent}
 * requests the response is stored in a {@link CompletableFuture} keyed by
 * chatId, and optionally delivered via {@link WebhookCallbackSender} to a
 * callback URL.
 *
 * <p>
 * Cross-channel delivery (e.g. forwarding a webhook-triggered response to
 * Telegram) is handled by the controller layer which resolves the target
 * channel from the channel registry.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookChannelAdapter implements ChannelPort {

    private static final String CHANNEL_TYPE = "webhook";

    private final WebhookCallbackSender callbackSender;

    /**
     * Pending agent run futures keyed by chatId. When {@code sendMessage} is called
     * by the pipeline, the future is completed with the response text.
     */
    private final Map<String, CompletableFuture<String>> pendingResponses = new ConcurrentHashMap<>();

    /**
     * Metadata for pending runs (callbackUrl, runId, startTime) keyed by chatId.
     */
    private final Map<String, RunMetadata> runMetadata = new ConcurrentHashMap<>();

    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }

    @Override
    public void start() {
        log.info("[Webhook] Channel adapter started");
    }

    @Override
    public void stop() {
        log.info("[Webhook] Channel adapter stopped");
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public CompletableFuture<Void> sendMessage(String chatId, String content) {
        log.debug("[Webhook] Response for chatId={}: {} chars", chatId, content != null ? content.length() : 0);

        CompletableFuture<String> pending = pendingResponses.remove(chatId);
        if (pending != null) {
            pending.complete(content);
        }

        RunMetadata metadata = runMetadata.remove(chatId);
        if (metadata != null && metadata.callbackUrl() != null) {
            long durationMs = System.currentTimeMillis() - metadata.startTimeMs();
            CallbackPayload payload = CallbackPayload.builder()
                    .runId(metadata.runId())
                    .chatId(chatId)
                    .status("completed")
                    .response(content)
                    .model(metadata.model())
                    .durationMs(durationMs)
                    .build();
            callbackSender.send(metadata.callbackUrl(), payload);
        }

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendMessage(Message message) {
        return sendMessage(message.getChatId(), message.getContent());
    }

    @Override
    public CompletableFuture<Void> sendVoice(String chatId, byte[] voiceData) {
        log.debug("[Webhook] Voice response not supported for webhook channel");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isAuthorized(String senderId) {
        // Auth is handled at the controller level by WebhookAuthenticator
        return true;
    }

    @Override
    public void onMessage(Consumer<Message> handler) {
        // No inbound polling — messages arrive via WebhookController
    }

    /**
     * Registers a pending agent run so the response can be captured.
     *
     * @param chatId
     *            session identifier
     * @param runId
     *            unique run identifier
     * @param callbackUrl
     *            optional URL to POST results to
     * @param model
     *            model tier used
     * @return a future that completes with the agent response text
     */
    public CompletableFuture<String> registerPendingRun(String chatId, String runId,
            String callbackUrl, String model) {
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingResponses.put(chatId, future);
        runMetadata.put(chatId, new RunMetadata(runId, callbackUrl, model, System.currentTimeMillis()));
        return future;
    }

    /**
     * Removes a pending run (e.g. on timeout or error).
     */
    public void cancelPendingRun(String chatId) {
        CompletableFuture<String> pending = pendingResponses.remove(chatId);
        if (pending != null) {
            pending.cancel(true);
        }
        RunMetadata metadata = runMetadata.remove(chatId);
        if (metadata != null && metadata.callbackUrl() != null) {
            long durationMs = System.currentTimeMillis() - metadata.startTimeMs();
            CallbackPayload payload = CallbackPayload.builder()
                    .runId(metadata.runId())
                    .chatId(chatId)
                    .status("failed")
                    .error("Run cancelled or timed out")
                    .durationMs(durationMs)
                    .build();
            callbackSender.send(metadata.callbackUrl(), payload);
        }
    }

    /**
     * Metadata for a pending agent run.
     */
    record RunMetadata(String runId, String callbackUrl, String model, long startTimeMs) {
    }
}
