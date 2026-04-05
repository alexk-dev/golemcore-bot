package me.golemcore.bot.domain.model.selfevolving;

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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical SelfEvolving run aggregate linked to a single golem.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RunRecord {

    private String id;
    private String golemId;
    private String sessionId;
    private String traceId;
    private String artifactBundleId;
    private String verdictId;
    private String status;
    private String runType;
    private Instant startedAt;
    private Instant completedAt;

    @Builder.Default
    private Map<String, Object> metrics = new LinkedHashMap<>();

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    @Builder.Default
    private List<String> appliedTacticIds = new ArrayList<>();
}
