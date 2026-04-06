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

import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticDto;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveInspectionResponse;
import me.golemcore.bot.domain.model.ProgressUpdate;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.hive.HiveLifecycleSignalRequest;
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

import java.util.List;
import java.util.Map;

/**
 * Outbound port for Hive-specific event publishing operations.
 */
public interface HiveEventPublishPort extends SelfEvolvingProjectionPublishPort {

    void publishCommandAcknowledged(HiveControlCommandEnvelope envelope);

    void publishInspectionResponse(HiveInspectionResponse response);

    void publishRuntimeEvents(List<RuntimeEvent> runtimeEvents, Map<String, Object> metadata);

    void publishProgressUpdate(String threadId, ProgressUpdate update);

    void publishThreadMessage(String threadId, String content, Map<String, Object> metadata);

    void publishLifecycleSignal(HiveLifecycleSignalRequest request, Map<String, Object> metadata);

    void publishSelfEvolvingCampaignProjection(String golemId, BenchmarkCampaign campaign);

    void publishSelfEvolvingTacticCatalogProjection(List<SelfEvolvingTacticDto> tactics);

    void publishSelfEvolvingTacticSearchProjection(
            String query,
            TacticSearchStatus status,
            List<TacticSearchResult> results);

    void publishSelfEvolvingArtifactProjection(String golemId, ArtifactCatalogEntry artifact);

    void publishSelfEvolvingArtifactNormalizedRevisionProjection(String golemId,
            ArtifactNormalizedRevisionProjection projection);

    void publishSelfEvolvingArtifactLineageProjection(String golemId, ArtifactLineageProjection projection);

    void publishSelfEvolvingArtifactRevisionDiffProjection(String golemId,
            ArtifactRevisionDiffProjection projection);

    void publishSelfEvolvingArtifactTransitionDiffProjection(String golemId,
            ArtifactTransitionDiffProjection projection);

    void publishSelfEvolvingArtifactRevisionEvidenceProjection(String golemId,
            ArtifactRevisionEvidenceProjection projection);

    void publishSelfEvolvingArtifactTransitionEvidenceProjection(String golemId,
            ArtifactTransitionEvidenceProjection projection);

    void publishSelfEvolvingArtifactCompareEvidenceProjection(String golemId,
            ArtifactCompareEvidenceProjection projection);

    @Override
    void publishSelfEvolvingProjection(RunRecord runRecord, RunVerdict verdict, List<EvolutionCandidate> candidates);
}
