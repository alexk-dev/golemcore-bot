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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Derives user-facing tactic quality/runtime metrics from observed runtime
 * data.
 */
@Service
public class TacticQualityMetricsService {

    private final ArtifactBundleService artifactBundleService;
    private final SelfEvolvingRunService selfEvolvingRunService;
    private final Clock clock;

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
        TacticRecord enriched = copy(record);
        enriched.setSuccessRate(metrics.successRate());
        enriched.setBenchmarkWinRate(record.getBenchmarkWinRate());
        enriched.setRecencyScore(metrics.recencyScore());
        enriched.setGolemLocalUsageSuccess(metrics.golemLocalUsageSuccess());
        return enriched;
    }

    private ObservedMetrics resolveObservedMetrics(TacticRecord record) {
        Instant latestObservation = record.getUpdatedAt();
        Set<String> matchingBundleIds = resolveMatchingBundleIds(record);

        for (ArtifactBundleRecord bundle : artifactBundleService.getBundles()) {
            if (bundle == null || !matchingBundleIds.contains(bundle.getId())) {
                continue;
            }
            latestObservation = mostRecent(latestObservation, bundle.getActivatedAt(), bundle.getCreatedAt());
        }

        int observedRuns = 0;
        int successfulRuns = 0;
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
        }

        Double successRate = observedRuns > 0 ? successfulRuns / (double) observedRuns : null;
        return new ObservedMetrics(successRate, recencyScore(latestObservation), successRate);
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
        return outcome == null ? null : outcome.trim().toLowerCase();
    }

    private Double recencyScore(Instant timestamp) {
        if (timestamp == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(timestamp, Instant.now(clock));
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

    private record ObservedMetrics(Double successRate, Double recencyScore, Double golemLocalUsageSuccess) {
    }
}
