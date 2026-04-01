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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.inbound.ChannelPort;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
@RequiredArgsConstructor
@Slf4j
public class ScheduleReportSender {

    public static final String WEBHOOK_CHANNEL_TYPE = "webhook";
    private static final int CHANNEL_SEND_TIMEOUT_SECONDS = 30;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final ChannelRegistry channelRegistry;
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

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
            AutoModeScheduler.ChannelInfo fallbackChannelInfo) {
        if (!hasReportChannel(schedule)) {
            return;
        }
        if (StringValueSupport.isBlank(assistantText)) {
            log.debug("[ReportSender] No assistant text to report for schedule {}", schedule.getId());
            return;
        }

        if (WEBHOOK_CHANNEL_TYPE.equals(schedule.getReportChannelType())) {
            sendViaWebhook(schedule, reportHeader, assistantText);
        } else {
            sendViaChannel(schedule, reportHeader, assistantText, fallbackChannelInfo);
        }
    }

    private void sendViaChannel(ScheduleEntry schedule, String reportHeader, String assistantText,
            AutoModeScheduler.ChannelInfo fallbackChannelInfo) {
        String chatId = resolveChatId(schedule, fallbackChannelInfo);
        if (StringValueSupport.isBlank(chatId)) {
            log.warn("[ReportSender] Cannot resolve chatId for channel '{}', skipping report",
                    schedule.getReportChannelType());
            return;
        }

        ChannelPort channel = channelRegistry.get(schedule.getReportChannelType()).orElse(null);
        if (channel == null) {
            log.warn("[ReportSender] Channel '{}' not found in registry", schedule.getReportChannelType());
            return;
        }

        String report = reportHeader + "\n\n" + assistantText;
        try {
            channel.sendMessage(chatId, report).get(CHANNEL_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("[ReportSender] Sent report to {} for schedule {}", schedule.getReportChannelType(),
                    schedule.getId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[ReportSender] Interrupted sending report: {}", e.getMessage());
        } catch (ExecutionException | TimeoutException e) {
            log.error("[ReportSender] Failed to send report via {}: {}", schedule.getReportChannelType(),
                    e.getMessage());
        }
    }

    private void sendViaWebhook(ScheduleEntry schedule, String reportHeader, String assistantText) {
        String webhookUrl = schedule.getReportWebhookUrl();
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
            RequestBody body = RequestBody.create(json, JSON_MEDIA_TYPE);

            Request.Builder requestBuilder = new Request.Builder()
                    .url(webhookUrl)
                    .post(body);

            String secret = schedule.getReportWebhookSecret();
            if (!StringValueSupport.isBlank(secret)) {
                requestBuilder.header("Authorization", "Bearer " + secret);
            }

            try (Response response = okHttpClient.newCall(requestBuilder.build()).execute()) {
                log.info("[ReportSender] Webhook POST to {} returned {} for schedule {}",
                        webhookUrl, response.code(), schedule.getId());
            }
        } catch (Exception e) { // NOSONAR — report delivery must not crash the scheduler
            log.error("[ReportSender] Failed to send webhook report to {}: {}", webhookUrl, e.getMessage());
        }
    }

    private static String resolveChatId(ScheduleEntry schedule, AutoModeScheduler.ChannelInfo fallbackChannelInfo) {
        String chatId = schedule.getReportChatId();
        if (!StringValueSupport.isBlank(chatId)) {
            return chatId;
        }
        if (fallbackChannelInfo != null
                && schedule.getReportChannelType().equals(fallbackChannelInfo.channelType())) {
            return fallbackChannelInfo.transportChatId();
        }
        return null;
    }

    private static boolean hasReportChannel(ScheduleEntry schedule) {
        return schedule.getReportChannelType() != null && !schedule.getReportChannelType().isBlank();
    }
}
