package me.golemcore.bot.domain.service;

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

import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactImpactProjection;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes attribution-aware artifact impact summaries.
 */
@Service
public class ArtifactImpactService {

    private static final int PROJECTION_SCHEMA_VERSION = 1;

    public ArtifactImpactProjection summarizeImpact(
            String artifactStreamId,
            String fromRevisionId,
            String toRevisionId,
            ArtifactBundleRecord baselineBundle,
            ArtifactBundleRecord candidateBundle,
            List<BenchmarkCampaign> campaigns,
            Set<String> regressionRevisionIds) {
        Map<String, String> baselineBindings = baselineBundle != null
                && baselineBundle.getArtifactRevisionBindings() != null
                        ? baselineBundle.getArtifactRevisionBindings()
                        : Map.of();
        Map<String, String> candidateBindings = candidateBundle != null
                && candidateBundle.getArtifactRevisionBindings() != null
                        ? candidateBundle.getArtifactRevisionBindings()
                        : Map.of();

        int changedBindings = countChangedBindings(baselineBindings, candidateBindings);
        String attributionMode = changedBindings <= 1 ? "isolated" : "mixed_change";
        if (baselineBindings.isEmpty() || candidateBindings.isEmpty()) {
            attributionMode = "bundle_observed";
        }

        List<BenchmarkCampaign> matchingCampaigns = new ArrayList<>();
        if (campaigns != null) {
            for (BenchmarkCampaign campaign : campaigns) {
                if (campaign == null) {
                    continue;
                }
                if (baselineBundle != null && candidateBundle != null
                        && StringValueSupport.nullSafe(baselineBundle.getId()).equals(campaign.getBaselineBundleId())
                        && StringValueSupport.nullSafe(candidateBundle.getId())
                                .equals(campaign.getCandidateBundleId())) {
                    matchingCampaigns.add(campaign);
                }
            }
        }

        boolean regressionIntroduced = regressionRevisionIds != null && regressionRevisionIds.contains(toRevisionId);
        boolean regressionResolved = regressionRevisionIds != null
                && regressionRevisionIds.contains(fromRevisionId)
                && !regressionRevisionIds.contains(toRevisionId);

        return ArtifactImpactProjection.builder()
                .artifactStreamId(artifactStreamId)
                .fromRevisionId(fromRevisionId)
                .toRevisionId(toRevisionId)
                .attributionMode(attributionMode)
                .campaignDelta(matchingCampaigns.size())
                .campaignIds(matchingCampaigns.stream().map(BenchmarkCampaign::getId).toList())
                .regressionIntroduced(regressionIntroduced)
                .regressionResolved(regressionResolved)
                .summary(buildSummary(attributionMode, matchingCampaigns.size(), regressionIntroduced,
                        regressionResolved))
                .projectionSchemaVersion(PROJECTION_SCHEMA_VERSION)
                .projectedAt(Instant.now())
                .build();
    }

    private int countChangedBindings(Map<String, String> baselineBindings, Map<String, String> candidateBindings) {
        int changed = 0;
        for (String artifactStreamId : baselineBindings.keySet()) {
            if (!StringValueSupport.nullSafe(baselineBindings.get(artifactStreamId))
                    .equals(StringValueSupport.nullSafe(candidateBindings.get(artifactStreamId)))) {
                changed++;
            }
        }
        for (String artifactStreamId : candidateBindings.keySet()) {
            if (!baselineBindings.containsKey(artifactStreamId)) {
                changed++;
            }
        }
        return changed;
    }

    private String buildSummary(
            String attributionMode,
            int campaignDelta,
            boolean regressionIntroduced,
            boolean regressionResolved) {
        if (regressionIntroduced) {
            return attributionMode + ": regression introduced across " + campaignDelta + " campaigns";
        }
        if (regressionResolved) {
            return attributionMode + ": regression resolved";
        }
        return attributionMode + ": compared across " + campaignDelta + " campaigns";
    }
}
