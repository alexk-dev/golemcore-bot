package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkSuite;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BenchmarkLabServiceTest {

    private StoragePort storagePort;
    private SelfEvolvingRunService selfEvolvingRunService;
    private PromotionWorkflowService promotionWorkflowService;
    private BenchmarkLabService benchmarkLabService;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        selfEvolvingRunService = mock(SelfEvolvingRunService.class);
        promotionWorkflowService = mock(PromotionWorkflowService.class);
        when(storagePort.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        benchmarkLabService = new BenchmarkLabService(
                storagePort,
                selfEvolvingRunService,
                promotionWorkflowService,
                Clock.fixed(Instant.parse("2026-03-31T16:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldCreateBenchmarkCampaignFromHarvestedRun() {
        when(selfEvolvingRunService.getRuns()).thenReturn(List.of(RunRecord.builder()
                .id("run-1")
                .golemId("golem-1")
                .artifactBundleId("bundle-1")
                .traceId("trace-1")
                .status("FAILED")
                .build()));

        BenchmarkCampaign campaign = benchmarkLabService.createRegressionCampaign("run-1");

        assertNotNull(campaign.getSuiteId());
        assertEquals(List.of("run-1"), campaign.getRunIds());
        assertFalse(benchmarkLabService.getSuites().isEmpty());
    }

    @Test
    void shouldPersistSuiteAndCampaignForHarvestedRun() {
        when(selfEvolvingRunService.getRuns()).thenReturn(List.of(RunRecord.builder()
                .id("run-2")
                .golemId("golem-2")
                .artifactBundleId("bundle-2")
                .traceId("trace-2")
                .status("COMPLETED")
                .build()));

        BenchmarkCampaign campaign = benchmarkLabService.createRegressionCampaign("run-2");
        List<BenchmarkSuite> suites = benchmarkLabService.getSuites();

        assertEquals(1, suites.size());
        assertEquals(campaign.getSuiteId(), suites.getFirst().getId());
        assertEquals(1, benchmarkLabService.getCampaigns().size());
    }

    @Test
    void shouldUseOriginBundleAsBaselineWhenPromotionDecisionExists() {
        when(selfEvolvingRunService.getRuns()).thenReturn(List.of(RunRecord.builder()
                .id("run-3")
                .golemId("golem-3")
                .artifactBundleId("candidate-1:shadowed")
                .traceId("trace-3")
                .status("COMPLETED")
                .build()));
        when(promotionWorkflowService.getPromotionDecisions()).thenReturn(List.of(PromotionDecision.builder()
                .id("decision-1")
                .bundleId("candidate-1:shadowed")
                .originBundleId("bundle-baseline")
                .build()));

        BenchmarkCampaign campaign = benchmarkLabService.createRegressionCampaign("run-3");

        assertEquals("bundle-baseline", campaign.getBaselineBundleId());
        assertEquals("candidate-1:shadowed", campaign.getCandidateBundleId());
    }
}
