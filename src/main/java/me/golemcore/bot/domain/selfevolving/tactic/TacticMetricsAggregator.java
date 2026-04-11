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

import java.time.Instant;

/**
 * Per-tactic accumulator of observed runtime signals — bundle activations, run
 * outcomes, and benchmark campaign verdicts all feed into the same instance.
 *
 * <p>
 * Holding every observation dimension on a single aggregator keeps the metrics
 * pipeline honest: new signals become fields here, not parallel maps.
 */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class TacticMetricsAggregator {

    private Instant latestObservation;
    private int observedRuns;
    private int successfulRuns;
    private Instant latestOutcomeAt;
    private boolean latestOutcomeFailed;
    private int observedCampaigns;
    private int wonCampaigns;

    void noteObservation(Instant... candidates) {
        if (candidates == null) {
            return;
        }
        for (Instant candidate : candidates) {
            if (candidate != null && (latestObservation == null || candidate.isAfter(latestObservation))) {
                latestObservation = candidate;
            }
        }
    }

    void noteRunOutcome(String observedOutcome, Instant outcomeAt) {
        if (observedOutcome == null) {
            return;
        }
        observedRuns++;
        if ("completed".equals(observedOutcome)) {
            successfulRuns++;
        }
        if (outcomeAt != null && (latestOutcomeAt == null || outcomeAt.isAfter(latestOutcomeAt))) {
            latestOutcomeAt = outcomeAt;
            latestOutcomeFailed = "failed".equals(observedOutcome);
        }
    }

    void noteCampaignOutcome(boolean won) {
        observedCampaigns++;
        if (won) {
            wonCampaigns++;
        }
    }

    Instant latestObservation() {
        return latestObservation;
    }

    int observedRuns() {
        return observedRuns;
    }

    int successfulRuns() {
        return successfulRuns;
    }

    boolean latestOutcomeFailed() {
        return latestOutcomeFailed;
    }

    int observedCampaigns() {
        return observedCampaigns;
    }

    int wonCampaigns() {
        return wonCampaigns;
    }
}
