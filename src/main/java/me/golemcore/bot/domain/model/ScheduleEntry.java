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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Represents a cron-based schedule entry for autonomous goal/task execution.
 * Schedules are persisted in {@code auto/schedules.json} and evaluated by the
 * scheduler tick loop.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleEntry {

    private String id;
    private ScheduleType type;
    private String targetId;
    private String cronExpression;
    private boolean enabled;

    @Builder.Default
    private int maxExecutions = -1;

    private int executionCount;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastExecutedAt;
    private Instant nextExecutionAt;

    /**
     * Schedule target types.
     */
    public enum ScheduleType {
        GOAL, TASK
    }

    /**
     * Whether this schedule has reached its maximum execution count.
     */
    @JsonIgnore
    public boolean isExhausted() {
        return maxExecutions > 0 && executionCount >= maxExecutions;
    }
}
