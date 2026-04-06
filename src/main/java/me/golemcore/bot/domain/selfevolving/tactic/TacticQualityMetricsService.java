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
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaignVerdict;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import me.golemcore.bot.domain.selfevolving.artifact.ArtifactBundleService;
import me.golemcore.bot.domain.selfevolving.benchmark.BenchmarkLabService;
import me.golemcore.bot.domain.selfevolving.benchmark.BenchmarkWinRateCalculator;
import me.golemcore.bot.domain.selfevolving.run.SelfEvolvingRunService;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Derives user-facing tactic quality/runtime metrics from observed runtime
 * data.
 */
@Service
public class TacticQualityMetricsService {

    // Short-lived cache to amortise hot-path enrichAll calls from search.
    // Keyed on the caller's input signature; invalidated by TTL only, so
    // writes made within the TTL window observe slightly stale metrics.
    private static final long ENRICH_CACHE_TTL_MS = 2_000L;

    private final ArtifactBundleService artifactBundleService;
    private final SelfEvolvingRunService selfEvolvingRunService;
    private final TacticUsageAttributionService tacticUsageAttributionService;
    private final ObservedTacticMetricsCalculator observedTacticMetricsCalculator;
    private final BenchmarkLabService benchmarkLabService;
    private final Clock clock;
    private final AtomicReference<EnrichCacheEntry> enrichCache = new AtomicReference<>();

    public TacticQualityMetricsService(
            ArtifactBundleService artifactBundleService,
            SelfEvolvingRunService selfEvolvingRunService,
            TacticUsageAttributionService tacticUsageAttributionService,
            ObservedTacticMetricsCalculator observedTacticMetricsCalculator,
            BenchmarkLabService benchmarkLabService,
            Clock clock) {
        this.artifactBundleService = artifactBundleService;
        this.selfEvolvingRunService = selfEvolvingRunService;
        this.tacticUsageAttributionService = tacticUsageAttributionService;
        this.observedTacticMetricsCalculator = observedTacticMetricsCalculator;
        this.benchmarkLabService = benchmarkLabService;
        this.clock = clock;
    }

    public TacticRecord enrich(TacticRecord record) {
        if (record == null) {
            return null;
        }
        return enrichAll(List.of(record)).getFirst();
    }

    /**
     * Batched enrichment that amortises bundle/run lookups across the whole list.
     * Runs three attribution passes — bundles, runs, benchmark campaigns — into
     * per-tactic {@link TacticMetricsAggregator}s, then projects each aggregator
     * through {@link ObservedTacticMetricsCalculator} into the user-facing
     * {@link ObservedTacticMetrics}. Complexity is O(B + R + C + T).
     */
    public List<TacticRecord> enrichAll(List<TacticRecord> records) {
        if (records == null || records.isEmpty()) {
            return records;
        }
        String signature = buildCacheSignature(records);
        long nowMs = clock.millis();
        EnrichCacheEntry cachedEntry = enrichCache.get();
        if (cachedEntry != null
                && cachedEntry.signature.equals(signature)
                && (nowMs - cachedEntry.writtenAtMs) < ENRICH_CACHE_TTL_MS) {
            return new ArrayList<>(cachedEntry.records);
        }

        Map<String, TacticMetricsAggregator> aggregators = new LinkedHashMap<>();
        Map<String, List<String>> streamRevToTacticIds = new HashMap<>();
        seedAggregators(records, aggregators, streamRevToTacticIds);

        List<ArtifactBundleRecord> bundles = artifactBundleService.getBundles();
        Map<String, List<String>> bundleIdToTacticIds = tacticUsageAttributionService
                .indexBundleToTacticIds(bundles, streamRevToTacticIds);

        attributeBundles(aggregators, bundles, bundleIdToTacticIds);
        attributeRuns(aggregators, bundleIdToTacticIds);
        attributeCampaigns(aggregators, bundles, streamRevToTacticIds);

        List<TacticRecord> enriched = applyMetricsToRecords(records, aggregators);
        enrichCache.set(new EnrichCacheEntry(signature, nowMs, new ArrayList<>(enriched)));
        return enriched;
    }

    /** Invalidate the enrichAll cache eagerly, e.g. after a tactic write. */
    public void invalidateCache() {
        enrichCache.set(null);
    }

