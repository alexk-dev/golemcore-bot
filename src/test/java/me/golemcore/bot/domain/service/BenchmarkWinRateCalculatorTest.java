package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaignVerdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkWinRateCalculatorTest {

    private final BenchmarkWinRateCalculator calculator = new BenchmarkWinRateCalculator();

    @Test
    void shouldReturnNullWhenNoCampaignsObserved() {
        assertNull(calculator.calculate(0, 0));
    }

    @Test
    void shouldDivideWinsByObservations() {
        assertEquals(0.5d, calculator.calculate(1, 2));
        assertEquals(1.0d, calculator.calculate(3, 3));
        assertEquals(0.0d, calculator.calculate(0, 4));
    }

    @Test
    void shouldTreatPositiveQualityDeltaAsWin() {
        assertTrue(calculator.isCandidateWin(BenchmarkCampaignVerdict.builder()
                .qualityDelta(0.01d)
                .recommendation("reject")
                .build()));
    }

    @Test
    void shouldTreatPromoteLikeRecommendationsAsWin() {
        assertTrue(calculator.isCandidateWin(BenchmarkCampaignVerdict.builder().recommendation("promote").build()));
        assertTrue(calculator.isCandidateWin(BenchmarkCampaignVerdict.builder().recommendation("Ship").build()));
        assertTrue(calculator.isCandidateWin(BenchmarkCampaignVerdict.builder().recommendation("ACCEPT").build()));
    }

    @Test
    void shouldTreatOtherRecommendationsAsLoss() {
        assertFalse(calculator.isCandidateWin(BenchmarkCampaignVerdict.builder().recommendation("reject").build()));
        assertFalse(calculator.isCandidateWin(BenchmarkCampaignVerdict.builder().recommendation("hold").build()));
        assertFalse(calculator.isCandidateWin(BenchmarkCampaignVerdict.builder().build()));
        assertFalse(calculator.isCandidateWin(null));
    }

    @Test
    void shouldNotTreatNonPositiveQualityDeltaAsWin() {
        assertFalse(calculator.isCandidateWin(BenchmarkCampaignVerdict.builder().qualityDelta(0.0d).build()));
        assertFalse(calculator.isCandidateWin(BenchmarkCampaignVerdict.builder().qualityDelta(-0.1d).build()));
    }
}
