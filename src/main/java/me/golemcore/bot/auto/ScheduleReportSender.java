package me.golemcore.bot.auto;

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
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.ChannelTypes;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.ScheduleReportConfig;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.channel.ChannelPort;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends schedule run reports to configured report channels. Supports two
 * delivery modes:
 * <ul>
 * <li>Channel-based delivery via {@link ChannelPort} (e.g. Telegram)</li>
 * <li>Outgoing webhook delivery via direct HTTP POST</li>
 * </ul>
 */
@Component
@Slf4j
public class ScheduleReportSender {

    private static final int CHANNEL_SEND_TIMEOUT_SECONDS = 30;
    private static final int WEBHOOK_MAX_RETRIES = 3;
    private static final long WEBHOOK_INITIAL_BACKOFF_MILLIS = 100L;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final ChannelRegistry channelRegistry;
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private BackoffSleeper backoffSleeper = Thread::sleep;

    public ScheduleReportSender(ChannelRegistry channelRegistry, OkHttpClient okHttpClient, ObjectMapper objectMapper) {
        this.channelRegistry = channelRegistry;
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
    }

    // Package-private test hook so Spring keeps a single constructor candidate.
    void setBackoffSleeperForTest(BackoffSleeper backoffSleeper) {
        this.backoffSleeper = Objects.requireNonNull(backoffSleeper);
    }

    /**
     * Send a run report for the given schedule.
     *
     * @param schedule
     *            the schedule entry with report configuration
     * @param reportHeader
     *            the header text (e.g. "📋 Goal: X / Task: Y")
     * @param assistantText
     *            the assistant summary to include in the report
     * @param fallbackChannelInfo
     *            the active channel binding for chatId auto-resolution
     */
    public void sendReport(ScheduleEntry schedule, String reportHeader, String assistantText,
            ScheduleDeliveryContext fallbackDeliveryContext) {
        if (!hasReportChannel(schedule)) {
            return;
        }
        if (StringValueSupport.isBlank(assistantText)) {
            log.debug("[ReportSender] No assistant text to report for schedule {}", schedule.getId());
            return;
        }

        ScheduleReportConfig report = schedule.getReport();
        if (ChannelTypes.WEBHOOK.equals(report.getChannelType())) {
            sendViaWebhook(schedule, reportHeader, assistantText);
        } else {
            sendViaChannel(schedule, reportHeader, assistantText, fallbackDeliveryContext);
        }
    }

    private void sendViaChannel(ScheduleEntry schedule, String reportHeader, String assistantText,
            ScheduleDeliveryContext fallbackDeliveryContext) {
        ScheduleReportConfig report = schedule.getReport();
        String chatId = resolveChatId(report, fallbackDeliveryContext);
        if (StringValueSupport.isBlank(chatId)) {
            log.warn("[ReportSender] Cannot resolve chatId for channel '{}', skipping report",
                    report.getChannelType());
            return;
        }

        ChannelPort channel = channelRegistry.get(report.getChannelType()).orElse(null);
        if (channel == null) {
            log.warn("[ReportSender] Channel '{}' not found in registry", report.getChannelType());
            return;
        }

        String reportText = reportHeader + "\n\n" + assistantText;
        try {
            channel.sendMessage(chatId, reportText).get(CHANNEL_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("[ReportSender] Sent report to {} for schedule {}", report.getChannelType(),
                    schedule.getId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[ReportSender] Interrupted sending report: {}", e.getMessage());
        } catch (ExecutionException | TimeoutException e) {
            log.error("[ReportSender] Failed to send report via {}: {}", report.getChannelType(),
                    e.getMessage());
        }
    }

    private void sendViaWebhook(ScheduleEntry schedule, String reportHeader, String assistantText) {
        ScheduleReportConfig report = schedule.getReport();
        String webhookUrl = report.getWebhookUrl();
        if (StringValueSupport.isBlank(webhookUrl)) {
            log.warn("[ReportSender] Webhook URL is not configured for schedule {}", schedule.getId());
            return;
        }

        String fullReport = reportHeader + "\n\n" + assistantText;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", fullReport);
        payload.put("scheduleId", schedule.getId());
        payload.put("targetType", schedule.getType() != null ? schedule.getType().name() : null);
        payload.put("timestamp", Instant.now().toString());

        try {
            String json = objectMapper.writeValueAsString(payload);
            executeWebhookWithRetries(webhookUrl, json, report.getWebhookBearerToken(), schedule.getId());
        } catch (Exception e) { // NOSONAR — payload serialization must not crash the scheduler
            log.error("[ReportSender] Failed to serialize webhook report for {}: {}", webhookUrl, e.getMessage());
        }
    }

    private void executeWebhookWithRetries(String webhookUrl, String json, String bearerToken, String scheduleId) {
        for (int attempt = 0; attempt <= WEBHOOK_MAX_RETRIES; attempt++) {
            Request request = buildWebhookRequest(webhookUrl, json, bearerToken);
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    log.info("[ReportSender] Webhook POST to {} returned {} for schedule {}",
                            webhookUrl, response.code(), scheduleId);
                    return;
                }
                if (!retryOrComplete(attempt, webhookUrl, scheduleId,
                        "failed with status " + response.code())) {
                    return;
                }
            } catch (Exception e) { // NOSONAR — report delivery must not crash the scheduler
                if (!retryOrComplete(attempt, webhookUrl, scheduleId, e.getMessage())) {
                    return;
                }
            }
        }
    }

