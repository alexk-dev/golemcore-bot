package me.golemcore.bot.domain.selfevolving.benchmark;

import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaignVerdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkWinRateCalculatorTest {

    @Test
    void shouldTreatPositiveQualityDeltaAsWin() {
        assertTrue(BenchmarkWinRateCalculator.isCandidateWin(BenchmarkCampaignVerdict.builder()
                .qualityDelta(0.01d)
                .recommendation("reject")
                .build()));
    }

    @Test
    void shouldTreatPromoteLikeRecommendationsAsWin() {
        assertTrue(BenchmarkWinRateCalculator
                .isCandidateWin(BenchmarkCampaignVerdict.builder().recommendation("promote").build()));
        assertTrue(BenchmarkWinRateCalculator
                .isCandidateWin(BenchmarkCampaignVerdict.builder().recommendation("Ship").build()));
        assertTrue(BenchmarkWinRateCalculator
                .isCandidateWin(BenchmarkCampaignVerdict.builder().recommendation("ACCEPT").build()));
    }

    @Test
    void shouldTreatOtherRecommendationsAsLoss() {
        assertFalse(BenchmarkWinRateCalculator
                .isCandidateWin(BenchmarkCampaignVerdict.builder().recommendation("reject").build()));
        assertFalse(BenchmarkWinRateCalculator
                .isCandidateWin(BenchmarkCampaignVerdict.builder().recommendation("hold").build()));
        assertFalse(BenchmarkWinRateCalculator.isCandidateWin(BenchmarkCampaignVerdict.builder().build()));
        assertFalse(BenchmarkWinRateCalculator.isCandidateWin(null));
    }

    @Test
    void shouldNotTreatNonPositiveQualityDeltaAsWin() {
        assertFalse(BenchmarkWinRateCalculator
                .isCandidateWin(BenchmarkCampaignVerdict.builder().qualityDelta(0.0d).build()));
        assertFalse(BenchmarkWinRateCalculator
                .isCandidateWin(BenchmarkCampaignVerdict.builder().qualityDelta(-0.1d).build()));
    }
}
