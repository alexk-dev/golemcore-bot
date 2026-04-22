package me.golemcore.bot.port.outbound;

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

import java.util.List;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCatalogEntry;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCompareEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactNormalizedRevisionProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionDiffProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionDiffProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;

/**
 * Port for publishing SelfEvolving projections and catalog/search snapshots to
 * external systems.
 */
public interface SelfEvolvingProjectionPublishPort {

    void publishSelfEvolvingProjection(RunRecord runRecord, RunVerdict verdict, List<EvolutionCandidate> candidates);

    void publishSelfEvolvingCampaignProjection(String golemId, BenchmarkCampaign campaign);

    void publishSelfEvolvingTacticCatalogProjection(List<TacticSearchResult> tactics);

    void publishSelfEvolvingTacticSearchProjection(
            String query,
            TacticSearchStatus status,
            List<TacticSearchResult> results);

    void publishSelfEvolvingArtifactProjection(String golemId, ArtifactCatalogEntry artifact);

    void publishSelfEvolvingArtifactNormalizedRevisionProjection(
            String golemId,
            ArtifactNormalizedRevisionProjection projection);

    void publishSelfEvolvingArtifactLineageProjection(String golemId, ArtifactLineageProjection projection);

    void publishSelfEvolvingArtifactRevisionDiffProjection(
            String golemId,
            ArtifactRevisionDiffProjection projection);

    void publishSelfEvolvingArtifactTransitionDiffProjection(
            String golemId,
            ArtifactTransitionDiffProjection projection);

    void publishSelfEvolvingArtifactRevisionEvidenceProjection(
            String golemId,
            ArtifactRevisionEvidenceProjection projection);

    void publishSelfEvolvingArtifactTransitionEvidenceProjection(
            String golemId,
            ArtifactTransitionEvidenceProjection projection);

    void publishSelfEvolvingArtifactCompareEvidenceProjection(
            String golemId,
            ArtifactCompareEvidenceProjection projection);
}
