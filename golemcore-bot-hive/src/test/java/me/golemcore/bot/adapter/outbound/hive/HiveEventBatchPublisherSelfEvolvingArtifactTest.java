package me.golemcore.bot.adapter.outbound.hive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCatalogEntry;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCompareEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactNormalizedRevisionProjection;
import me.golemcore.bot.domain.hive.HiveSessionStateStore;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import me.golemcore.bot.domain.model.hive.HiveRuntimeContracts;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HiveEventBatchPublisherSelfEvolvingArtifactTest {

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
    void shouldBuildArtifactCatalogProjectionEvent() {
        HiveEventPayload payload = publisher.buildSelfEvolvingArtifactCatalogProjection(
                "golem-1",
                ArtifactCatalogEntry.builder()
                        .artifactStreamId("stream-1")
                        .originArtifactStreamId("stream-1")
                        .artifactKey("skill:planner")
                        .artifactAliases(List.of("skill:planner"))
                        .artifactType("skill")
                        .artifactSubtype("skill")
                        .activeRevisionId("rev-1")
                        .latestCandidateRevisionId("rev-2")
                        .projectionSchemaVersion(1)
                        .projectedAt(Instant.parse("2026-03-31T20:00:00Z"))
                        .build());

        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_ARTIFACT_UPSERTED, payload.eventType());
        assertEquals("golem-1", payload.golemId());
        assertInstanceOf(Map.class, payload.payload());
        Map<?, ?> eventPayload = (Map<?, ?>) payload.payload();
        assertEquals("stream-1", eventPayload.get("artifactStreamId"));
        assertEquals(1, eventPayload.get("projectionSchemaVersion"));
        assertNotNull(eventPayload.get("sourceBotVersion"));
    }

    @Test
    void shouldBuildArtifactNormalizedRevisionAndLineageProjectionEvents() {
        HiveEventPayload normalizedPayload = publisher.buildSelfEvolvingArtifactNormalizedRevisionProjection(
                "golem-1",
                ArtifactNormalizedRevisionProjection.builder()
                        .artifactStreamId("stream-1")
                        .contentRevisionId("rev-2")
                        .normalizationSchemaVersion(1)
                        .normalizedContent("planner v2")
                        .normalizedHash("hash-2")
                        .semanticSections(List.of("planner"))
                        .projectedAt(Instant.parse("2026-03-31T20:01:00Z"))
                        .build());
        HiveEventPayload lineagePayload = publisher.buildSelfEvolvingArtifactLineageProjection(
                "golem-1",
                ArtifactLineageProjection.builder()
                        .artifactStreamId("stream-1")
                        .artifactKey("skill:planner")
                        .railOrder(List.of("candidate-1:proposed"))
                        .projectionSchemaVersion(1)
                        .projectedAt(Instant.parse("2026-03-31T20:02:00Z"))
                        .build());

        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_ARTIFACT_NORMALIZED_REVISION_UPSERTED,
                normalizedPayload.eventType());
        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_ARTIFACT_LINEAGE_UPSERTED,
                lineagePayload.eventType());
    }

    @Test
    void shouldBuildArtifactEvidenceProjectionEventWithCompareKind() {
        HiveEventPayload payload = publisher.buildSelfEvolvingArtifactEvidenceProjection(
                "golem-1",
                "compare",
                ArtifactCompareEvidenceProjection.builder()
                        .artifactStreamId("stream-1")
                        .artifactKey("skill:planner")
                        .fromRevisionId("rev-1")
                        .toRevisionId("rev-2")
                        .runIds(List.of("run-1", "run-2"))
                        .promotionDecisionIds(List.of("decision-1"))
                        .approvalRequestIds(List.of("approval-1"))
                        .projectionSchemaVersion(1)
                        .projectedAt(Instant.parse("2026-03-31T20:03:00Z"))
                        .build());

        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_ARTIFACT_EVIDENCE_UPSERTED, payload.eventType());
        assertInstanceOf(Map.class, payload.payload());
        Map<?, ?> eventPayload = (Map<?, ?>) payload.payload();
        assertEquals("compare", eventPayload.get("payloadKind"));
        assertEquals("stream-1", eventPayload.get("artifactStreamId"));
        assertEquals(List.of("run-1", "run-2"), eventPayload.get("runIds"));
    }
}