    private boolean retryOrComplete(int attempt, String webhookUrl, String scheduleId, String reason) {
        if (!shouldRetryWebhookAttempt(attempt)) {
            log.warn("[ReportSender] Webhook POST to {} {} for schedule {} after {} attempts;"
                    + " treating report delivery as complete",
                    webhookUrl, reason, scheduleId, attempt + 1);
            return false;
        }
        long backoffMillis = computeWebhookBackoffMillis(attempt);
        log.warn("[ReportSender] Webhook POST to {} {} for schedule {}; retrying in {} ms (attempt {}/{})",
                webhookUrl, reason, scheduleId, backoffMillis, attempt + 1, WEBHOOK_MAX_RETRIES + 1);
        return sleepBeforeRetry(backoffMillis, webhookUrl, scheduleId);
    }

    private Request buildWebhookRequest(String webhookUrl, String json, String secret) {
        RequestBody body = RequestBody.create(json, JSON_MEDIA_TYPE);
        Request.Builder requestBuilder = new Request.Builder()
                .url(webhookUrl)
                .post(body);
        if (!StringValueSupport.isBlank(secret)) {
            requestBuilder.header("Authorization", "Bearer " + secret);
        }
        return requestBuilder.build();
    }

    private boolean sleepBeforeRetry(long backoffMillis, String webhookUrl, String scheduleId) {
        try {
            backoffSleeper.sleep(backoffMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[ReportSender] Interrupted during webhook retry backoff for {} schedule {}; treating report"
                    + " delivery as complete",
                    webhookUrl, scheduleId);
            return false;
        }
    }

    private static long computeWebhookBackoffMillis(int attempt) {
        return WEBHOOK_INITIAL_BACKOFF_MILLIS * (1L << attempt);
    }

    private static boolean shouldRetryWebhookAttempt(int attempt) {
        return attempt < WEBHOOK_MAX_RETRIES;
    }

    private static String resolveChatId(ScheduleReportConfig report, ScheduleDeliveryContext fallbackDeliveryContext) {
        String chatId = report.getChatId();
        if (!StringValueSupport.isBlank(chatId)) {
            return chatId;
        }
        if (fallbackDeliveryContext != null
                && report.getChannelType().equals(fallbackDeliveryContext.channelType())) {
            return fallbackDeliveryContext.transportChatId();
        }
        return null;
    }

    private static boolean hasReportChannel(ScheduleEntry schedule) {
        ScheduleReportConfig report = schedule.getReport();
        return report != null && !StringValueSupport.isBlank(report.getChannelType());
    }

    @FunctionalInterface
    interface BackoffSleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
