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
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public Set<String> resolveMatchingBundleIds(TacticRecord record, List<ArtifactBundleRecord> bundles) {
        Set<String> bundleIds = new LinkedHashSet<>();
        if (record == null
                || StringValueSupport.isBlank(record.getArtifactStreamId())
                || StringValueSupport.isBlank(record.getContentRevisionId())
                || bundles == null
                || bundles.isEmpty()) {
            return bundleIds;
        }
        for (ArtifactBundleRecord bundle : bundles) {
            if (bundle == null || StringValueSupport.isBlank(bundle.getId())) {
                continue;
            }
            Map<String, String> bindings = bundle.getArtifactRevisionBindings();
            if (bindings == null || bindings.isEmpty()) {
                continue;
            }
            String boundRevision = bindings.get(record.getArtifactStreamId());
            if (record.getContentRevisionId().equals(boundRevision)) {
                bundleIds.add(bundle.getId());
            }
        }
        return bundleIds;
    }

    public LinkedHashSet<String> resolveAttributedTacticIds(
            RunRecord run,
            Map<String, List<String>> bundleIdToTacticIds,
            Set<String> knownTacticIds) {
        LinkedHashSet<String> attributedTacticIds = new LinkedHashSet<>();
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

    private String streamRevisionKey(String streamId, String revisionId) {
        return streamId + "\u0000" + revisionId;
    }
}
