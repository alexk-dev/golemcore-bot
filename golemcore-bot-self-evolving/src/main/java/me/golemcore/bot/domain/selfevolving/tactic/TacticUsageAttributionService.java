package me.golemcore.bot.domain.selfevolving.tactic;

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
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.golemcore.bot.domain.support.StringValueSupport;

/**
 * Resolves which tactic ids a bundle or run should be attributed to.
 */
@Service
public class TacticUsageAttributionService {

    public Map<String, List<String>> indexBundleToTacticIds(
            List<ArtifactBundleRecord> bundles,
            Map<String, List<String>> tacticIdsByStreamRevision) {
        Map<String, List<String>> bundleIdToTacticIds = new HashMap<>();
        if (bundles == null || bundles.isEmpty() || tacticIdsByStreamRevision == null
                || tacticIdsByStreamRevision.isEmpty()) {
            return bundleIdToTacticIds;
        }
        for (ArtifactBundleRecord bundle : bundles) {
            if (bundle == null || StringValueSupport.isBlank(bundle.getId())) {
                continue;
            }
            Map<String, String> bindings = bundle.getArtifactRevisionBindings();
            if (bindings == null || bindings.isEmpty()) {
                continue;
            }
            List<String> matchedTacticIds = new ArrayList<>();
            for (Map.Entry<String, String> binding : bindings.entrySet()) {
                List<String> tacticIds = tacticIdsByStreamRevision
                        .get(streamRevisionKey(binding.getKey(), binding.getValue()));
                if (tacticIds != null) {
                    matchedTacticIds.addAll(tacticIds);
                }
            }
            if (!matchedTacticIds.isEmpty()) {
                bundleIdToTacticIds.put(bundle.getId(), matchedTacticIds);
            }
        }
        return bundleIdToTacticIds;
    }

    public Set<String> resolveAttributedTacticIds(
            RunRecord run,
            Map<String, List<String>> bundleIdToTacticIds,
            Set<String> knownTacticIds) {
        Set<String> attributedTacticIds = new LinkedHashSet<>();
        if (run == null) {
            return attributedTacticIds;
        }
        List<String> viaBundle = bundleIdToTacticIds != null ? bundleIdToTacticIds.get(run.getArtifactBundleId())
                : null;
        if (viaBundle != null) {
            attributedTacticIds.addAll(viaBundle);
        }
        if (run.getAppliedTacticIds() == null || knownTacticIds == null || knownTacticIds.isEmpty()) {
            return attributedTacticIds;
        }
        for (String appliedId : run.getAppliedTacticIds()) {
            if (appliedId != null && knownTacticIds.contains(appliedId)) {
                attributedTacticIds.add(appliedId);
            }
        }
        return attributedTacticIds;
    }

    /**
     * Attributes a benchmark campaign to tactics whose (stream, revision) appear in
     * the candidate bundle but differ (or are absent) in the baseline bundle. A
     * tactic is "under test" only when candidate and baseline disagree on it —
     * otherwise the campaign is not actually exercising that tactic.
     */
    public Set<String> resolveCampaignTacticIds(
            BenchmarkCampaign campaign,
            Map<String, ArtifactBundleRecord> bundlesById,
            Map<String, List<String>> tacticIdsByStreamRevision) {
        Set<String> attributed = new LinkedHashSet<>();
        if (campaign == null || bundlesById == null || bundlesById.isEmpty()
                || tacticIdsByStreamRevision == null || tacticIdsByStreamRevision.isEmpty()
                || StringValueSupport.isBlank(campaign.getCandidateBundleId())) {
            return attributed;
        }
        ArtifactBundleRecord candidate = bundlesById.get(campaign.getCandidateBundleId());
        if (candidate == null || candidate.getArtifactRevisionBindings() == null
                || candidate.getArtifactRevisionBindings().isEmpty()) {
            return attributed;
        }
        Map<String, String> baselineBindings = Map.of();
        if (!StringValueSupport.isBlank(campaign.getBaselineBundleId())
                && !campaign.getBaselineBundleId().equals(campaign.getCandidateBundleId())) {
            ArtifactBundleRecord baseline = bundlesById.get(campaign.getBaselineBundleId());
            if (baseline != null) {
                baselineBindings = baseline.getArtifactRevisionBindings();
            }
        }
        for (Map.Entry<String, String> candidateBinding : candidate.getArtifactRevisionBindings().entrySet()) {
            String streamId = candidateBinding.getKey();
            String revisionId = candidateBinding.getValue();
            if (StringValueSupport.isBlank(streamId) || StringValueSupport.isBlank(revisionId)) {
                continue;
            }
            String baselineRevision = baselineBindings.get(streamId);
            if (revisionId.equals(baselineRevision)) {
                // Tactic revision unchanged between baseline and candidate — campaign
                // is not exercising it, so no attribution.
                continue;
            }
            List<String> tacticIds = tacticIdsByStreamRevision
                    .get(streamRevisionKey(streamId, revisionId));
            if (tacticIds != null) {
                attributed.addAll(tacticIds);
            }
        }
        return attributed;
    }

    private String streamRevisionKey(String streamId, String revisionId) {
        return streamId + "\u0000" + revisionId;
    }
}