    private void seedAggregators(
            List<TacticRecord> records,
            Map<String, TacticMetricsAggregator> aggregators,
            Map<String, List<String>> streamRevToTacticIds) {
        for (TacticRecord record : records) {
            if (record == null || StringValueSupport.isBlank(record.getTacticId())) {
                continue;
            }
            // Intentionally do not seed from record.getUpdatedAt(): save/deactivate
            // bumps updatedAt and would otherwise artificially rejuvenate recency.
            // Recency is derived from observed bundles/runs only.
            aggregators.put(record.getTacticId(), new TacticMetricsAggregator());
            if (StringValueSupport.isBlank(record.getArtifactStreamId())
                    || StringValueSupport.isBlank(record.getContentRevisionId())) {
                continue;
            }
            String key = streamRevisionKey(record.getArtifactStreamId(), record.getContentRevisionId());
            streamRevToTacticIds.computeIfAbsent(key, k -> new ArrayList<>()).add(record.getTacticId());
        }
    }

    private void attributeBundles(
            Map<String, TacticMetricsAggregator> aggregators,
            List<ArtifactBundleRecord> bundles,
            Map<String, List<String>> bundleIdToTacticIds) {
        for (ArtifactBundleRecord bundle : bundles) {
            if (bundle == null || StringValueSupport.isBlank(bundle.getId())) {
                continue;
            }
            List<String> matchedTacticIds = bundleIdToTacticIds.get(bundle.getId());
            if (matchedTacticIds == null || matchedTacticIds.isEmpty()) {
                continue;
            }
            for (String tacticId : matchedTacticIds) {
                aggregators.get(tacticId).noteObservation(bundle.getActivatedAt(), bundle.getCreatedAt());
            }
        }
    }

    private void attributeRuns(
            Map<String, TacticMetricsAggregator> aggregators,
            Map<String, List<String>> bundleIdToTacticIds) {
        for (RunRecord run : selfEvolvingRunService.getRuns()) {
            if (run == null) {
                continue;
            }
            Set<String> attributedTacticIds = tacticUsageAttributionService
                    .resolveAttributedTacticIds(run, bundleIdToTacticIds, aggregators.keySet());
            if (attributedTacticIds.isEmpty()) {
                continue;
            }
            Optional<RunVerdict> verdict = selfEvolvingRunService.findVerdict(run.getId());
            Instant verdictCreatedAt = verdict.map(RunVerdict::getCreatedAt).orElse(null);
            String observedOutcome = observedTacticMetricsCalculator.resolveObservedOutcome(run, verdict.orElse(null));
            Instant outcomeAt = mostRecentOf(run.getCompletedAt(), verdictCreatedAt);
            for (String tacticId : attributedTacticIds) {
                TacticMetricsAggregator aggregator = aggregators.get(tacticId);
                aggregator.noteObservation(run.getCompletedAt(), run.getStartedAt(), verdictCreatedAt);
                aggregator.noteRunOutcome(observedOutcome, outcomeAt);
            }
        }
    }

    private void attributeCampaigns(
            Map<String, TacticMetricsAggregator> aggregators,
            List<ArtifactBundleRecord> bundles,
            Map<String, List<String>> streamRevToTacticIds) {
        if (benchmarkLabService == null) {
            return;
        }
        List<BenchmarkCampaign> campaigns = benchmarkLabService.getCampaigns();
        if (campaigns == null || campaigns.isEmpty()) {
            return;
        }
        Map<String, ArtifactBundleRecord> bundlesById = indexBundlesById(bundles);
        for (BenchmarkCampaign campaign : campaigns) {
            if (campaign == null) {
                continue;
            }
            Optional<BenchmarkCampaignVerdict> verdict = benchmarkLabService
                    .findVerdictByCampaignId(campaign.getId());
            if (verdict.isEmpty()) {
                continue;
            }
            Set<String> campaignTacticIds = tacticUsageAttributionService
                    .resolveCampaignTacticIds(campaign, bundlesById, streamRevToTacticIds);
            if (campaignTacticIds.isEmpty()) {
                continue;
            }
            boolean won = BenchmarkWinRateCalculator.isCandidateWin(verdict.get());
            for (String tacticId : campaignTacticIds) {
                TacticMetricsAggregator aggregator = aggregators.get(tacticId);
                if (aggregator != null) {
                    aggregator.noteCampaignOutcome(won);
                }
            }
        }
    }

    private List<TacticRecord> applyMetricsToRecords(
            List<TacticRecord> records,
            Map<String, TacticMetricsAggregator> aggregators) {
        List<TacticRecord> enriched = new ArrayList<>(records.size());
        for (TacticRecord record : records) {
            if (record == null || StringValueSupport.isBlank(record.getTacticId())) {
                enriched.add(record);
                continue;
            }
            ObservedTacticMetrics metrics = observedTacticMetricsCalculator
                    .calculate(aggregators.get(record.getTacticId()));
            enriched.add(applyMetrics(record, metrics));
        }
        return enriched;
    }

