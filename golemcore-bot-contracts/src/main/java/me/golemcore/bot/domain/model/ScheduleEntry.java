package me.golemcore.bot.domain.model;

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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * Represents a cron-based schedule entry for autonomous goal/task execution.
 * Schedules are persisted in {@code auto/schedules.json} and evaluated by the
 * scheduler tick loop.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScheduleEntry {

    private String id;
    private ScheduleType type;
    private String targetId;
    private String cronExpression;
    private boolean enabled;
    private boolean clearContextBeforeRun;
    private ScheduleReportConfig report;

    @Builder.Default
    private int maxExecutions = -1;

    private int executionCount;
    @Builder.Default
    private int retryCount = 0;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastExecutedAt;
    private Instant nextExecutionAt;
    private Instant activeWindowStartedAt;
    private Instant nextWindowAt;

    /**
     * Schedule target types.
     */
    public enum ScheduleType {
        GOAL, TASK, SCHEDULED_TASK
    }

    /**
     * Whether this schedule has reached its maximum execution count.
     */
    @JsonIgnore
    public boolean isExhausted() {
        return maxExecutions > 0 && executionCount >= maxExecutions;
    }

    @JsonSetter("reportChannelType")
    public void setLegacyReportChannelType(String value) {
        applyLegacyReportField(value, normalized -> getOrCreateReport().setChannelType(normalized));
    }

    @JsonSetter("reportChatId")
    public void setLegacyReportChatId(String value) {
        applyLegacyReportField(value, normalized -> getOrCreateReport().setChatId(normalized));
    }

    @JsonSetter("reportWebhookUrl")
    public void setLegacyReportWebhookUrl(String value) {
        applyLegacyReportField(value, normalized -> getOrCreateReport().setWebhookUrl(normalized));
    }

    @JsonSetter("reportWebhookSecret")
    public void setLegacyReportWebhookSecret(String value) {
        applyLegacyReportField(value, normalized -> getOrCreateReport().setWebhookBearerToken(normalized));
    }

    private void applyLegacyReportField(String value, Consumer<String> consumer) {
        if (value == null || value.isBlank()) {
            return;
        }
        consumer.accept(value.trim());
    }

    private ScheduleReportConfig getOrCreateReport() {
        if (report == null) {
            report = new ScheduleReportConfig();
        }
        return report;
    }
}
