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
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Derives user-facing tactic quality/runtime metrics from observed runtime
 * data.
 */
@Service
public class TacticQualityMetricsService {

    private static final int REGRESSION_MIN_OBSERVATIONS = 2;
    private static final double REGRESSION_FAILURE_RATE_THRESHOLD = 0.5d;
    private static final String FLAG_HIGH_FAILURE_RATE = "observed-high-failure-rate";
    private static final String FLAG_RECENT_FAILURE = "recent-failure";

    // Short-lived cache to amortise hot-path enrichAll calls from search.
    // Keyed on the caller's input signature; invalidated by TTL only, so
    // writes made within the TTL window observe slightly stale metrics.
    private static final long ENRICH_CACHE_TTL_MS = 2_000L;

    private final ArtifactBundleService artifactBundleService;
    private final SelfEvolvingRunService selfEvolvingRunService;
    private final Clock clock;
    private final java.util.concurrent.atomic.AtomicReference<EnrichCacheEntry> enrichCache = new java.util.concurrent.atomic.AtomicReference<>();

    public TacticQualityMetricsService(
            ArtifactBundleService artifactBundleService,
            SelfEvolvingRunService selfEvolvingRunService,
            Clock clock) {
        this.artifactBundleService = artifactBundleService;
        this.selfEvolvingRunService = selfEvolvingRunService;
        this.clock = clock;
    }