    private Map<String, ArtifactBundleRecord> indexBundlesById(List<ArtifactBundleRecord> bundles) {
        Map<String, ArtifactBundleRecord> bundlesById = new HashMap<>();
        if (bundles == null) {
            return bundlesById;
        }
        for (ArtifactBundleRecord bundle : bundles) {
            if (bundle != null && !StringValueSupport.isBlank(bundle.getId())) {
                bundlesById.put(bundle.getId(), bundle);
            }
        }
        return bundlesById;
    }

    private Instant mostRecentOf(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    private String buildCacheSignature(List<TacticRecord> records) {
        StringBuilder builder = new StringBuilder();
        for (TacticRecord record : records) {
            if (record == null) {
                builder.append("0|");
                continue;
            }
            builder.append(StringValueSupport.nullSafe(record.getTacticId()))
                    .append(':')
                    .append(StringValueSupport.nullSafe(record.getContentRevisionId()))
                    .append(':')
                    .append(record.getUpdatedAt() != null ? record.getUpdatedAt().toEpochMilli() : 0L)
                    .append('|');
        }
        return builder.toString();
    }

    private String streamRevisionKey(String streamId, String revisionId) {
        return streamId + "\u0000" + revisionId;
    }

    private TacticRecord applyMetrics(TacticRecord record, ObservedTacticMetrics metrics) {
        TacticRecord enriched = copy(record);
        enriched.setSuccessRate(metrics.successRate());
        enriched.setBenchmarkWinRate(metrics.benchmarkWinRate());
        enriched.setRecencyScore(metrics.recencyScore());
        // NOTE: golemLocalUsageSuccess currently mirrors successRate because
        // every observed run is assumed to originate from a local golem. When
        // remote/fleet runs land, this metric should be computed from a
        // filtered subset (see RunRecord.runType).
        enriched.setGolemLocalUsageSuccess(metrics.golemLocalUsageSuccess());
        if (metrics.regressionFlags() != null) {
            enriched.setRegressionFlags(new ArrayList<>(metrics.regressionFlags()));
        }
        return enriched;
    }

    private TacticRecord copy(TacticRecord record) {
        return TacticRecord.builder()
                .tacticId(record.getTacticId())
                .artifactStreamId(record.getArtifactStreamId())
                .originArtifactStreamId(record.getOriginArtifactStreamId())
                .artifactKey(record.getArtifactKey())
                .artifactType(record.getArtifactType())
                .title(record.getTitle())
                .aliases(record.getAliases() != null ? new ArrayList<>(record.getAliases()) : new ArrayList<>())
                .contentRevisionId(record.getContentRevisionId())
                .intentSummary(record.getIntentSummary())
                .behaviorSummary(record.getBehaviorSummary())
                .toolSummary(record.getToolSummary())
                .outcomeSummary(record.getOutcomeSummary())
                .benchmarkSummary(record.getBenchmarkSummary())
                .approvalNotes(record.getApprovalNotes())
                .evidenceSnippets(record.getEvidenceSnippets() != null ? new ArrayList<>(record.getEvidenceSnippets())
                        : new ArrayList<>())
                .taskFamilies(record.getTaskFamilies() != null ? new ArrayList<>(record.getTaskFamilies())
                        : new ArrayList<>())
                .tags(record.getTags() != null ? new ArrayList<>(record.getTags()) : new ArrayList<>())
                .promotionState(record.getPromotionState())
                .rolloutStage(record.getRolloutStage())
                .successRate(record.getSuccessRate())
                .benchmarkWinRate(record.getBenchmarkWinRate())
                .regressionFlags(record.getRegressionFlags() != null ? new ArrayList<>(record.getRegressionFlags())
                        : new ArrayList<>())
                .recencyScore(record.getRecencyScore())
                .golemLocalUsageSuccess(record.getGolemLocalUsageSuccess())
                .embeddingStatus(record.getEmbeddingStatus())
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    private static final class EnrichCacheEntry {
        private final String signature;
        private final long writtenAtMs;
        private final List<TacticRecord> records;

        private EnrichCacheEntry(String signature, long writtenAtMs, List<TacticRecord> records) {
            this.signature = signature;
            this.writtenAtMs = writtenAtMs;
            this.records = records;
        }
    }
}
