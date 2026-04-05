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
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageNode;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lookup and source-snapshot helpers for artifact workspace projections.
 */
@Service
public class ArtifactProjectionLookupService {

    private static final Comparator<ArtifactBundleRecord> BUNDLE_RECENCY_COMPARATOR = Comparator
            .comparing(ArtifactBundleRecord::getActivatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ArtifactBundleRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ArtifactBundleRecord::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final EvolutionCandidateService evolutionCandidateService;
    private final PromotionWorkflowService promotionWorkflowService;
    private final ArtifactBundleService artifactBundleService;
    private final BenchmarkLabService benchmarkLabService;

    public ArtifactProjectionLookupService(
            EvolutionCandidateService evolutionCandidateService,
            PromotionWorkflowService promotionWorkflowService,
            ArtifactBundleService artifactBundleService,
            BenchmarkLabService benchmarkLabService) {
        this.evolutionCandidateService = evolutionCandidateService;
        this.promotionWorkflowService = promotionWorkflowService;
        this.artifactBundleService = artifactBundleService;
        this.benchmarkLabService = benchmarkLabService;
    }

    public Map<String, List<ArtifactRevisionRecord>> revisionsByStream() {
        Map<String, List<ArtifactRevisionRecord>> revisionsByStream = new LinkedHashMap<>();
        for (ArtifactRevisionRecord revision : evolutionCandidateService.getArtifactRevisionRecords()) {
            if (revision == null || StringValueSupport.isBlank(revision.getArtifactStreamId())) {
                continue;
            }
            revisionsByStream.computeIfAbsent(revision.getArtifactStreamId(), ignored -> new ArrayList<>())
                    .add(revision);
        }
        return revisionsByStream;
    }

    public List<ArtifactRevisionRecord> sortRevisions(List<ArtifactRevisionRecord> revisions) {
        List<ArtifactRevisionRecord> sorted = new ArrayList<>(revisions);
        sorted.sort(Comparator.comparing(ArtifactRevisionRecord::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return sorted;
    }

    public Optional<EvolutionCandidate> findLatestCandidate(String artifactStreamId) {
        return promotionWorkflowService.getCandidates().stream()
                .filter(candidate -> candidate != null && artifactStreamId.equals(candidate.getArtifactStreamId()))
                .max(Comparator.comparing(EvolutionCandidate::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
    }

    public List<String> resolveRunIdsForRevision(String artifactStreamId, ArtifactRevisionRecord revision) {
        Set<String> runIds = new LinkedHashSet<>();
        if (revision.getSourceRunIds() != null) {
            runIds.addAll(revision.getSourceRunIds());
        }
        Set<String> candidateRunIds = new LinkedHashSet<>();
        for (EvolutionCandidate candidate : promotionWorkflowService.getCandidates()) {
            if (candidate == null || !artifactStreamId.equals(candidate.getArtifactStreamId())) {
                continue;
            }
            if (!revision.getContentRevisionId().equals(candidate.getContentRevisionId())) {
                continue;
            }
            if (candidate.getSourceRunIds() != null) {
                candidateRunIds.addAll(candidate.getSourceRunIds());
            }
        }
        if (!candidateRunIds.isEmpty()) {
            return new ArrayList<>(candidateRunIds);
        }
        return new ArrayList<>(runIds);
    }

    public Optional<String> resolveActiveRevisionId(String artifactStreamId) {
        List<ArtifactBundleRecord> bundles = artifactBundleService.getBundles().stream()
                .filter(bundle -> bundle != null && bundle.getArtifactRevisionBindings() != null)
                .filter(bundle -> !StringValueSupport
                        .isBlank(bundle.getArtifactRevisionBindings().get(artifactStreamId)))
                .toList();
        return bundles.stream()
                .filter(bundle -> "active".equalsIgnoreCase(bundle.getStatus()))
                .max(BUNDLE_RECENCY_COMPARATOR)
                .or(() -> bundles.stream()
                        .filter(bundle -> StringValueSupport.isBlank(bundle.getStatus())
                                || "snapshot".equalsIgnoreCase(bundle.getStatus()))
                        .max(BUNDLE_RECENCY_COMPARATOR))
                .or(() -> bundles.stream().max(BUNDLE_RECENCY_COMPARATOR))
                .map(bundle -> bundle.getArtifactRevisionBindings().get(artifactStreamId));
    }

    public int resolveCampaignCount(String artifactStreamId) {
        int campaignCount = 0;
        for (BenchmarkCampaign campaign : benchmarkLabService.getCampaigns()) {
            if (campaign == null) {
                continue;
            }
            if (findBundleById(campaign.getBaselineBundleId())
                    .filter(bundle -> bundle.getArtifactRevisionBindings() != null
                            && bundle.getArtifactRevisionBindings().containsKey(artifactStreamId))
                    .isPresent()
                    || findBundleById(campaign.getCandidateBundleId())
                            .filter(bundle -> bundle.getArtifactRevisionBindings() != null
                                    && bundle.getArtifactRevisionBindings().containsKey(artifactStreamId))
                            .isPresent()) {
                campaignCount++;
            }
        }
        return campaignCount;
    }

    public Optional<ArtifactRevisionRecord> findRevision(String artifactStreamId, String revisionId) {
        return evolutionCandidateService.getArtifactRevisionRecords().stream()
                .filter(revision -> revision != null && artifactStreamId.equals(revision.getArtifactStreamId()))
                .filter(revision -> revisionId.equals(revision.getContentRevisionId()))
                .findFirst();
    }

    public Optional<ArtifactBundleRecord> findBundleByRevision(String artifactStreamId, String revisionId) {
        return artifactBundleService.getBundles().stream()
                .filter(bundle -> bundle != null && bundle.getArtifactRevisionBindings() != null)
                .filter(bundle -> revisionId.equals(bundle.getArtifactRevisionBindings().get(artifactStreamId)))
                .max(BUNDLE_RECENCY_COMPARATOR);
    }

    public String resolveTransitionBaseRevisionId(ArtifactLineageNode node) {
        if (node == null) {
            return null;
        }
        if (!StringValueSupport.isBlank(node.getPromotionDecisionId())) {
            Optional<PromotionDecision> decision = findDecisionById(node.getPromotionDecisionId());
            if (decision.isPresent() && !StringValueSupport.isBlank(decision.get().getBaseContentRevisionId())) {
                return decision.get().getBaseContentRevisionId();
            }
        }
        int separatorIndex = node.getNodeId() != null ? node.getNodeId().indexOf(':') : -1;
        if (separatorIndex > 0) {
            String candidateId = node.getNodeId().substring(0, separatorIndex);
            Optional<EvolutionCandidate> candidate = findCandidateById(candidateId);
            if (candidate.isPresent() && !StringValueSupport.isBlank(candidate.get().getBaseContentRevisionId())) {
                return candidate.get().getBaseContentRevisionId();
            }
        }
        return null;
    }

    private Optional<EvolutionCandidate> findCandidateById(String candidateId) {
        return promotionWorkflowService.getCandidates().stream()
                .filter(candidate -> candidate != null && candidateId.equals(candidate.getId()))
                .findFirst();
    }

    private Optional<PromotionDecision> findDecisionById(String decisionId) {
        return promotionWorkflowService.getPromotionDecisions().stream()
                .filter(decision -> decision != null && decisionId.equals(decision.getId()))
                .findFirst();
    }

    private Optional<ArtifactBundleRecord> findBundleById(String bundleId) {
        if (StringValueSupport.isBlank(bundleId)) {
            return Optional.empty();
        }
        return artifactBundleService.getBundles().stream()
                .filter(bundle -> bundle != null && bundleId.equals(bundle.getId()))
                .findFirst();
    }
}
