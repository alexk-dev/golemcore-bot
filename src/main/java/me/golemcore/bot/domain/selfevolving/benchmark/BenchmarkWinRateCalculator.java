package me.golemcore.bot.domain.selfevolving.benchmark;

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

import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaignVerdict;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Encapsulates the "what counts as a candidate win" decision for benchmark
 * campaign verdicts.
 *
 * <p>
 * Arithmetic aggregation lives on {@link TacticMetricsAggregator} — keeping the
 * decision isolated here means richer verdict signals (confidence thresholds,
 * multi-metric deltas) only touch this class.
 */
@Service
public class BenchmarkWinRateCalculator {

    /**
     * A verdict is a candidate win when the campaign recommends promoting the
     * candidate OR when the measured quality delta is strictly positive. Null
     * verdicts never count as wins.
     */
    public boolean isCandidateWin(BenchmarkCampaignVerdict verdict) {
        if (verdict == null) {
            return false;
        }
        if (verdict.getQualityDelta() != null && verdict.getQualityDelta() > 0.0d) {
            return true;
        }
        String recommendation = verdict.getRecommendation();
        if (recommendation == null) {
            return false;
        }
        String normalized = recommendation.trim().toLowerCase(Locale.ROOT);
        return "promote".equals(normalized) || "ship".equals(normalized) || "accept".equals(normalized);
    }
}
