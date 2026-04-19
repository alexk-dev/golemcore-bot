package me.golemcore.bot.adapter.inbound.web.controller;

import static me.golemcore.bot.adapter.inbound.web.controller.SelfEvolvingControllerSupport.blocking;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingCampaignDto;
import me.golemcore.bot.adapter.inbound.web.projection.SelfEvolvingProjectionService;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.selfevolving.benchmark.BenchmarkLabService;
import me.golemcore.bot.port.outbound.HiveEventPublishPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * SelfEvolving benchmark campaign endpoints.
 */
@RestController
@RequestMapping("/api/self-evolving")
@Slf4j
public class SelfEvolvingBenchmarksController {

    private final SelfEvolvingProjectionService projectionService;
    private final BenchmarkLabService benchmarkLabService;
    private final HiveEventPublishPort hiveEventPublishPort;

    public SelfEvolvingBenchmarksController(SelfEvolvingProjectionService projectionService,
            BenchmarkLabService benchmarkLabService,
            HiveEventPublishPort hiveEventPublishPort) {
        this.projectionService = projectionService;
        this.benchmarkLabService = benchmarkLabService;
        this.hiveEventPublishPort = hiveEventPublishPort;
    }

    @GetMapping("/benchmarks/campaigns")
    public Mono<ResponseEntity<List<SelfEvolvingCampaignDto>>> listCampaigns() {
        return blocking(() -> ResponseEntity.ok(projectionService.listCampaigns()));
    }

    @PostMapping("/benchmarks/regression/{runId}")
    public Mono<ResponseEntity<SelfEvolvingCampaignDto>> createRegressionCampaign(@PathVariable String runId) {
        return blocking(() -> {
            BenchmarkCampaign campaign = benchmarkLabService.createRegressionCampaign(runId);
            publishHiveCampaignProjection(campaign);
            return ResponseEntity.ok(toCampaignDto(campaign));
        });
    }

    private SelfEvolvingCampaignDto toCampaignDto(BenchmarkCampaign campaign) {
        return SelfEvolvingCampaignDto.builder()
                .id(campaign.getId())
                .suiteId(campaign.getSuiteId())
                .baselineBundleId(campaign.getBaselineBundleId())
                .candidateBundleId(campaign.getCandidateBundleId())
                .status(campaign.getStatus())
                .startedAt(campaign.getStartedAt() != null ? campaign.getStartedAt().toString() : null)
                .completedAt(campaign.getCompletedAt() != null ? campaign.getCompletedAt().toString() : null)
                .runIds(campaign.getRunIds())
                .build();
    }

    private void publishHiveCampaignProjection(BenchmarkCampaign campaign) {
        if (hiveEventPublishPort == null || campaign == null) {
            return;
        }
        try {
            hiveEventPublishPort.publishSelfEvolvingCampaignProjection(null, campaign);
        } catch (RuntimeException exception) {
            log.debug("[Hive] Skipping SelfEvolving campaign projection publish: {}", exception.getMessage());
        }
    }
}
