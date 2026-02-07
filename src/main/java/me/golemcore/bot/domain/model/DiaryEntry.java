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
 * Represents a diary entry for logging autonomous agent's work, thoughts, and
 * decisions. Entries are categorized by type and linked to specific goals and
 * tasks.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiaryEntry {

    private Instant timestamp;
    private DiaryType type;
    private String content;
    private String goalId;
    private String taskId;

    public enum DiaryType {
        THOUGHT, PROGRESS, OBSERVATION, DECISION, ERROR
    }
}
