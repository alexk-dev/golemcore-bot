package me.golemcore.bot.domain.model.selfevolving.tactic;

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
import java.util.List;

/**
 * Ranked tactic-search result with promotion and explainability metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TacticSearchResult {

    private String tacticId;
    private String artifactStreamId;
    private String artifactKey;
    private String artifactType;
    private String title;

    @Builder.Default
    private List<String> aliases = new ArrayList<>();

    private String promotionState;
    private String rolloutStage;
    private Double score;
    private Double successRate;
    private Double benchmarkWinRate;

    @Builder.Default
    private List<String> regressionFlags = new ArrayList<>();

    private Double recencyScore;
    private Double golemLocalUsageSuccess;
    private Instant updatedAt;
    private TacticSearchExplanation explanation;
}
