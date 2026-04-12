package me.golemcore.bot.domain.selfevolving.tactic;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservedTacticMetricsCalculatorTest {

    private final ObservedTacticMetricsCalculator calculator = new ObservedTacticMetricsCalculator(
            Clock.fixed(Instant.parse("2026-04-05T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void shouldDeriveSuccessRateRecencyAndRegressionFlags() {
        TacticMetricsAggregator aggregator = new TacticMetricsAggregator();
        aggregator.noteObservation(Instant.parse("2026-04-05T11:00:00Z"));
        aggregator.noteRunOutcome("completed", Instant.parse("2026-04-05T10:00:00Z"));
        aggregator.noteRunOutcome("failed", Instant.parse("2026-04-05T11:00:00Z"));

        ObservedTacticMetrics metrics = calculator.calculate(aggregator);

        assertEquals(0.5d, metrics.successRate());
        assertEquals(1.0d, metrics.recencyScore());
        assertEquals(0.5d, metrics.golemLocalUsageSuccess());
        assertEquals(List.of("observed-high-failure-rate", "recent-failure"), metrics.regressionFlags());
    }

    @Test
    void shouldDeriveBenchmarkWinRateFromCampaignOutcomes() {
        TacticMetricsAggregator aggregator = new TacticMetricsAggregator();
        aggregator.noteCampaignOutcome(true);
        aggregator.noteCampaignOutcome(false);
        aggregator.noteCampaignOutcome(true);

        ObservedTacticMetrics metrics = calculator.calculate(aggregator);

        assertEquals(2.0d / 3.0d, metrics.benchmarkWinRate());
    }

    @Test
    void shouldReturnNullSignalsForEmptyAggregator() {
        ObservedTacticMetrics metrics = calculator.calculate(new TacticMetricsAggregator());

        assertNull(metrics.successRate());
        assertNull(metrics.recencyScore());
        assertNull(metrics.benchmarkWinRate());
        assertTrue(metrics.regressionFlags().isEmpty());
    }

    @Test
    void shouldReturnNullSignalsForNullAggregator() {
        ObservedTacticMetrics metrics = calculator.calculate(null);

        assertNull(metrics.successRate());
        assertNull(metrics.benchmarkWinRate());
        assertTrue(metrics.regressionFlags().isEmpty());
    }
}
