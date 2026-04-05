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
    private final BenchmarkWinRateCalculator benchmarkWinRateCalculator;
    private final Clock clock;
    private final java.util.concurrent.atomic.AtomicReference<EnrichCacheEntry> enrichCache = new java.util.concurrent.atomic.AtomicReference<>();

    public TacticQualityMetricsService(
            ArtifactBundleService artifactBundleService,
            SelfEvolvingRunService selfEvolvingRunService,
            TacticUsageAttributionService tacticUsageAttributionService,
            ObservedTacticMetricsCalculator observedTacticMetricsCalculator,
            BenchmarkLabService benchmarkLabService,
            BenchmarkWinRateCalculator benchmarkWinRateCalculator,
            Clock clock) {
        this.artifactBundleService = artifactBundleService;
        this.selfEvolvingRunService = selfEvolvingRunService;
        this.tacticUsageAttributionService = tacticUsageAttributionService;
        this.observedTacticMetricsCalculator = observedTacticMetricsCalculator;
        this.benchmarkLabService = benchmarkLabService;
        this.benchmarkWinRateCalculator = benchmarkWinRateCalculator;
        this.clock = clock;
    }

    public TacticRecord enrich(TacticRecord record) {
        if (record == null) {
            return null;
        }
        ObservedTacticMetrics metrics = resolveObservedMetrics(record);
        Double benchmarkWinRate = resolveBenchmarkWinRate(record);
        return applyMetrics(record, metrics, benchmarkWinRate);
    }

    /**
     * Batched enrichment that amortises bundle/run lookups across the whole list.
     * Prefer this over calling {@link #enrich(TacticRecord)} in a loop: the
     * single-record variant rescans bundles and runs per call, which is O(T × (B +
     * R)); this method is O(B + R + T).
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
        Map<String, TacticAggregator> aggregators = new LinkedHashMap<>();
        Map<String, List<String>> streamRevToTacticIds = new HashMap<>();
        for (TacticRecord record : records) {
            if (record == null || StringValueSupport.isBlank(record.getTacticId())) {
                continue;
            }
            // Intentionally do not seed from record.getUpdatedAt(): save/deactivate
            // bumps updatedAt and would otherwise artificially rejuvenate recency.
            // Recency is derived from observed bundles/runs only.
            aggregators.put(record.getTacticId(), new TacticAggregator(null));
            if (StringValueSupport.isBlank(record.getArtifactStreamId())
                    || StringValueSupport.isBlank(record.getContentRevisionId())) {
                continue;
            }
            String key = streamRevisionKey(record.getArtifactStreamId(), record.getContentRevisionId());
            streamRevToTacticIds.computeIfAbsent(key, k -> new ArrayList<>()).add(record.getTacticId());
        }

        List<ArtifactBundleRecord> bundles = artifactBundleService.getBundles();
        Map<String, List<String>> bundleIdToTacticIds = tacticUsageAttributionService
                .indexBundleToTacticIds(bundles, streamRevToTacticIds);
        for (ArtifactBundleRecord bundle : bundles) {
            if (bundle == null || StringValueSupport.isBlank(bundle.getId())) {
                continue;
            }
            List<String> matchedTacticIds = bundleIdToTacticIds.get(bundle.getId());
            if (matchedTacticIds == null || matchedTacticIds.isEmpty()) {
                continue;
            }
            for (String tacticId : matchedTacticIds) {
                TacticAggregator aggregator = aggregators.get(tacticId);
                aggregator.noteObservation(bundle.getActivatedAt(), bundle.getCreatedAt());
            }
        }

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
            for (String tacticId : attributedTacticIds) {
                TacticAggregator aggregator = aggregators.get(tacticId);
                aggregator.noteObservation(run.getCompletedAt(), run.getStartedAt(), verdictCreatedAt);
                if (observedOutcome != null) {
                    aggregator.noteOutcome(observedOutcome, mostRecent(null, run.getCompletedAt(), verdictCreatedAt));
                }
            }
        }

        Map<String, int[]> benchmarkWinObserved = computeBenchmarkWinObserved(bundles, streamRevToTacticIds,
                aggregators.keySet());

        List<TacticRecord> enriched = new ArrayList<>(records.size());
        for (TacticRecord record : records) {
            if (record == null || StringValueSupport.isBlank(record.getTacticId())) {
                enriched.add(record);
                continue;
            }
            TacticAggregator aggregator = aggregators.get(record.getTacticId());
            int[] winObserved = benchmarkWinObserved.get(record.getTacticId());
            Double benchmarkWinRate = winObserved != null
                    ? benchmarkWinRateCalculator.calculate(winObserved[0], winObserved[1])
                    : null;
            enriched.add(applyMetrics(record, toMetrics(aggregator), benchmarkWinRate));
        }
        enrichCache.set(new EnrichCacheEntry(signature, nowMs, new ArrayList<>(enriched)));
        return enriched;
    }

    /** Invalidate the enrichAll cache eagerly, e.g. after a tactic write. */
    public void invalidateCache() {
        enrichCache.set(null);
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

    private TacticRecord applyMetrics(TacticRecord record, ObservedTacticMetrics metrics, Double benchmarkWinRate) {
        TacticRecord enriched = copy(record);
        enriched.setSuccessRate(metrics.successRate());
        enriched.setBenchmarkWinRate(benchmarkWinRate);
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

    private Map<String, int[]> computeBenchmarkWinObserved(
            List<ArtifactBundleRecord> bundles,
            Map<String, List<String>> streamRevToTacticIds,
            Set<String> knownTacticIds) {
        Map<String, int[]> perTacticWinObserved = new HashMap<>();
        if (benchmarkLabService == null || knownTacticIds.isEmpty()) {
            return perTacticWinObserved;
        }
        List<BenchmarkCampaign> campaigns = benchmarkLabService.getCampaigns();
        if (campaigns == null || campaigns.isEmpty()) {
            return perTacticWinObserved;
        }
        Map<String, ArtifactBundleRecord> bundlesById = new HashMap<>();
        if (bundles != null) {
            for (ArtifactBundleRecord bundle : bundles) {
                if (bundle != null && !StringValueSupport.isBlank(bundle.getId())) {
                    bundlesById.put(bundle.getId(), bundle);
                }
            }
        }
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
            boolean won = benchmarkWinRateCalculator.isCandidateWin(verdict.get());
            for (String tacticId : campaignTacticIds) {
                if (!knownTacticIds.contains(tacticId)) {
                    continue;
                }
                int[] bucket = perTacticWinObserved.computeIfAbsent(tacticId, k -> new int[2]);
                if (won) {
                    bucket[0]++;
                }
                bucket[1]++;
            }
        }
        return perTacticWinObserved;
    }

    private Double resolveBenchmarkWinRate(TacticRecord record) {
        if (benchmarkLabService == null || record == null
                || StringValueSupport.isBlank(record.getTacticId())
                || StringValueSupport.isBlank(record.getArtifactStreamId())
                || StringValueSupport.isBlank(record.getContentRevisionId())) {
            return null;
        }
        Map<String, List<String>> streamRevToTacticIds = new HashMap<>();
        streamRevToTacticIds.put(
                streamRevisionKey(record.getArtifactStreamId(), record.getContentRevisionId()),
                List.of(record.getTacticId()));
        Map<String, int[]> perTacticWinObserved = computeBenchmarkWinObserved(
                artifactBundleService.getBundles(),
                streamRevToTacticIds,
                Set.of(record.getTacticId()));
        int[] bucket = perTacticWinObserved.get(record.getTacticId());
        if (bucket == null) {
            return null;
        }
        return benchmarkWinRateCalculator.calculate(bucket[0], bucket[1]);
    }

    private ObservedTacticMetrics resolveObservedMetrics(TacticRecord record) {
        // Intentionally do not seed from record.getUpdatedAt(): save/deactivate
        // bumps updatedAt and would otherwise artificially rejuvenate recency.
        Instant latestObservation = null;
        List<ArtifactBundleRecord> bundles = artifactBundleService.getBundles();
        Set<String> matchingBundleIds = tacticUsageAttributionService.resolveMatchingBundleIds(record, bundles);

        for (ArtifactBundleRecord bundle : bundles) {
            if (bundle == null || !matchingBundleIds.contains(bundle.getId())) {
                continue;
            }
            latestObservation = mostRecent(latestObservation, bundle.getActivatedAt(), bundle.getCreatedAt());
        }

        int observedRuns = 0;
        int successfulRuns = 0;
        Instant latestOutcomeAt = null;
        boolean latestOutcomeFailed = false;
        String recordTacticId = record != null ? record.getTacticId() : null;
        Map<String, List<String>> matchingBundleAttribution = new HashMap<>();
        if (recordTacticId != null) {
            for (String bundleId : matchingBundleIds) {
                matchingBundleAttribution.put(bundleId, List.of(recordTacticId));
            }
        }
        for (RunRecord run : selfEvolvingRunService.getRuns()) {
            if (run == null) {
                continue;
            }
            Set<String> attributedTacticIds = tacticUsageAttributionService.resolveAttributedTacticIds(
                    run,
                    matchingBundleAttribution,
                    recordTacticId != null ? Set.of(recordTacticId) : Set.of());
            if (!attributedTacticIds.contains(recordTacticId)) {
                continue;
            }
            Optional<RunVerdict> verdict = selfEvolvingRunService.findVerdict(run.getId());
            Instant verdictCreatedAt = verdict.map(RunVerdict::getCreatedAt).orElse(null);
            latestObservation = mostRecent(
                    latestObservation,
                    run.getCompletedAt(),
                    run.getStartedAt(),
                    verdictCreatedAt);
            String observedOutcome = observedTacticMetricsCalculator.resolveObservedOutcome(run, verdict.orElse(null));
            if (observedOutcome == null) {
                continue;
            }
            observedRuns++;
            if ("completed".equals(observedOutcome)) {
                successfulRuns++;
            }
            Instant outcomeAt = mostRecent(null, run.getCompletedAt(), verdictCreatedAt);
            if (outcomeAt != null && (latestOutcomeAt == null || outcomeAt.isAfter(latestOutcomeAt))) {
                latestOutcomeAt = outcomeAt;
                latestOutcomeFailed = "failed".equals(observedOutcome);
            }
        }

        return observedTacticMetricsCalculator.calculate(
                latestObservation,
                observedRuns,
                successfulRuns,
                latestOutcomeFailed);
    }

    private Instant mostRecent(Instant current, Instant... candidates) {
        Instant latest = current;
        if (candidates == null) {
            return latest;
        }
        for (Instant candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (latest == null || candidate.isAfter(latest)) {
                latest = candidate;
            }
        }
        return latest;
    }

    private ObservedTacticMetrics toMetrics(TacticAggregator aggregator) {
        if (aggregator == null) {
            return observedTacticMetricsCalculator.calculate(null, 0, 0, false);
        }
        return observedTacticMetricsCalculator.calculate(
                aggregator.latestObservation,
                aggregator.observedRuns,
                aggregator.successfulRuns,
                aggregator.latestOutcomeFailed);
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

    private final class TacticAggregator {
        private Instant latestObservation;
        private int observedRuns;
        private int successfulRuns;
        private Instant latestOutcomeAt;
        private boolean latestOutcomeFailed;

        private TacticAggregator(Instant seedObservation) {
            this.latestObservation = seedObservation;
        }

        private void noteObservation(Instant... candidates) {
            latestObservation = mostRecent(latestObservation, candidates);
        }

        private void noteOutcome(String observedOutcome, Instant outcomeAt) {
            observedRuns++;
            if ("completed".equals(observedOutcome)) {
                successfulRuns++;
            }
            if (outcomeAt != null && (latestOutcomeAt == null || outcomeAt.isAfter(latestOutcomeAt))) {
                latestOutcomeAt = outcomeAt;
                latestOutcomeFailed = "failed".equals(observedOutcome);
            }
        }
    }
}
