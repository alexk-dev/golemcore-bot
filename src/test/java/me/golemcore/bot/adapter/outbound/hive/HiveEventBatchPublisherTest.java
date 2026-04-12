package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCatalogEntry;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCompareEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactImpactProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageEdge;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageNode;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactNormalizedRevisionProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionDiffProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionDiffProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionEvidenceProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveInspectionResponse;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.ProgressUpdate;
import me.golemcore.bot.domain.model.ProgressUpdateType;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.model.hive.HiveEvidenceRef;
import me.golemcore.bot.domain.model.hive.HiveLifecycleSignalRequest;
import me.golemcore.bot.domain.service.HiveSessionStateStore;
import me.golemcore.bot.port.outbound.StoragePort;

class HiveEventBatchPublisherTest {

    private HiveSessionStateStore hiveSessionStateStore;
    private HiveApiClient hiveApiClient;
    private HiveEventBatchPublisher publisher;
    private StoragePort storagePort;
    private Map<String, String> persistedFiles;

    @BeforeEach
    void setUp() {
        hiveSessionStateStore = mock(HiveSessionStateStore.class);
        hiveApiClient = mock(HiveApiClient.class);
        storagePort = mock(StoragePort.class);
        persistedFiles = new ConcurrentHashMap<>();
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    persistedFiles.put(invocation.getArgument(1), invocation.getArgument(2));
                    return CompletableFuture.completedFuture(null);
                });
        when(storagePort.getText(anyString(), anyString()))
                .thenAnswer(
                        invocation -> CompletableFuture.completedFuture(persistedFiles.get(invocation.getArgument(1))));
        publisher = new HiveEventBatchPublisher(
                hiveSessionStateStore,
                hiveApiClient,
                new HiveEventOutboxService(storagePort, new ObjectMapper().registerModule(new JavaTimeModule())),
                new ObjectMapper().registerModule(new JavaTimeModule()));
        when(hiveSessionStateStore.load()).thenReturn(Optional.of(HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .accessToken("access")
                .build()));
    }

    @Test
    void shouldPublishThreadMessageAndUsageEvent() {
        publisher.publishThreadMessage("thread-1", "Done", Map.of(
                ContextAttributes.HIVE_THREAD_ID, "thread-1",
                ContextAttributes.HIVE_CARD_ID, "card-1",
                ContextAttributes.HIVE_COMMAND_ID, "cmd-1",
                ContextAttributes.HIVE_RUN_ID, "run-1",
                "inputTokens", 120L,
                "outputTokens", 45L));

        ArgumentCaptor<List<HiveEventPayload>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hiveApiClient).publishEventsBatch(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eventsCaptor.capture());
        List<HiveEventPayload> events = eventsCaptor.getValue();
        assertEquals(2, events.size());
        assertEquals("THREAD_MESSAGE", events.get(0).runtimeEventType());
        assertEquals("thread-1", events.get(0).threadId());
        assertEquals("cmd-1", events.get(0).commandId());
        assertEquals("Done", events.get(0).details());
        assertEquals("USAGE_REPORTED", events.get(1).runtimeEventType());
        assertEquals(120L, events.get(1).inputTokens());
        assertEquals(45L, events.get(1).outputTokens());
    }

    @Test
    void shouldMapRuntimeEventsToHiveRuntimeTypes() {
        List<RuntimeEvent> runtimeEvents = List.of(
                RuntimeEvent.builder()
                        .type(RuntimeEventType.TURN_STARTED)
                        .timestamp(Instant.parse("2026-03-18T00:00:01Z"))
                        .sessionId("hive:thread-1")
                        .channelType("hive")
                        .chatId("thread-1")
                        .payload(Map.of())
                        .build(),
                RuntimeEvent.builder()
                        .type(RuntimeEventType.TOOL_STARTED)
                        .timestamp(Instant.parse("2026-03-18T00:00:02Z"))
                        .sessionId("hive:thread-1")
                        .channelType("hive")
                        .chatId("thread-1")
                        .payload(Map.of("tool", "shell"))
                        .build(),
                RuntimeEvent.builder()
                        .type(RuntimeEventType.TURN_FINISHED)
                        .timestamp(Instant.parse("2026-03-18T00:00:03Z"))
                        .sessionId("hive:thread-1")
                        .channelType("hive")
                        .chatId("thread-1")
                        .payload(Map.of("reason", "completed"))
                        .build());

        publisher.publishRuntimeEvents(runtimeEvents, Map.of(
                ContextAttributes.HIVE_THREAD_ID, "thread-1",
                ContextAttributes.HIVE_CARD_ID, "card-1",
                ContextAttributes.HIVE_COMMAND_ID, "cmd-1",
                ContextAttributes.HIVE_RUN_ID, "run-1"));

        ArgumentCaptor<List<HiveEventPayload>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hiveApiClient).publishEventsBatch(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eventsCaptor.capture());
        List<HiveEventPayload> events = eventsCaptor.getValue();
        assertEquals(List.of("RUN_STARTED", "RUN_PROGRESS", "RUN_COMPLETED"),
                events.stream()
                        .filter(event -> "runtime_event".equals(event.eventType()))
                        .map(HiveEventPayload::runtimeEventType)
                        .toList());
        assertEquals(List.of("WORK_STARTED"),
                events.stream()
                        .filter(event -> "card_lifecycle_signal".equals(event.eventType()))
                        .map(HiveEventPayload::signalType)
                        .toList());
        assertEquals("card-1", events.get(0).cardId());
        assertEquals("run-1", events.get(0).runId());
    }

    @Test
    void shouldPublishProgressUpdateWithoutHiveKeysInDetails() {
        publisher.publishProgressUpdate("thread-1", new ProgressUpdate(
                ProgressUpdateType.SUMMARY,
                "Progress update",
                Map.of(
                        ContextAttributes.HIVE_THREAD_ID, "thread-1",
                        ContextAttributes.HIVE_COMMAND_ID, "cmd-1",
                        "toolCount", 2)));

        ArgumentCaptor<List<HiveEventPayload>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hiveApiClient).publishEventsBatch(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eventsCaptor.capture());
        HiveEventPayload event = eventsCaptor.getValue().get(0);
        assertEquals("RUN_PROGRESS", event.runtimeEventType());
        assertEquals("Progress update", event.summary());
        assertFalse(event.details().contains(ContextAttributes.HIVE_THREAD_ID));
        assertTrue(event.details().contains("toolCount"));
    }

    @Test
    void shouldSkipPublishWithoutActiveHiveSession() {
        when(hiveSessionStateStore.load()).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> publisher.publishCommandAcknowledged(HiveControlCommandEnvelope.builder()
                        .commandId("cmd-1")
                        .threadId("thread-1")
                        .body("Do work")
                        .build()));

        verify(hiveApiClient, never()).publishEventsBatch(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                anyList());
    }

    @Test
    void shouldPublishExplicitLifecycleSignal() {
        publisher.publishLifecycleSignal(
                new HiveLifecycleSignalRequest(
                        "BLOCKER_RAISED",
                        "Missing credentials",
                        "Need staging API key",
                        "missing_credentials",
                        List.of(new HiveEvidenceRef("run_log", "run-1/log-2")),
                        Instant.parse("2026-03-18T00:02:00Z")),
                Map.of(
                        ContextAttributes.HIVE_THREAD_ID, "thread-1",
                        ContextAttributes.HIVE_CARD_ID, "card-1",
                        ContextAttributes.HIVE_COMMAND_ID, "cmd-1",
                        ContextAttributes.HIVE_RUN_ID, "run-1"));

        ArgumentCaptor<List<HiveEventPayload>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hiveApiClient).publishEventsBatch(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eventsCaptor.capture());
        HiveEventPayload event = eventsCaptor.getValue().get(0);
        assertEquals("card_lifecycle_signal", event.eventType());
        assertEquals("BLOCKER_RAISED", event.signalType());
        assertEquals("Missing credentials", event.summary());
        assertEquals("missing_credentials", event.blockerCode());
        assertEquals(1, event.evidenceRefs().size());
        assertEquals("run_log", event.evidenceRefs().get(0).kind());
    }

    @Test
    void shouldPublishInspectionResponseEvent() {
        publisher.publishInspectionResponse(HiveInspectionResponse.builder()
                .requestId("req-1")
                .threadId("thread-1")
                .cardId("card-1")
                .runId("run-1")
                .golemId("golem-1")
                .operation("sessions.list")
                .success(true)
                .payload(List.of(Map.of("id", "web:conv-1")))
                .createdAt(Instant.parse("2026-03-18T00:05:00Z"))
                .build());

        ArgumentCaptor<List<HiveEventPayload>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hiveApiClient).publishEventsBatch(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eventsCaptor.capture());
        HiveEventPayload event = eventsCaptor.getValue().get(0);
        assertEquals("inspection_response", event.eventType());
        assertEquals("req-1", event.requestId());
        assertEquals("sessions.list", event.operation());
        assertEquals(Boolean.TRUE, event.success());
        assertEquals(List.of(Map.of("id", "web:conv-1")), event.payload());
    }

    @Test
    void shouldMapFailedAndCancelledRuntimeEventsToLifecycleSignals() {
        List<RuntimeEvent> runtimeEvents = List.of(
                RuntimeEvent.builder()
                        .type(RuntimeEventType.TURN_FAILED)
                        .timestamp(Instant.parse("2026-03-18T00:00:01Z"))
                        .sessionId("hive:thread-1")
                        .channelType("hive")
                        .chatId("thread-1")
                        .payload(Map.of("reason", "tool_error", "code", "shell_failed"))
                        .build(),
                RuntimeEvent.builder()
                        .type(RuntimeEventType.TURN_FINISHED)
                        .timestamp(Instant.parse("2026-03-18T00:00:02Z"))
                        .sessionId("hive:thread-1")
                        .channelType("hive")
                        .chatId("thread-1")
                        .payload(Map.of("reason", "user_interrupt"))
                        .build());

        publisher.publishRuntimeEvents(runtimeEvents, Map.of(
                ContextAttributes.HIVE_THREAD_ID, "thread-1",
                ContextAttributes.HIVE_CARD_ID, "card-1",
                ContextAttributes.HIVE_COMMAND_ID, "cmd-1",
                ContextAttributes.HIVE_RUN_ID, "run-1"));

        ArgumentCaptor<List<HiveEventPayload>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hiveApiClient).publishEventsBatch(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eventsCaptor.capture());
        List<HiveEventPayload> events = eventsCaptor.getValue();
        assertEquals(List.of("RUN_FAILED", "RUN_CANCELLED"),
                events.stream()
                        .filter(event -> "runtime_event".equals(event.eventType()))
                        .map(HiveEventPayload::runtimeEventType)
                        .toList());
        assertEquals(List.of("WORK_FAILED", "WORK_CANCELLED"),
                events.stream()
                        .filter(event -> "card_lifecycle_signal".equals(event.eventType()))
                        .map(HiveEventPayload::signalType)
                        .toList());
    }

    @Test
    void shouldPublishSelfEvolvingRunAndCandidateProjections() {
        RunRecord runRecord = RunRecord.builder()
                .id("run-1")
                .golemId("golem-1")
                .sessionId("session-1")
                .traceId("trace-1")
                .artifactBundleId("bundle-1")
                .status("completed")
                .startedAt(Instant.parse("2026-03-18T00:00:00Z"))
                .completedAt(Instant.parse("2026-03-18T00:05:00Z"))
                .build();
        RunVerdict verdict = RunVerdict.builder()
                .runId("run-1")
                .outcomeStatus("success")
                .processStatus("good")
                .promotionRecommendation("shadow")
                .outcomeSummary("Task completed")
                .processSummary("Skill selection was stable")
                .confidence(0.91)
                .processFindings(List.of("No unnecessary retries"))
                .evidenceRefs(List.of(VerdictEvidenceRef.builder()
                        .traceId("trace-1")
                        .spanId("span-1")
                        .snapshotId("snapshot-1")
                        .build()))
                .build();
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-1")
                .artifactType("skill")
                .artifactKey("skill:planner")
                .status("proposed")
                .riskLevel("medium")
                .expectedImpact("Improve planning quality")
                .sourceRunIds(List.of("run-1"))
                .build();

        publisher.publishSelfEvolvingProjection(runRecord, verdict, List.of(candidate));

        ArgumentCaptor<List<HiveEventPayload>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hiveApiClient).publishEventsBatch(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eventsCaptor.capture());
        List<HiveEventPayload> events = eventsCaptor.getValue();
        assertEquals(2, events.size());

        HiveEventPayload runEvent = events.get(0);
        assertEquals("selfevolving.run.upserted", runEvent.eventType());
        assertEquals("Task completed", runEvent.summary());
        assertEquals("trace_snapshot", runEvent.evidenceRefs().getFirst().kind());
        Map<String, Object> runPayload = assertInstanceOf(Map.class, runEvent.payload());
        assertEquals("run-1", runPayload.get("id"));
        assertEquals("success", runPayload.get("outcomeStatus"));
        assertEquals(List.of("No unnecessary retries"), runPayload.get("processFindings"));

        HiveEventPayload candidateEvent = events.get(1);
        assertEquals("selfevolving.candidate.upserted", candidateEvent.eventType());
        assertEquals("Improve planning quality", candidateEvent.summary());
        Map<String, Object> candidatePayload = assertInstanceOf(Map.class, candidateEvent.payload());
        assertEquals("candidate-1", candidatePayload.get("id"));
        assertEquals("medium", candidatePayload.get("riskLevel"));
        assertEquals(List.of("run-1"), candidatePayload.get("sourceRunIds"));
    }

    @Test
    void shouldBuildSelfEvolvingArtifactProjectionPayloads() {
        Instant projectedAt = Instant.parse("2026-03-18T00:06:00Z");
        ArtifactCatalogEntry entry = ArtifactCatalogEntry.builder()
                .artifactStreamId("stream-1")
                .originArtifactStreamId("origin-1")
                .artifactKey("skill:planner")
                .artifactAliases(List.of("planner"))
                .artifactType("skill")
                .artifactSubtype("workflow")
                .displayName("Planner")
                .latestRevisionId("rev-2")
                .activeRevisionId("rev-1")
                .latestCandidateRevisionId("rev-2")
                .currentLifecycleState("active")
                .currentRolloutStage("active")
                .hasRegression(Boolean.TRUE)
                .hasPendingApproval(Boolean.FALSE)
                .campaignCount(3)
                .projectionSchemaVersion(2)
                .updatedAt(projectedAt)
                .projectedAt(projectedAt)
                .build();
        HiveEventPayload catalogEvent = publisher.buildSelfEvolvingArtifactCatalogProjection(null, entry);
        assertEquals("selfevolving.artifact.upserted", catalogEvent.eventType());
        assertEquals("skill:planner", catalogEvent.summary());
        Map<String, Object> catalogPayload = assertInstanceOf(Map.class, catalogEvent.payload());
        assertEquals("stream-1", catalogPayload.get("artifactStreamId"));
        assertEquals("rev-2", catalogPayload.get("latestRevisionId"));
        assertEquals("dev", catalogPayload.get("sourceBotVersion"));

        ArtifactNormalizedRevisionProjection normalizedProjection = ArtifactNormalizedRevisionProjection.builder()
                .artifactStreamId("stream-1")
                .contentRevisionId("rev-2")
                .normalizationSchemaVersion(4)
                .normalizedContent("normalized-content")
                .normalizedHash("hash-1")
                .semanticSections(List.of("prompt", "rules"))
                .projectedAt(projectedAt)
                .build();
        HiveEventPayload normalizedEvent = publisher.buildSelfEvolvingArtifactNormalizedRevisionProjection(
                "golem-9", normalizedProjection);
        assertEquals("selfevolving.artifact.normalized-revision.upserted", normalizedEvent.eventType());
        assertEquals("golem-9", normalizedEvent.golemId());
        Map<String, Object> normalizedPayload = assertInstanceOf(Map.class, normalizedEvent.payload());
        assertEquals("rev-2", normalizedPayload.get("contentRevisionId"));
        assertEquals(List.of("prompt", "rules"), normalizedPayload.get("semanticSections"));

        ArtifactLineageProjection lineageProjection = ArtifactLineageProjection.builder()
                .artifactStreamId("stream-1")
                .originArtifactStreamId("origin-1")
                .artifactKey("skill:planner")
                .nodes(List.of(ArtifactLineageNode.builder()
                        .nodeId("node-1")
                        .contentRevisionId("rev-1")
                        .rolloutStage("active")
                        .build()))
                .edges(List.of(ArtifactLineageEdge.builder()
                        .edgeId("edge-1")
                        .fromNodeId("node-1")
                        .toNodeId("node-2")
                        .edgeType("approved")
                        .build()))
                .railOrder(List.of("node-1", "node-2"))
                .branches(List.of("main"))
                .defaultSelectedNodeId("node-1")
                .defaultSelectedRevisionId("rev-1")
                .projectionSchemaVersion(3)
                .projectedAt(projectedAt)
                .build();
        HiveEventPayload lineageEvent = publisher.buildSelfEvolvingArtifactLineageProjection(null, lineageProjection);
        assertEquals("selfevolving.artifact.lineage.upserted", lineageEvent.eventType());
        Map<String, Object> lineagePayload = assertInstanceOf(Map.class, lineageEvent.payload());
        assertEquals("origin-1", lineagePayload.get("originArtifactStreamId"));
        assertEquals(List.of("main"), lineagePayload.get("branches"));
        assertNotNull(lineagePayload.get("nodes"));
        assertNotNull(lineagePayload.get("edges"));
    }

    @Test
    void shouldBuildDiffEvidenceAndCampaignProjections() {
        Instant projectedAt = Instant.parse("2026-03-18T00:07:00Z");
        ArtifactImpactProjection impactProjection = ArtifactImpactProjection.builder()
                .artifactStreamId("stream-1")
                .fromRevisionId("rev-1")
                .toRevisionId("rev-2")
                .qualityDelta(0.4)
                .costDelta(-0.2)
                .summary("Quality improved")
                .projectedAt(projectedAt)
                .build();

        ArtifactRevisionDiffProjection revisionDiff = ArtifactRevisionDiffProjection.builder()
                .artifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .fromRevisionId("rev-1")
                .toRevisionId("rev-2")
                .summary("Added retry guard")
                .semanticSections(List.of("logic"))
                .rawPatch("@@ -1 +1 @@")
                .changedFields(List.of("prompt"))
                .riskSignals(List.of("behavior-change"))
                .impactSummary(impactProjection)
                .projectionSchemaVersion(2)
                .projectedAt(projectedAt)
                .build();
        HiveEventPayload revisionDiffEvent = publisher.buildSelfEvolvingArtifactRevisionDiffProjection(
                null, revisionDiff);
        assertEquals("selfevolving.artifact.diff.upserted", revisionDiffEvent.eventType());
        Map<String, Object> revisionDiffPayload = assertInstanceOf(Map.class, revisionDiffEvent.payload());
        assertEquals("revision", revisionDiffPayload.get("payloadKind"));
        assertEquals(List.of("behavior-change"), revisionDiffPayload.get("riskSignals"));

        ArtifactTransitionDiffProjection transitionDiff = ArtifactTransitionDiffProjection.builder()
                .artifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .fromNodeId("node-1")
                .toNodeId("node-2")
                .fromRevisionId("rev-1")
                .toRevisionId("rev-2")
                .fromRolloutStage("candidate")
                .toRolloutStage("active")
                .contentChanged(true)
                .summary("Promoted to active")
                .impactSummary(impactProjection)
                .projectionSchemaVersion(2)
                .projectedAt(projectedAt)
                .build();
        HiveEventPayload transitionDiffEvent = publisher.buildSelfEvolvingArtifactTransitionDiffProjection(
                "golem-2", transitionDiff);
        Map<String, Object> transitionDiffPayload = assertInstanceOf(Map.class, transitionDiffEvent.payload());
        assertEquals("transition", transitionDiffPayload.get("payloadKind"));
        assertEquals("golem-2", transitionDiffEvent.golemId());
        assertEquals(Boolean.TRUE, transitionDiffPayload.get("contentChanged"));

        ArtifactRevisionEvidenceProjection revisionEvidence = ArtifactRevisionEvidenceProjection.builder()
                .artifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .revisionId("rev-2")
                .runIds(List.of("run-1"))
                .traceIds(List.of("trace-1"))
                .spanIds(List.of("span-1"))
                .campaignIds(List.of("campaign-1"))
                .promotionDecisionIds(List.of("decision-1"))
                .approvalRequestIds(List.of("approval-1"))
                .findings(List.of("Judge preferred candidate"))
                .projectionSchemaVersion(2)
                .projectedAt(projectedAt)
                .build();
        HiveEventPayload revisionEvidenceEvent = publisher.buildSelfEvolvingArtifactEvidenceProjection(
                null, "revision", revisionEvidence);
        Map<String, Object> revisionEvidencePayload = assertInstanceOf(Map.class, revisionEvidenceEvent.payload());
        assertEquals("revision", revisionEvidencePayload.get("payloadKind"));
        assertEquals(List.of("trace-1"), revisionEvidencePayload.get("traceIds"));

        ArtifactCompareEvidenceProjection compareEvidence = ArtifactCompareEvidenceProjection.builder()
                .artifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .fromRevisionId("rev-1")
                .toRevisionId("rev-2")
                .runIds(List.of("run-1", "run-2"))
                .findings(List.of("Candidate wins"))
                .projectionSchemaVersion(2)
                .projectedAt(projectedAt)
                .build();
        HiveEventPayload compareEvidenceEvent = publisher.buildSelfEvolvingArtifactEvidenceProjection(
                "golem-3", "compare", compareEvidence);
        Map<String, Object> compareEvidencePayload = assertInstanceOf(Map.class, compareEvidenceEvent.payload());
        assertEquals("compare", compareEvidencePayload.get("payloadKind"));
        assertEquals("rev-1", compareEvidencePayload.get("fromRevisionId"));
        assertEquals("golem-3", compareEvidenceEvent.golemId());

        ArtifactTransitionEvidenceProjection transitionEvidence = ArtifactTransitionEvidenceProjection.builder()
                .artifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .fromNodeId("node-1")
                .toNodeId("node-2")
                .fromRevisionId("rev-1")
                .toRevisionId("rev-2")
                .findings(List.of("Approved after canary"))
                .projectionSchemaVersion(2)
                .projectedAt(projectedAt)
                .build();
        HiveEventPayload transitionEvidenceEvent = publisher.buildSelfEvolvingArtifactEvidenceProjection(
                null, "transition", transitionEvidence);
        Map<String, Object> transitionEvidencePayload = assertInstanceOf(Map.class, transitionEvidenceEvent.payload());
        assertEquals("transition", transitionEvidencePayload.get("payloadKind"));
        assertEquals("node-1", transitionEvidencePayload.get("fromNodeId"));
        assertEquals(List.of(), transitionEvidencePayload.get("runIds"));

        BenchmarkCampaign campaign = BenchmarkCampaign.builder()
                .id("campaign-1")
                .suiteId("suite-1")
                .baselineBundleId("bundle-a")
                .candidateBundleId("bundle-b")
                .status("completed")
                .runIds(List.of("run-1"))
                .startedAt(Instant.parse("2026-03-18T00:01:00Z"))
                .completedAt(projectedAt)
                .build();
        HiveEventPayload campaignEvent = publisher.buildSelfEvolvingCampaignProjection(null, campaign);
        assertEquals("selfevolving.campaign.upserted", campaignEvent.eventType());
        assertEquals("golem-1", campaignEvent.golemId());
        Map<String, Object> campaignPayload = assertInstanceOf(Map.class, campaignEvent.payload());
        assertEquals("bundle-b", campaignPayload.get("candidateBundleId"));
        assertTrue(campaignPayload.containsKey("completedAt"));
    }
}
