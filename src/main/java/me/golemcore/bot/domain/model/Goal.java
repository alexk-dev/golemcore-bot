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
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a high-level objective for autonomous mode. Goals contain multiple
 * tasks and track overall progress and status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Goal {

    private String id;
    private String title;
    private String description;

    @Builder.Default
    private GoalStatus status = GoalStatus.ACTIVE;

    @Builder.Default
    private List<AutoTask> tasks = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Calculates the number of completed tasks (not serialized to JSON).
     */
    @JsonIgnore
    public long getCompletedTaskCount() {
        return tasks.stream()
                .filter(t -> t.getStatus() == AutoTask.TaskStatus.COMPLETED)
                .count();
    }

    /**
     * Goal lifecycle states.
     */
    public enum GoalStatus {
        ACTIVE, COMPLETED, PAUSED, CANCELLED
    }
}
