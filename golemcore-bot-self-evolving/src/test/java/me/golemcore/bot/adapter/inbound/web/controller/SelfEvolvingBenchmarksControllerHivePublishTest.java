package me.golemcore.bot.adapter.inbound.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import me.golemcore.bot.adapter.inbound.web.projection.SelfEvolvingProjectionService;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.selfevolving.benchmark.BenchmarkLabService;
import me.golemcore.bot.port.outbound.SelfEvolvingProjectionPublishPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

class SelfEvolvingBenchmarksControllerHivePublishTest {

    private SelfEvolvingProjectionService projectionService;
    private BenchmarkLabService benchmarkLabService;
    private SelfEvolvingProjectionPublishPort projectionPublishPort;
    private SelfEvolvingBenchmarksController controller;

    @BeforeEach
    void setUp() {
        projectionService = mock(SelfEvolvingProjectionService.class);
        benchmarkLabService = mock(BenchmarkLabService.class);
        projectionPublishPort = mock(SelfEvolvingProjectionPublishPort.class);
        controller = new SelfEvolvingBenchmarksController(projectionService, benchmarkLabService,
                projectionPublishPort);
    }

    @Test
    void shouldPublishHiveCampaignProjectionWhenPortAvailable() {
        BenchmarkCampaign campaign = BenchmarkCampaign.builder()
                .id("campaign-1")
                .suiteId("suite-1")
                .status("created")
                .runIds(List.of("run-1"))
                .build();
        when(benchmarkLabService.createRegressionCampaign("run-1")).thenReturn(campaign);

        StepVerifier.create(controller.createRegressionCampaign("run-1"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("campaign-1", response.getBody().getId());
                })
                .verifyComplete();

        verify(projectionPublishPort).publishSelfEvolvingCampaignProjection(null, campaign);
    }

    @Test
    void shouldSwallowHiveCampaignProjectionFailures() {
        BenchmarkCampaign campaign = BenchmarkCampaign.builder()
                .id("campaign-2")
                .suiteId("suite-2")
                .status("created")
                .runIds(List.of("run-2"))
                .build();
        when(benchmarkLabService.createRegressionCampaign("run-2")).thenReturn(campaign);
        doThrow(new IllegalStateException("publish failed"))
                .when(projectionPublishPort)
                .publishSelfEvolvingCampaignProjection(null, campaign);

        StepVerifier.create(controller.createRegressionCampaign("run-2"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("campaign-2", response.getBody().getId());
                })
                .verifyComplete();
    }
}
