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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Represents a single task within a goal in autonomous mode. Tasks track
 * progress toward goal completion with status, results, and ordering.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutoTask {

    private String id;
    private String goalId;
    private String title;
    private String description;

    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;

    private String result;
    private int order;
    private Instant createdAt;
    private Instant updatedAt;

    public enum TaskStatus {
        PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED
    }
}
