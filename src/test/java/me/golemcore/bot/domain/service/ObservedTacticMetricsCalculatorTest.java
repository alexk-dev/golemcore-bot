package me.golemcore.bot.domain.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObservedTacticMetricsCalculatorTest {

    @Test
    void shouldDeriveSuccessRateRecencyAndRegressionFlags() {
        ObservedTacticMetricsCalculator calculator = new ObservedTacticMetricsCalculator(
                Clock.fixed(Instant.parse("2026-04-05T12:00:00Z"), ZoneOffset.UTC));

        ObservedTacticMetrics metrics = calculator.calculate(
                Instant.parse("2026-04-05T11:00:00Z"),
                2,
                1,
                true);

        assertEquals(0.5d, metrics.successRate());
        assertEquals(1.0d, metrics.recencyScore());
        assertEquals(0.5d, metrics.golemLocalUsageSuccess());
        assertEquals(List.of("observed-high-failure-rate", "recent-failure"), metrics.regressionFlags());
    }
}
