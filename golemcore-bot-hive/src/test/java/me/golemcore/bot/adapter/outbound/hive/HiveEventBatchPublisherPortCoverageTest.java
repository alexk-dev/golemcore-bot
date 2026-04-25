package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCatalogEntry;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCompareEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactNormalizedRevisionProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionDiffProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionDiffProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchExplanation;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import me.golemcore.bot.domain.service.HiveSessionStateStore;
import me.golemcore.bot.port.outbound.SelfEvolvingProjectionPublishPort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import me.golemcore.bot.domain.model.hive.HiveRuntimeContracts;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HiveEventBatchPublisherPortCoverageTest {

    private HiveApiClient hiveApiClient;
    private HiveEventBatchPublisher publisher;
    private SelfEvolvingProjectionPublishPort projectionPublisher;

    @BeforeEach
    void setUp() {
        HiveSessionStateStore hiveSessionStateStore = mock(HiveSessionStateStore.class);
        hiveApiClient = mock(HiveApiClient.class);
        StoragePort storagePort = mock(StoragePort.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        publisher = new HiveEventBatchPublisher(
                hiveSessionStateStore,
                hiveApiClient,
                new HiveEventOutboxService(storagePort, objectMapper),
                objectMapper);
        projectionPublisher = publisher;
        when(hiveSessionStateStore.load()).thenReturn(Optional.of(HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .accessToken("access")
                .build()));
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.getText(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void shouldPublishArtifactProjectionVariantsThroughPortMethods() {
        projectionPublisher.publishSelfEvolvingArtifactProjection(
                "golem-1",
                ArtifactCatalogEntry.builder()
                        .artifactStreamId("stream-2")
                        .artifactKey("skill:router")
                        .artifactType("skill")
                        .projectionSchemaVersion(1)
                        .projectedAt(Instant.parse("2026-04-01T00:00:00Z"))
                        .build());
        projectionPublisher.publishSelfEvolvingArtifactNormalizedRevisionProjection(
                "golem-1",
                ArtifactNormalizedRevisionProjection.builder()
                        .artifactStreamId("stream-2")
                        .contentRevisionId("rev-2")
                        .normalizationSchemaVersion(1)
                        .normalizedContent("router")
                        .normalizedHash("hash")
                        .projectedAt(Instant.parse("2026-04-01T00:01:00Z"))
                        .build());
        projectionPublisher.publishSelfEvolvingArtifactLineageProjection(
                "golem-1",
                ArtifactLineageProjection.builder()
                        .artifactStreamId("stream-2")
                        .artifactKey("skill:router")
                        .projectionSchemaVersion(1)
                        .projectedAt(Instant.parse("2026-04-01T00:02:00Z"))
                        .build());
        projectionPublisher.publishSelfEvolvingArtifactRevisionDiffProjection(
                "golem-1",
                ArtifactRevisionDiffProjection.builder()
                        .artifactStreamId("stream-2")
                        .artifactKey("skill:router")
                        .fromRevisionId("rev-1")
                        .toRevisionId("rev-2")
                        .projectionSchemaVersion(1)
                        .projectedAt(Instant.parse("2026-04-01T00:03:00Z"))
                        .build());
        projectionPublisher.publishSelfEvolvingArtifactTransitionDiffProjection(
                "golem-1",
                ArtifactTransitionDiffProjection.builder()
                        .artifactStreamId("stream-2")
                        .artifactKey("skill:router")
                        .fromNodeId("n1")
                        .toNodeId("n2")
                        .fromRevisionId("rev-1")
                        .toRevisionId("rev-2")
                        .projectionSchemaVersion(1)
                        .projectedAt(Instant.parse("2026-04-01T00:04:00Z"))
                        .build());
        projectionPublisher.publishSelfEvolvingArtifactRevisionEvidenceProjection(
                "golem-1",
                ArtifactRevisionEvidenceProjection.builder()
                        .artifactStreamId("stream-2")
                        .artifactKey("skill:router")
                        .revisionId("rev-2")
                        .projectionSchemaVersion(1)
                        .projectedAt(Instant.parse("2026-04-01T00:05:00Z"))
                        .build());
        projectionPublisher.publishSelfEvolvingArtifactTransitionEvidenceProjection(
                "golem-1",
                ArtifactTransitionEvidenceProjection.builder()
                        .artifactStreamId("stream-2")
                        .artifactKey("skill:router")
                        .fromNodeId("n1")
                        .toNodeId("n2")
                        .fromRevisionId("rev-1")
                        .toRevisionId("rev-2")
                        .projectionSchemaVersion(1)
                        .projectedAt(Instant.parse("2026-04-01T00:06:00Z"))
                        .build());
        projectionPublisher.publishSelfEvolvingArtifactCompareEvidenceProjection(
                "golem-1",
                ArtifactCompareEvidenceProjection.builder()
                        .artifactStreamId("stream-2")
                        .artifactKey("skill:router")
                        .fromRevisionId("rev-1")
                        .toRevisionId("rev-2")
                        .projectionSchemaVersion(1)
                        .projectedAt(Instant.parse("2026-04-01T00:07:00Z"))
                        .build());

        ArgumentCaptor<List<HiveEventPayload>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hiveApiClient, times(8)).publishEventsBatch(
                eq("https://hive.example.com"), eq("golem-1"), eq("access"), eventsCaptor.capture());
        List<List<HiveEventPayload>> allBatches = eventsCaptor.getAllValues();
        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_ARTIFACT_UPSERTED,
                allBatches.get(0).getFirst().eventType());
        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_ARTIFACT_NORMALIZED_REVISION_UPSERTED,
                allBatches.get(1).getFirst().eventType());
        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_ARTIFACT_LINEAGE_UPSERTED,
                allBatches.get(2).getFirst().eventType());
        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_ARTIFACT_DIFF_UPSERTED,
                allBatches.get(3).getFirst().eventType());
        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_ARTIFACT_DIFF_UPSERTED,
                allBatches.get(4).getFirst().eventType());
        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_ARTIFACT_EVIDENCE_UPSERTED,
                allBatches.get(5).getFirst().eventType());
        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_ARTIFACT_EVIDENCE_UPSERTED,
                allBatches.get(6).getFirst().eventType());
        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_ARTIFACT_EVIDENCE_UPSERTED,
                allBatches.get(7).getFirst().eventType());
    }

    @Test
    void shouldPublishTacticSearchProjectionThroughPortMethod() {
        projectionPublisher.publishSelfEvolvingTacticSearchProjection(
                "recover shell",
                TacticSearchStatus.builder()
                        .mode("hybrid")
                        .reason("degraded")
                        .updatedAt(Instant.parse("2026-04-01T00:08:00Z"))
                        .build(),
                List.of(TacticSearchResult.builder()
                        .tacticId("planner")
                        .artifactKey("skill:planner")
                        .artifactType("skill")
                        .title("Planner tactic")
                        .updatedAt(Instant.parse("2026-04-01T00:08:00Z"))
                        .explanation(TacticSearchExplanation.builder()
                                .searchMode("hybrid")
                                .finalScore(1.2d)
                                .build())
                        .build()));

        ArgumentCaptor<List<HiveEventPayload>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hiveApiClient).publishEventsBatch(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eventsCaptor.capture());
        List<HiveEventPayload> batch = eventsCaptor.getValue();
        assertEquals(2, batch.size());
        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_TACTIC_SEARCH_STATUS_UPSERTED,
                batch.getFirst().eventType());
        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_TACTIC_UPSERTED, batch.get(1).eventType());
        assertEquals("recover shell", batch.getFirst().summary());
    }

    @Test
    void shouldHandleNullTacticStatusAndExplanationWhenPublishingSearchProjection() {
        projectionPublisher.publishSelfEvolvingTacticSearchProjection(
                "recover shell",
                null,
                List.of(TacticSearchResult.builder()
                        .tacticId("planner")
                        .artifactKey("skill:planner")
                        .artifactType("skill")
                        .title("Planner tactic")
                        .updatedAt(Instant.parse("2026-04-01T00:08:00Z"))
                        .explanation(null)
                        .build()));

        ArgumentCaptor<List<HiveEventPayload>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hiveApiClient).publishEventsBatch(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eventsCaptor.capture());
        List<HiveEventPayload> batch = eventsCaptor.getValue();
        Map<String, Object> statusPayload = assertInstanceOf(Map.class, batch.getFirst().payload());
        assertNull(statusPayload.get("mode"));
        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_TACTIC_UPSERTED, batch.get(1).eventType());
        Map<String, Object> tacticPayload = assertInstanceOf(Map.class, batch.get(1).payload());
        assertNull(tacticPayload.get("explanation"));
    }

    @Test
    void shouldNotLeakSearchOnlyFieldsIntoTacticCatalogProjection() {
        projectionPublisher.publishSelfEvolvingTacticCatalogProjection(List.of(TacticSearchResult.builder()
                .tacticId("planner")
                .artifactKey("skill:planner")
                .artifactType("skill")
                .title("Planner tactic")
                .score(1.2d)
                .recencyScore(0.8d)
                .golemLocalUsageSuccess(0.7d)
                .updatedAt(Instant.parse("2026-04-01T00:08:00Z"))
                .explanation(TacticSearchExplanation.builder()
                        .searchMode("hybrid")
                        .finalScore(1.2d)
                        .build())
                .build()));

        ArgumentCaptor<List<HiveEventPayload>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hiveApiClient).publishEventsBatch(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eventsCaptor.capture());
        List<HiveEventPayload> batch = eventsCaptor.getValue();
        assertEquals(1, batch.size());
        assertEquals(HiveRuntimeContracts.EVENT_TYPE_SELF_EVOLVING_TACTIC_UPSERTED, batch.getFirst().eventType());
        Map<String, Object> tacticPayload = assertInstanceOf(Map.class, batch.getFirst().payload());
        assertNull(tacticPayload.get("score"));
        assertNull(tacticPayload.get("recencyScore"));
        assertNull(tacticPayload.get("golemLocalUsageSuccess"));
        assertNull(tacticPayload.get("explanation"));
        assertNull(tacticPayload.get("searchQuery"));
    }

}
