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
import java.util.ArrayList;
import java.util.List;

/**
 * Structured memory record used by Memory V2 retrieval and persistence.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoryItem {

    public enum Layer {
        WORKING, EPISODIC, SEMANTIC, PROCEDURAL
    }

    public enum Type {
        DECISION, CONSTRAINT, FAILURE, FIX, PREFERENCE, PROJECT_FACT, TASK_STATE, COMMAND_RESULT
    }

    private String id;
    private Layer layer;
    private Type type;
    private String title;
    private String content;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private String source;
    private Double confidence;
    private Double salience;
    private Integer ttlDays;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastAccessedAt;

    @Builder.Default
    private List<String> references = new ArrayList<>();

    private String fingerprint;
}
