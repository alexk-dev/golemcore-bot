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

import java.util.ArrayList;
import java.util.List;

/**
 * Explainability payload for a tactic-search result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TacticSearchExplanation {

    @Builder.Default
    private String searchMode = "bm25";

    private String degradedReason;

    @Builder.Default
    private Double bm25Score = 0.0d;

    @Builder.Default
    private Double vectorScore = 0.0d;

    @Builder.Default
    private Double rrfScore = 0.0d;

    @Builder.Default
    private Double qualityPrior = 0.0d;

    @Builder.Default
    private Double mmrDiversityAdjustment = 0.0d;

    @Builder.Default
    private Double negativeMemoryPenalty = 0.0d;

    @Builder.Default
    private Double personalizationBoost = 0.0d;

    private String rerankerVerdict;

    @Builder.Default
    private List<String> matchedQueryViews = new ArrayList<>();

    @Builder.Default
    private List<String> matchedTerms = new ArrayList<>();

    @Builder.Default
    private Boolean eligible = true;

    private String gatingReason;

    @Builder.Default
    private Double finalScore = 0.0d;
}
