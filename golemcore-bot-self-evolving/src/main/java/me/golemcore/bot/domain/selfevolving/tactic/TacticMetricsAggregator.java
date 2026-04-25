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
public final class TacticMetricsAggregator {

    private Instant latestObservationAt;
    private int observedRunCount;
    private int successfulRunCount;
    private Instant latestOutcomeAt;
    private boolean latestOutcomeWasFailure;
    private int observedCampaignCount;
    private int wonCampaignCount;

    void noteObservation(Instant... candidates) {
        if (candidates == null) {
            return;
        }
        for (Instant candidate : candidates) {
            if (candidate != null && (latestObservationAt == null || candidate.isAfter(latestObservationAt))) {
                latestObservationAt = candidate;
            }
        }
    }

    void noteRunOutcome(String observedOutcome, Instant outcomeAt) {
        if (observedOutcome == null) {
            return;
        }
        observedRunCount++;
        if ("completed".equals(observedOutcome)) {
            successfulRunCount++;
        }
        if (outcomeAt != null && (latestOutcomeAt == null || outcomeAt.isAfter(latestOutcomeAt))) {
            latestOutcomeAt = outcomeAt;
            latestOutcomeWasFailure = "failed".equals(observedOutcome);
        }
    }

    void noteCampaignOutcome(boolean won) {
        observedCampaignCount++;
        if (won) {
            wonCampaignCount++;
        }
    }

    Instant latestObservation() {
        return latestObservationAt;
    }

    int observedRuns() {
        return observedRunCount;
    }

    int successfulRuns() {
        return successfulRunCount;
    }

    boolean latestOutcomeFailed() {
        return latestOutcomeWasFailure;
    }

    int observedCampaigns() {
        return observedCampaignCount;
    }

    int wonCampaigns() {
        return wonCampaignCount;
    }
}
