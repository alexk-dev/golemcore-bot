package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.service.HiveSessionStateStore;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import me.golemcore.bot.domain.model.hive.HiveRuntimeContracts;
import org.junit.jupiter.api.Test;

class HiveEventBatchPublisherSelfEvolvingTest {

    private HiveEventBatchPublisher publisher;

    @BeforeEach
    void setUp() {
        HiveSessionStateStore hiveSessionStateStore = mock(HiveSessionStateStore.class);
        HiveApiClient hiveApiClient = mock(HiveApiClient.class);
        StoragePort storagePort = mock(StoragePort.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        publisher = new HiveEventBatchPublisher(
                hiveSessionStateStore,
                hiveApiClient,
                new HiveEventOutboxService(storagePort, objectMapper),
                objectMapper);
        when(hiveSessionStateStore.load()).thenReturn(Optional.of(HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .accessToken("access")
                .build()));
    }

    @Test
    void shouldBuildSelfEvolvingRunProjectionEvent() {
        HiveEventPayload payload = publisher.buildSelfEvolvingRunProjection(
                RunRecord.builder()
                        .id("run-1")
                        .golemId("golem-1")
                        .sessionId("session-1")
                        .traceId("trace-1")
                        .artifactBundleId("bundle-1")
                        .status("COMPLETED")
                        .startedAt(Instant.parse("2026-03-31T15:00:00Z"))
                        .completedAt(Instant.parse("2026-03-31T15:00:30Z"))
                        .build(),
                RunVerdict.builder()
                        .runId("run-1")
                        .outcomeStatus("COMPLETED")
                        .processStatus("HEALTHY")
                        .promotionRecommendation("approve_gated")
                        .outcomeSummary("Completed successfully")
                        .processSummary("Tier routing was efficient")
                        .confidence(0.91d)
                        .processFindings(List.of("No tier escalation required"))
                        .build());

        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_RUN_UPSERTED, payload.eventType());
        assertEquals("run-1", payload.runId());
        assertEquals("golem-1", payload.golemId());
        assertInstanceOf(Map.class, payload.payload());
        assertEquals("COMPLETED", ((Map<?, ?>) payload.payload()).get("outcomeStatus"));
    }

    @Test
    void shouldBuildSelfEvolvingCandidateProjectionEvent() {
        HiveEventPayload payload = publisher.buildSelfEvolvingCandidateProjection(
                "golem-1",
                EvolutionCandidate.builder()
                        .id("candidate-1")
                        .goal("fix")
                        .artifactType("skill")
                        .status("approved_pending")
                        .riskLevel("medium")
                        .expectedImpact("Reduce routing failures")
                        .sourceRunIds(List.of("run-1"))
                        .build());

        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_CANDIDATE_UPSERTED, payload.eventType());
        assertEquals("golem-1", payload.golemId());
        assertInstanceOf(Map.class, payload.payload());
        assertEquals("candidate-1", ((Map<?, ?>) payload.payload()).get("id"));
    }

    @Test
    void shouldBuildSelfEvolvingBenchmarkCampaignProjectionEvent() {
        HiveEventPayload payload = publisher.buildSelfEvolvingCampaignProjection(
                "golem-1",
                BenchmarkCampaign.builder()
                        .id("campaign-1")
                        .suiteId("suite-1")
                        .baselineBundleId("bundle-a")
                        .candidateBundleId("bundle-b")
                        .status("created")
                        .startedAt(Instant.parse("2026-03-31T16:00:00Z"))
                        .runIds(List.of("run-1"))
                        .build());

        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_CAMPAIGN_UPSERTED, payload.eventType());
        assertEquals("golem-1", payload.golemId());
        assertInstanceOf(Map.class, payload.payload());
        assertEquals("campaign-1", ((Map<?, ?>) payload.payload()).get("id"));
    }
}
