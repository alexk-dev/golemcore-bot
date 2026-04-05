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

import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Converts observed runtime signals into user-facing tactic quality metrics.
 */
@Service
public class ObservedTacticMetricsCalculator {

    private static final int REGRESSION_MIN_OBSERVATIONS = 2;
    private static final double REGRESSION_FAILURE_RATE_THRESHOLD = 0.5d;
    private static final String FLAG_HIGH_FAILURE_RATE = "observed-high-failure-rate";
    private static final String FLAG_RECENT_FAILURE = "recent-failure";

    private final Clock clock;

    public ObservedTacticMetricsCalculator(Clock clock) {
        this.clock = clock;
    }

    public ObservedTacticMetrics calculate(
            Instant latestObservation,
            int observedRuns,
            int successfulRuns,
            boolean lastOutcomeFailed) {
        Double successRate = observedRuns > 0 ? successfulRuns / (double) observedRuns : null;
        List<String> regressionFlags = deriveRegressionFlags(observedRuns, successfulRuns, lastOutcomeFailed);
        return new ObservedTacticMetrics(successRate, recencyScore(latestObservation), successRate, regressionFlags);
    }

    public String resolveObservedOutcome(RunRecord run, RunVerdict verdict) {
        String verdictOutcome = normalizeOutcome(verdict != null ? verdict.getOutcomeStatus() : null);
        if (isTerminalOutcome(verdictOutcome)) {
            return verdictOutcome;
        }
        String runOutcome = normalizeOutcome(run != null ? run.getStatus() : null);
        return isTerminalOutcome(runOutcome) ? runOutcome : null;
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
}
