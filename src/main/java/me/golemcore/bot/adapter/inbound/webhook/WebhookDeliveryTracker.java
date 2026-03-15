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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.inbound.webhook.dto.CallbackPayload;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tracks webhook callback deliveries for dashboard observability and manual
 * retry operations.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookDeliveryTracker {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    private static final String SOURCE_AGENT = "agent";
    private static final String SOURCE_TEST = "test";

    private static final String EVENT_REGISTERED = "REGISTERED";
    private static final String EVENT_ATTEMPT = "ATTEMPT";
    private static final String EVENT_SUCCESS = "SUCCESS";
    private static final String EVENT_FAILURE = "FAILURE";
    private static final String EVENT_RETRY_REQUESTED = "RETRY_REQUESTED";

    private static final int DEFAULT_MAX_ENTRIES = 500;
    private static final int DEFAULT_MAX_EVENTS_PER_DELIVERY = 64;
    private static final int DEFAULT_MAX_PAYLOAD_RESPONSE_CHARS = 2000;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 200;

    private final WebhookCallbackSender callbackSender;
    private final BotProperties botProperties;

    private final Object lock = new Object();
    private final Map<String, DeliveryState> deliveriesById = new LinkedHashMap<>();
    private final Deque<String> deliveryOrder = new ArrayDeque<>();

    /**
     * Register a callback delivery for an accepted webhook run.
     */
    public String registerPendingDelivery(String runId, String chatId, String callbackUrl, String model) {
        return registerPendingDelivery(runId, chatId, callbackUrl, model, SOURCE_AGENT);
    }

    /**
     * Register a callback delivery with a custom source marker.
     */
    public String registerPendingDelivery(String runId, String chatId, String callbackUrl, String model,
            String source) {
        String deliveryId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        synchronized (lock) {
            DeliveryState state = DeliveryState.builder()
                    .deliveryId(deliveryId)
                    .runId(runId)
                    .chatId(chatId)
                    .callbackUrl(callbackUrl)
                    .model(model)
                    .source(source)
                    .status(STATUS_PENDING)
                    .attempts(0)
                    .lastError(null)
                    .createdAt(now)
                    .updatedAt(now)
                    .events(new ArrayList<>())
                    .build();
            state.appendEvent(EVENT_REGISTERED, STATUS_PENDING, null,
                    "Delivery registered for callback URL");

            deliveriesById.put(deliveryId, state);
            deliveryOrder.addFirst(deliveryId);
            evictOverflowIfNeeded();
        }

        return deliveryId;
    }

    /**
     * Stores payload metadata and marks a manual retry request if requested.
     */
    public void capturePayload(String deliveryId, CallbackPayload payload, boolean manualRetry) {
        if (deliveryId == null || payload == null) {
            return;
        }

        synchronized (lock) {
            DeliveryState state = deliveriesById.get(deliveryId);
            if (state == null) {
                return;
            }

            state.setPayloadStatus(payload.getStatus());
            state.setPayloadModel(payload.getModel());
            state.setPayloadDurationMs(payload.getDurationMs());
            state.setPayloadError(payload.getError());
            state.setPayloadResponse(payload.getResponse());
            state.setUpdatedAt(Instant.now());

            if (manualRetry) {
                state.setStatus(STATUS_IN_PROGRESS);
                state.setLastError(null);
                state.appendEvent(EVENT_RETRY_REQUESTED, state.getStatus(), null,
                        "Manual retry requested from dashboard");
            }
        }
    }

    /**
     * Creates an observer bound to the specified delivery id.
     */
    public WebhookCallbackSender.DeliveryObserver createObserver(String deliveryId) {
        int attemptOffset;
        synchronized (lock) {
            DeliveryState state = deliveriesById.get(deliveryId);
            attemptOffset = state != null ? state.getAttempts() : 0;
        }

        return new WebhookCallbackSender.DeliveryObserver() {
            @Override
            public void onAttempt(String callbackUrl, CallbackPayload payload, int attemptNumber) {
                updateAttempt(deliveryId, attemptOffset + Math.max(1, attemptNumber));
            }

            @Override
            public void onSuccess(String callbackUrl, CallbackPayload payload, int totalAttempts) {
                markSuccess(deliveryId, attemptOffset + Math.max(1, totalAttempts));
            }

            @Override
            public void onFailure(String callbackUrl, CallbackPayload payload, int totalAttempts, Throwable error) {
                String message = error != null ? truncate(error.getMessage(), 300) : "Callback failed";
                markFailure(deliveryId, attemptOffset + Math.max(1, totalAttempts), message);
            }
        };
    }

    /**
     * Returns latest deliveries, optionally filtered by status.
     */
    public List<DeliverySummary> listDeliveries(String status, int limit) {
        String normalizedStatus = normalizeStatusOrNull(status);
        int normalizedLimit = normalizeLimit(limit);

        synchronized (lock) {
            List<DeliverySummary> result = new ArrayList<>();
            for (String deliveryId : deliveryOrder) {
                DeliveryState state = deliveriesById.get(deliveryId);
                if (state == null) {
                    continue;
                }
                if (normalizedStatus != null && !normalizedStatus.equals(state.getStatus())) {
                    continue;
                }
                result.add(state.toSummary());
                if (result.size() >= normalizedLimit) {
                    break;
                }
            }
            return result;
        }
    }

    /**
     * Returns detailed delivery information including timeline events.
     */
    public Optional<DeliveryDetail> getDelivery(String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank()) {
            return Optional.empty();
        }

        synchronized (lock) {
            DeliveryState state = deliveriesById.get(deliveryId);
            if (state == null) {
                return Optional.empty();
            }
            return Optional.of(state.toDetail());
        }
    }

    /**
     * Retries a failed or completed callback delivery using the last stored
     * payload.
     */
    public Optional<DeliveryDetail> retryDelivery(String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank()) {
            return Optional.empty();
        }

        String callbackUrl;
        CallbackPayload payload;

        synchronized (lock) {
            DeliveryState state = deliveriesById.get(deliveryId);
            if (state == null) {
                return Optional.empty();
            }
            if (!isRetryableStatus(state.getStatus())) {
                return Optional.empty();
            }

            callbackUrl = state.getCallbackUrl();
            payload = state.toCallbackPayload();
            if (callbackUrl == null || callbackUrl.isBlank() || payload == null) {
                return Optional.empty();
            }

            capturePayload(deliveryId, payload, true);
        }

        callbackSender.send(callbackUrl, payload, createObserver(deliveryId));
        return getDelivery(deliveryId);
    }

    /**
     * Sends a dashboard-triggered test callback and tracks it as delivery source
     * {@code test}.
     */
    public DeliveryDetail sendTestCallback(TestCallbackCommand command) {
        validateCallbackUrl(command.callbackUrl());

        String runId = command.runId() != null && !command.runId().isBlank()
                ? command.runId().trim()
                : "test-" + UUID.randomUUID();
        String chatId = command.chatId() != null && !command.chatId().isBlank()
                ? command.chatId().trim()
                : "webhook:test";

        String payloadStatus = normalizePayloadStatus(command.payloadStatus());

        CallbackPayload payload = CallbackPayload.builder()
                .runId(runId)
                .chatId(chatId)
                .status(payloadStatus)
                .response(command.response())
                .model(command.model())
                .durationMs(command.durationMs() > 0 ? command.durationMs() : 1)
                .error("failed".equals(payloadStatus) ? nullToDefault(command.errorMessage(), "Test failure") : null)
                .build();

        String deliveryId = registerPendingDelivery(runId, chatId, command.callbackUrl(), command.model(), SOURCE_TEST);
        capturePayload(deliveryId, payload, false);
        callbackSender.send(command.callbackUrl(), payload, createObserver(deliveryId));

        return getDelivery(deliveryId)
                .orElseThrow(() -> new IllegalStateException("Unable to read test delivery after registration"));
    }

    public void validateStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return;
        }
        normalizeStatus(status.trim());
    }

    public void validateCallbackUrl(String callbackUrl) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            throw new IllegalArgumentException("callbackUrl is required");
        }
        try {
            URI uri = new URI(callbackUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("callbackUrl must be a valid http(s) URL");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("callbackUrl must be a valid http(s) URL", e);
        }
    }

    private void updateAttempt(String deliveryId, int attemptNumber) {
        synchronized (lock) {
            DeliveryState state = deliveriesById.get(deliveryId);
            if (state == null) {
                return;
            }
            state.setStatus(STATUS_IN_PROGRESS);
            state.setAttempts(Math.max(state.getAttempts(), attemptNumber));
            state.setUpdatedAt(Instant.now());
            state.appendEvent(EVENT_ATTEMPT, STATUS_IN_PROGRESS, attemptNumber,
                    "Callback attempt started");
        }
    }

    private void markSuccess(String deliveryId, int attempts) {
        synchronized (lock) {
            DeliveryState state = deliveriesById.get(deliveryId);
            if (state == null) {
                return;
            }
            state.setStatus(STATUS_SUCCESS);
            state.setAttempts(Math.max(state.getAttempts(), attempts));
            state.setLastError(null);
            state.setUpdatedAt(Instant.now());
            state.appendEvent(EVENT_SUCCESS, STATUS_SUCCESS, attempts,
                    "Callback delivered successfully");
        }
    }

    private void markFailure(String deliveryId, int attempts, String errorMessage) {
        synchronized (lock) {
            DeliveryState state = deliveriesById.get(deliveryId);
            if (state == null) {
                return;
            }
            state.setStatus(STATUS_FAILED);
            state.setAttempts(Math.max(state.getAttempts(), attempts));
            state.setLastError(errorMessage);
            state.setUpdatedAt(Instant.now());
            state.appendEvent(EVENT_FAILURE, STATUS_FAILED, attempts,
                    nullToDefault(errorMessage, "Callback failed"));
        }
    }

    private int normalizeLimit(int limit) {
        if (limit < MIN_LIMIT) {
            return MIN_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            return MAX_LIMIT;
        }
        return limit;
    }

    private String normalizeStatusOrNull(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return normalizeStatus(status);
    }

    private String normalizeStatus(String status) {
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (STATUS_PENDING.equals(normalized)
                || STATUS_IN_PROGRESS.equals(normalized)
                || STATUS_SUCCESS.equals(normalized)
                || STATUS_FAILED.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported delivery status filter: " + status);
    }

    private String normalizePayloadStatus(String status) {
        if (status == null || status.isBlank()) {
            return "completed";
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        if ("completed".equals(normalized) || "failed".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("payloadStatus must be completed or failed");
    }

    private boolean isRetryableStatus(String status) {
        return STATUS_SUCCESS.equals(status) || STATUS_FAILED.equals(status);
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        if (maxChars <= 15) {
            return value.substring(0, Math.max(1, maxChars));
        }
        return value.substring(0, maxChars - 15) + "... [truncated]";
    }

    private String nullToDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private void evictOverflowIfNeeded() {
        int maxEntries = resolveMaxEntries();
        while (deliveryOrder.size() > maxEntries) {
            String evictedDeliveryId = deliveryOrder.removeLast();
            deliveriesById.remove(evictedDeliveryId);
        }
    }

    private int resolveMaxEntries() {
        int configured = botProperties.getWebhooks().getDeliveryHistoryMaxEntries();
        return configured > 0 ? configured : DEFAULT_MAX_ENTRIES;
    }

    public record TestCallbackCommand(
            String callbackUrl,
            String runId,
            String chatId,
            String model,
            String payloadStatus,
            String response,
            long durationMs,
            String errorMessage) {
    }

    public record DeliverySummary(
            String deliveryId,
            String runId,
            String chatId,
            String source,
            String callbackUrl,
            String model,
            String status,
            int attempts,
            String lastError,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record DeliveryDetail(
            String deliveryId,
            String runId,
            String chatId,
            String source,
            String callbackUrl,
            String model,
            String status,
            int attempts,
            String lastError,
            Instant createdAt,
            Instant updatedAt,
            PayloadSnapshot payload,
            List<DeliveryEvent> events) {
    }

    public record PayloadSnapshot(
            String status,
            String response,
            String model,
            long durationMs,
            String error) {
    }

    public record DeliveryEvent(
            int sequence,
            String type,
            String status,
            Instant timestamp,
            Integer attempt,
            String message) {
    }

    @lombok.Builder
    @lombok.Data
    private static class DeliveryState {
        private String deliveryId;
        private String runId;
        private String chatId;
        private String source;
        private String callbackUrl;
        private String model;
        private String status;
        private int attempts;
        private String lastError;
        private Instant createdAt;
        private Instant updatedAt;
        private String payloadStatus;
        private String payloadResponse;
        private String payloadModel;
        private long payloadDurationMs;
        private String payloadError;
        private List<DeliveryEvent> events;

        private void appendEvent(String type, String currentStatus, Integer attempt, String message) {
            int sequence = events.size() + 1;
            events.add(new DeliveryEvent(
                    sequence,
                    type,
                    currentStatus,
                    Instant.now(),
                    attempt,
                    message));

            while (events.size() > DEFAULT_MAX_EVENTS_PER_DELIVERY) {
                events.remove(0);
            }
        }

        private DeliverySummary toSummary() {
            return new DeliverySummary(
                    deliveryId,
                    runId,
                    chatId,
                    source,
                    callbackUrl,
                    model,
                    status,
                    attempts,
                    lastError,
                    createdAt,
                    updatedAt);
        }

        private DeliveryDetail toDetail() {
            PayloadSnapshot payloadSnapshot = new PayloadSnapshot(
                    payloadStatus,
                    truncate(payloadResponse, DEFAULT_MAX_PAYLOAD_RESPONSE_CHARS),
                    payloadModel,
                    payloadDurationMs,
                    payloadError);
            return new DeliveryDetail(
                    deliveryId,
                    runId,
                    chatId,
                    source,
                    callbackUrl,
                    model,
                    status,
                    attempts,
                    lastError,
                    createdAt,
                    updatedAt,
                    payloadSnapshot,
                    List.copyOf(events));
        }

        private CallbackPayload toCallbackPayload() {
            if (payloadStatus == null) {
                return null;
            }
            return CallbackPayload.builder()
                    .runId(runId)
                    .chatId(chatId)
                    .status(payloadStatus)
                    .response(payloadResponse)
                    .model(payloadModel != null ? payloadModel : model)
                    .durationMs(payloadDurationMs)
                    .error(payloadError)
                    .build();
        }
    }
}
