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
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persistent autonomous task that can be scheduled independently of any chat
 * session goal/task tree.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScheduledTask {

    private String id;
    private String title;
    private String description;
    private String prompt;
    private String reflectionModelTier;
    private boolean reflectionTierPriority;

    @Builder.Default
    private int consecutiveFailureCount = 0;

    @Builder.Default
    private boolean reflectionRequired = false;

    private String lastFailureSummary;
    private String lastFailureFingerprint;
    private String reflectionStrategy;
    private String lastUsedSkillName;
    private String legacySourceType;
    private String legacySourceId;
    private Instant lastFailureAt;
    private Instant lastReflectionAt;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Returns the prompt that should be used for scheduled execution.
     */
    @JsonIgnore
    public String getExecutionPrompt() {
        String basePrompt = prompt != null && !prompt.isBlank() ? prompt : title;
        if (reflectionStrategy == null || reflectionStrategy.isBlank()) {
            return basePrompt;
        }
        return basePrompt + "\n\nRecovery strategy from previous reflection:\n" + reflectionStrategy;
    }
}
