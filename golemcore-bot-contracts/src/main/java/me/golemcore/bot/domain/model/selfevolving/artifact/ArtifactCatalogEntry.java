package me.golemcore.bot.domain.model.selfevolving.artifact;

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
 * Top-level workspace list entry for one artifact stream.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArtifactCatalogEntry {

    private String artifactStreamId;
    private String originArtifactStreamId;
    private String artifactKey;

    @Builder.Default
    private List<String> artifactAliases = new ArrayList<>();

    private String artifactType;
    private String artifactSubtype;
    private String displayName;
    private String latestRevisionId;
    private String activeRevisionId;
    private String latestCandidateRevisionId;
    private String currentLifecycleState;
    private String currentRolloutStage;
    private Boolean hasRegression;
    private Boolean hasPendingApproval;
    private Integer campaignCount;
    private Integer projectionSchemaVersion;
    private Instant updatedAt;
    private Instant projectedAt;
}
