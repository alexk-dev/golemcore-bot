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
 * Represents an execution plan containing ordered steps of tool calls proposed
 * by the LLM. Plans go through a lifecycle: collecting steps, user review,
 * approval, execution, and completion.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Plan {

    private String id;
    private String title;
    private String description;

    @Builder.Default
    private PlanStatus status = PlanStatus.COLLECTING;

    @Builder.Default
    private List<PlanStep> steps = new ArrayList<>();

    private String modelTier;
    private String chatId;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Calculates the number of completed steps (not serialized to JSON).
     */
    @JsonIgnore
    public long getCompletedStepCount() {
        return steps.stream()
                .filter(s -> s.getStatus() == PlanStep.StepStatus.COMPLETED)
                .count();
    }

    /**
     * Calculates the number of failed steps (not serialized to JSON).
     */
    @JsonIgnore
    public long getFailedStepCount() {
        return steps.stream()
                .filter(s -> s.getStatus() == PlanStep.StepStatus.FAILED)
                .count();
    }

    /**
     * Plan lifecycle states.
     */
    public enum PlanStatus {
        COLLECTING, READY, APPROVED, EXECUTING, COMPLETED, PARTIALLY_COMPLETED, CANCELLED
    }
}