    public TacticRecord enrich(TacticRecord record) {
        if (record == null) {
            return null;
        }
        ObservedMetrics metrics = resolveObservedMetrics(record);
        return applyMetrics(record, metrics);
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

        Map<String, List<String>> bundleIdToTacticIds = new HashMap<>();
        for (ArtifactBundleRecord bundle : artifactBundleService.getBundles()) {
            if (bundle == null || StringValueSupport.isBlank(bundle.getId())) {
                continue;
            }
            Map<String, String> bindings = bundle.getArtifactRevisionBindings();
            if (bindings == null || bindings.isEmpty()) {
                continue;
            }
            List<String> matchedTacticIds = new ArrayList<>();
            for (Map.Entry<String, String> binding : bindings.entrySet()) {
                List<String> tacticIds = streamRevToTacticIds
                        .get(streamRevisionKey(binding.getKey(), binding.getValue()));
                if (tacticIds != null) {
                    matchedTacticIds.addAll(tacticIds);
                }
            }
            if (matchedTacticIds.isEmpty()) {
                continue;
            }
            bundleIdToTacticIds.put(bundle.getId(), matchedTacticIds);
            for (String tacticId : matchedTacticIds) {
                TacticAggregator aggregator = aggregators.get(tacticId);
                aggregator.latestObservation = mostRecent(aggregator.latestObservation,
                        bundle.getActivatedAt(), bundle.getCreatedAt());
            }
        }

        for (RunRecord run : selfEvolvingRunService.getRuns()) {
            if (run == null) {
                continue;
            }
            List<String> tacticIds = bundleIdToTacticIds.get(run.getArtifactBundleId());
            if (tacticIds == null) {
                continue;
            }
            Optional<RunVerdict> verdict = selfEvolvingRunService.findVerdict(run.getId());
            Instant verdictCreatedAt = verdict.map(RunVerdict::getCreatedAt).orElse(null);
            String observedOutcome = resolveObservedOutcome(run, verdict.orElse(null));
            for (String tacticId : tacticIds) {
                TacticAggregator aggregator = aggregators.get(tacticId);
                aggregator.latestObservation = mostRecent(aggregator.latestObservation,
                        run.getCompletedAt(), run.getStartedAt(), verdictCreatedAt);
                if (observedOutcome == null) {
                    continue;
                }
                aggregator.observedRuns++;
                if ("completed".equals(observedOutcome)) {
                    aggregator.successfulRuns++;
                }
                Instant outcomeAt = mostRecent(null, run.getCompletedAt(), verdictCreatedAt);
                if (outcomeAt != null
                        && (aggregator.latestOutcomeAt == null || outcomeAt.isAfter(aggregator.latestOutcomeAt))) {
                    aggregator.latestOutcomeAt = outcomeAt;
                    aggregator.latestOutcomeFailed = "failed".equals(observedOutcome);
                }
            }
        }

        List<TacticRecord> enriched = new ArrayList<>(records.size());
        for (TacticRecord record : records) {
            if (record == null || StringValueSupport.isBlank(record.getTacticId())) {
                enriched.add(record);
                continue;
            }
            TacticAggregator aggregator = aggregators.get(record.getTacticId());
            enriched.add(applyMetrics(record, aggregator.toMetrics(recencyScore(aggregator.latestObservation))));
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

    private TacticRecord applyMetrics(TacticRecord record, ObservedMetrics metrics) {
        TacticRecord enriched = copy(record);
        enriched.setSuccessRate(metrics.successRate());
        enriched.setBenchmarkWinRate(record.getBenchmarkWinRate());
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

    private List<String> deriveRegressionFlags(int observedRuns, int successfulRuns, boolean lastOutcomeFailed) {
        List<String> flags = new ArrayList<>();
        if (observedRuns >= REGRESSION_MIN_OBSERVATIONS) {
            double failureRate = (observedRuns - successfulRuns) / (double) observedRuns;
            if (failureRate >= REGRESSION_FAILURE_RATE_THRESHOLD) {
                flags.add(FLAG_HIGH_FAILURE_RATE);
            }
        }
        if (lastOutcomeFailed) {
            flags.add(FLAG_RECENT_FAILURE);
        }
        return flags;
    }

    private ObservedMetrics resolveObservedMetrics(TacticRecord record) {
        // Intentionally do not seed from record.getUpdatedAt(): save/deactivate
        // bumps updatedAt and would otherwise artificially rejuvenate recency.
        Instant latestObservation = null;
        Set<String> matchingBundleIds = resolveMatchingBundleIds(record);

        for (ArtifactBundleRecord bundle : artifactBundleService.getBundles()) {
            if (bundle == null || !matchingBundleIds.contains(bundle.getId())) {
                continue;
            }
            latestObservation = mostRecent(latestObservation, bundle.getActivatedAt(), bundle.getCreatedAt());
        }

        int observedRuns = 0;
        int successfulRuns = 0;
        Instant latestOutcomeAt = null;
        boolean latestOutcomeFailed = false;
        for (RunRecord run : selfEvolvingRunService.getRuns()) {
            if (run == null || !matchingBundleIds.contains(run.getArtifactBundleId())) {
                continue;
            }
            Optional<RunVerdict> verdict = selfEvolvingRunService.findVerdict(run.getId());
            latestObservation = mostRecent(
                    latestObservation,
                    run.getCompletedAt(),
                    run.getStartedAt(),
                    verdict.map(RunVerdict::getCreatedAt).orElse(null));
            String observedOutcome = resolveObservedOutcome(run, verdict.orElse(null));
            if (observedOutcome == null) {
                continue;
            }
            observedRuns++;
            if ("completed".equals(observedOutcome)) {
                successfulRuns++;
            }
            Instant outcomeAt = mostRecent(null, run.getCompletedAt(),
                    verdict.map(RunVerdict::getCreatedAt).orElse(null));
            if (outcomeAt != null && (latestOutcomeAt == null || outcomeAt.isAfter(latestOutcomeAt))) {
                latestOutcomeAt = outcomeAt;
                latestOutcomeFailed = "failed".equals(observedOutcome);
            }
        }

        Double successRate = observedRuns > 0 ? successfulRuns / (double) observedRuns : null;
        List<String> regressionFlags = deriveRegressionFlags(observedRuns, successfulRuns, latestOutcomeFailed);
        return new ObservedMetrics(successRate, recencyScore(latestObservation), successRate, regressionFlags);
    }

    private Set<String> resolveMatchingBundleIds(TacticRecord record) {
        Set<String> bundleIds = new LinkedHashSet<>();
        if (record == null
                || StringValueSupport.isBlank(record.getArtifactStreamId())
                || StringValueSupport.isBlank(record.getContentRevisionId())) {
            return bundleIds;
        }
        for (ArtifactBundleRecord bundle : artifactBundleService.getBundles()) {
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

    private String resolveObservedOutcome(RunRecord run, RunVerdict verdict) {
        String verdictOutcome = normalizeOutcome(verdict != null ? verdict.getOutcomeStatus() : null);
        if (isTerminalOutcome(verdictOutcome)) {
            return verdictOutcome;
        }
        String runOutcome = normalizeOutcome(run != null ? run.getStatus() : null);
        return isTerminalOutcome(runOutcome) ? runOutcome : null;
    }

    private boolean isTerminalOutcome(String outcome) {
        return "completed".equals(outcome) || "failed".equals(outcome);
    }

    private String normalizeOutcome(String outcome) {
        return outcome == null ? null : outcome.trim().toLowerCase(Locale.ROOT);
    }

    private Double recencyScore(Instant timestamp) {
        if (timestamp == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(timestamp, clock.instant());
        // Negative days (timestamp in the future relative to the clock) are
        // treated as maximally recent rather than over-saturating the score.
        if (days <= 0) {
            return 1.0d;
        }
        if (days >= 30) {
            return 0.0d;
        }
        return clamp(1.0d - (days / 30.0d));
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
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

    private record ObservedMetrics(
            Double successRate,
            Double recencyScore,
            Double golemLocalUsageSuccess,
            List<String> regressionFlags) {
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

        private ObservedMetrics toMetrics(Double recencyScore) {
            Double successRate = observedRuns > 0 ? successfulRuns / (double) observedRuns : null;
            List<String> flags = deriveRegressionFlags(observedRuns, successfulRuns, latestOutcomeFailed);
            return new ObservedMetrics(successRate, recencyScore, successRate, flags);
        }
    }
}
