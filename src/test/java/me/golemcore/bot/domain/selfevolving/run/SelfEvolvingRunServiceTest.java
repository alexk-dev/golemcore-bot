package me.golemcore.bot.domain.selfevolving.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.adapter.outbound.selfevolving.JsonRunJournalAdapter;
import me.golemcore.bot.port.outbound.StoragePort;
import me.golemcore.bot.port.outbound.selfevolving.RunJournalPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import me.golemcore.bot.domain.selfevolving.artifact.ArtifactBundleService;

class SelfEvolvingRunServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-03-31T14:00:00Z");

    private StoragePort storagePort;
    private ArtifactBundleService artifactBundleService;
    private Clock clock;
    private SelfEvolvingRunService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        artifactBundleService = mock(ArtifactBundleService.class);
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        service = new SelfEvolvingRunService(new JsonRunJournalAdapter(storagePort), artifactBundleService, clock);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        when(storagePort.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putTextAtomic(eq("self-evolving"), eq("runs.json"), anyString(), eq(true)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putTextAtomic(eq("self-evolving"), eq("run-verdicts.json"), anyString(), eq(true)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void shouldCreateRunRecordWithArtifactBundleBeforeJudging() throws Exception {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-1").chatId("chat-1").build())
                .build();
        context.setAttribute(ContextAttributes.HIVE_GOLEM_ID, "golem-1");
        when(artifactBundleService.snapshot(context)).thenReturn(ArtifactBundleRecord.builder()
                .id("bundle-1")
                .golemId("golem-1")
                .build());

        RunRecord run = service.startRun(context);

        assertNotNull(run.getId());
        assertEquals("golem-1", run.getGolemId());
        assertEquals("session-1", run.getSessionId());
        assertEquals("bundle-1", run.getArtifactBundleId());
        assertEquals("RUNNING", run.getStatus());
        assertEquals(FIXED_INSTANT, run.getStartedAt());
        verify(storagePort).putTextAtomic(eq("self-evolving"), eq("runs.json"), anyString(), eq(true));

        String persistedJson = service.exportRunsJson();
        assertTrue(persistedJson.contains("\"artifactBundleId\":\"bundle-1\""));
    }

    @Test
    void shouldCompleteRunWithFailedOutcomeAndUpdatedBundle() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-2").metadata(Map.of()).build())
                .turnOutcome(TurnOutcome.builder().finishReason(FinishReason.ITERATION_LIMIT).build())
                .build();
        RunRecord run = RunRecord.builder()
                .id("run-2")
                .artifactBundleId("bundle-2")
                .traceId("trace-before")
                .status("RUNNING")
                .build();
        ArtifactBundleRecord refreshedBundle = ArtifactBundleRecord.builder()
                .id("bundle-2b")
                .build();
        when(artifactBundleService.refresh("bundle-2", context)).thenReturn(refreshedBundle);

        RunRecord completed = service.completeRun(run, context);

        assertSame(run, completed);
        assertEquals("bundle-2b", completed.getArtifactBundleId());
        assertEquals("FAILED", completed.getStatus());
        assertEquals(FIXED_INSTANT, completed.getCompletedAt());
    }

    @Test
    void shouldFindRunsAndFallbackToMetadataGolemId() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .id("session-3")
                        .metadata(Map.of(ContextAttributes.HIVE_GOLEM_ID, "golem-meta"))
                        .build())
                .build();
        when(artifactBundleService.snapshot(context)).thenReturn(ArtifactBundleRecord.builder()
                .id("bundle-3")
                .golemId("golem-meta")
                .build());

        RunRecord run = service.startRun(context);

        assertTrue(service.findRun(run.getId()).isPresent());
        assertFalse(service.findRun(" ").isPresent());
        assertEquals("golem-meta", run.getGolemId());
    }

    @Test
    void shouldReturnCompletedStatusWhenOutcomeIsMissing() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-4").metadata(Map.of()).build())
                .build();
        RunRecord run = RunRecord.builder()
                .id("run-4")
                .artifactBundleId("bundle-4")
                .status("RUNNING")
                .build();
        when(artifactBundleService.refresh("bundle-4", context)).thenReturn(ArtifactBundleRecord.builder()
                .id("bundle-4")
                .build());

        RunRecord completed = service.completeRun(run, context);

        assertEquals("COMPLETED", completed.getStatus());
    }

    @Test
    void shouldUpdatePersistedRunWithoutDuplicatingAndRefreshTraceId() throws Exception {
        Map<String, String> persistedFiles = new ConcurrentHashMap<>();
        RunRecord storedRun = RunRecord.builder()
                .id("run-5")
                .golemId("golem-5")
                .artifactBundleId("bundle-5")
                .traceId("trace-old")
                .status("RUNNING")
                .startedAt(FIXED_INSTANT)
                .build();
        persistedFiles.put("runs.json", objectMapper.writeValueAsString(List.of(storedRun)));

        StoragePort persistedStorage = mapBackedStorage(persistedFiles);
        SelfEvolvingRunService persistedService = new SelfEvolvingRunService(
                new JsonRunJournalAdapter(persistedStorage), artifactBundleService,
                clock);
        RunRecord run = persistedService.getRuns().getFirst();
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-5").metadata(Map.of()).build())
                .turnOutcome(TurnOutcome.builder().finishReason(FinishReason.SUCCESS).build())
                .build();
        context.setTraceContext(TraceContext.builder()
                .traceId("trace-new")
                .spanId("span-new")
                .build());
        when(artifactBundleService.refresh("bundle-5", context)).thenReturn(ArtifactBundleRecord.builder()
                .id("bundle-5b")
                .build());

        RunRecord completed = persistedService.completeRun(run, context);

        assertEquals("COMPLETED", completed.getStatus());
        assertEquals("trace-new", completed.getTraceId());
        assertEquals("bundle-5b", completed.getArtifactBundleId());
        assertEquals(1, persistedService.getRuns().size());
        assertTrue(persistedService.exportRunsJson().contains("\"traceId\":\"trace-new\""));
    }

    @Test
    void shouldPersistAndLoadVerdictByRunId() {
        Map<String, String> persistedFiles = new ConcurrentHashMap<>();
        StoragePort persistedStorage = mapBackedStorage(persistedFiles);
        SelfEvolvingRunService writerService = new SelfEvolvingRunService(new JsonRunJournalAdapter(persistedStorage),
                artifactBundleService,
                clock);
        RunVerdict verdict = RunVerdict.builder()
                .id("verdict-1")
                .runId("run-6")
                .outcomeStatus("FAILED")
                .processSummary("Judge found a tool failure")
                .createdAt(FIXED_INSTANT)
                .build();

        writerService.saveVerdict("run-6", verdict);

        SelfEvolvingRunService readerService = new SelfEvolvingRunService(new JsonRunJournalAdapter(persistedStorage),
                artifactBundleService,
                clock);
        Optional<RunVerdict> storedVerdict = readerService.findVerdict("run-6");

        assertTrue(storedVerdict.isPresent());
        assertEquals("verdict-1", storedVerdict.get().getId());
        assertEquals("FAILED", storedVerdict.get().getOutcomeStatus());
        assertTrue(persistedFiles.containsKey("run-verdicts.json"));
    }

    @Test
    void shouldLoadLegacyVerdictListFormatByRunId() throws Exception {
        Map<String, String> persistedFiles = new ConcurrentHashMap<>();
        persistedFiles.put("run-verdicts.json", objectMapper.writeValueAsString(List.of(
                RunVerdict.builder()
                        .id("verdict-legacy")
                        .runId("run-legacy")
                        .outcomeStatus("COMPLETED")
                        .createdAt(FIXED_INSTANT)
                        .build())));
        StoragePort persistedStorage = mapBackedStorage(persistedFiles);
        SelfEvolvingRunService readerService = new SelfEvolvingRunService(new JsonRunJournalAdapter(persistedStorage),
                artifactBundleService,
                clock);

        Optional<RunVerdict> storedVerdict = readerService.findVerdict("run-legacy");

        assertTrue(storedVerdict.isPresent());
        assertEquals("verdict-legacy", storedVerdict.get().getId());
        assertEquals("COMPLETED", storedVerdict.get().getOutcomeStatus());
    }

    @Test
    void shouldCopyAppliedTacticIdsFromContextOnRunCompletion() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-applied").metadata(Map.of()).build())
                .turnOutcome(TurnOutcome.builder().finishReason(FinishReason.SUCCESS).build())
                .build();
        context.setAttribute(ContextAttributes.APPLIED_TACTIC_IDS, List.of("tactic-a", "tactic-b"));
        RunRecord run = RunRecord.builder()
                .id("run-applied")
                .artifactBundleId("bundle-applied")
                .status("RUNNING")
                .build();
        when(artifactBundleService.refresh("bundle-applied", context)).thenReturn(ArtifactBundleRecord.builder()
                .id("bundle-applied")
                .build());

        RunRecord completed = service.completeRun(run, context);

        assertEquals(List.of("tactic-a", "tactic-b"), completed.getAppliedTacticIds());
    }

    @Test
    void shouldSerializeAndDeserializeAppliedTacticIds() throws Exception {
        RunRecord run = RunRecord.builder()
                .id("run-json")
                .golemId("golem-json")
                .artifactBundleId("bundle-json")
                .appliedTacticIds(List.of("tactic-x", "tactic-y"))
                .build();

        String json = objectMapper.writeValueAsString(run);
        RunRecord restored = objectMapper.readValue(json, RunRecord.class);

        assertEquals(List.of("tactic-x", "tactic-y"), restored.getAppliedTacticIds());
    }

    @Test
    void shouldReturnNullWhenCompletingMissingRun() {
        assertNull(service.completeRun(null, AgentContext.builder().build()));
    }

    @Test
    void shouldFallbackToLocalSessionGolemIdWhenHiveMetadataIsMissing() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-local").metadata(Map.of()).build())
                .build();
        when(artifactBundleService.snapshot(context)).thenReturn(ArtifactBundleRecord.builder()
                .id("bundle-local")
                .build());

        RunRecord run = service.startRun(context);

        assertEquals("local-session-local", run.getGolemId());
    }

    @Test
    void shouldFallbackToLocalGolemWhenSessionIsMissing() {
        AgentContext context = AgentContext.builder().build();
        when(artifactBundleService.snapshot(context)).thenReturn(ArtifactBundleRecord.builder()
                .id("bundle-global")
                .build());

        RunRecord run = service.startRun(context);

        assertEquals("local-golem", run.getGolemId());
        assertEquals("bundle-global", run.getArtifactBundleId());
    }

    @Test
    void shouldFindCorrectRunAmongMultipleCachedRuns() {
        // Guards the findRun lambda against a mutation that returns true for
        // every candidate — with multiple persisted runs, such a mutant would
        // return the wrong element.
        Map<String, String> persistedFiles = new ConcurrentHashMap<>();
        StoragePort persistedStorage = mapBackedStorage(persistedFiles);
        SelfEvolvingRunService persistedService = new SelfEvolvingRunService(
                new JsonRunJournalAdapter(persistedStorage), artifactBundleService, clock);

        AgentContext ctxA = AgentContext.builder()
                .session(AgentSession.builder().id("session-A").metadata(Map.of()).build())
                .build();
        AgentContext ctxB = AgentContext.builder()
                .session(AgentSession.builder().id("session-B").metadata(Map.of()).build())
                .build();
        when(artifactBundleService.snapshot(ctxA)).thenReturn(ArtifactBundleRecord.builder().id("bA").build());
        when(artifactBundleService.snapshot(ctxB)).thenReturn(ArtifactBundleRecord.builder().id("bB").build());

        RunRecord runA = persistedService.startRun(ctxA);
        RunRecord runB = persistedService.startRun(ctxB);

        Optional<RunRecord> found = persistedService.findRun(runB.getId());
        assertTrue(found.isPresent());
        assertEquals(runB.getId(), found.get().getId());
        assertEquals("local-session-B", found.get().getGolemId());
        // Also confirm run A is still reachable and not overwritten.
        assertEquals(runA.getId(), persistedService.findRun(runA.getId()).get().getId());
    }

    @Test
    void shouldReturnMutableListForAppliedTacticIdsEvenWhenAttributeMissing() {
        // Kills the mutant that replaces `new ArrayList<>()` with
        // `Collections.emptyList()` in resolveAppliedTacticIds — the empty-list
        // singleton would reject the add() call below.
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-mut").metadata(Map.of()).build())
                .turnOutcome(TurnOutcome.builder().finishReason(FinishReason.SUCCESS).build())
                .build();
        RunRecord run = RunRecord.builder()
                .id("run-mut")
                .artifactBundleId("bundle-mut")
                .status("RUNNING")
                .build();
        when(artifactBundleService.refresh("bundle-mut", context))
                .thenReturn(ArtifactBundleRecord.builder().id("bundle-mut").build());

        RunRecord completed = service.completeRun(run, context);

        assertNotNull(completed.getAppliedTacticIds());
        assertTrue(completed.getAppliedTacticIds().isEmpty());
        completed.getAppliedTacticIds().add("post-hoc");
        assertEquals(List.of("post-hoc"), completed.getAppliedTacticIds());
    }

    @Test
    void shouldAssignRunIdToVerdictWhenRunIdIsMissingOnPayload() {
        // saveVerdict must stamp the runId on the incoming verdict, even if
        // the caller passed one without the field populated. Guards the
        // removed-call-to-setRunId mutation.
        Map<String, String> persistedFiles = new ConcurrentHashMap<>();
        StoragePort persistedStorage = mapBackedStorage(persistedFiles);
        SelfEvolvingRunService writerService = new SelfEvolvingRunService(
                new JsonRunJournalAdapter(persistedStorage), artifactBundleService, clock);
        RunVerdict verdictWithoutRunId = RunVerdict.builder()
                .id("verdict-no-id")
                .outcomeStatus("COMPLETED")
                .createdAt(FIXED_INSTANT)
                .build();

        writerService.saveVerdict("run-stamped", verdictWithoutRunId);

        assertEquals("run-stamped", verdictWithoutRunId.getRunId());
        Optional<RunVerdict> stored = writerService.findVerdict("run-stamped");
        assertTrue(stored.isPresent());
        assertEquals("run-stamped", stored.get().getRunId());
    }

    @Test
    void shouldCacheRunsAfterFirstLoad() {
        // getRuns() must populate the in-memory cache on first call so that
        // subsequent calls do not hit the journal port again. Kills the
        // removed-call-to-runCache::set mutant.
        RunJournalPort mockJournal = mock(RunJournalPort.class);
        when(mockJournal.loadRuns()).thenReturn(new java.util.ArrayList<>(List.of(
                RunRecord.builder().id("r-1").build())));
        SelfEvolvingRunService cachedService = new SelfEvolvingRunService(mockJournal, artifactBundleService, clock);

        cachedService.getRuns();
        cachedService.getRuns();
        cachedService.getRuns();

        verify(mockJournal, org.mockito.Mockito.times(1)).loadRuns();
    }

    @Test
    void shouldCacheVerdictsAfterFirstLoad() {
        // Same caching contract for the verdict map — guards against
        // removed-call-to-verdictCache::set on the getVerdicts() lazy-init
        // path.
        RunJournalPort mockJournal = mock(RunJournalPort.class);
        when(mockJournal.loadRuns()).thenReturn(new java.util.ArrayList<>());
        when(mockJournal.loadVerdicts()).thenReturn(new java.util.LinkedHashMap<>(Map.of(
                "run-v", RunVerdict.builder().id("v").runId("run-v").build())));
        SelfEvolvingRunService cachedService = new SelfEvolvingRunService(mockJournal, artifactBundleService, clock);

        cachedService.findVerdict("run-v");
        cachedService.findVerdict("run-v");
        cachedService.findVerdict("run-v");

        verify(mockJournal, org.mockito.Mockito.times(1)).loadVerdicts();
    }

    @Test
    void shouldRefreshVerdictCacheAfterSave() {
        // Guards the removed-call-to-verdictCache::set mutation inside
        // saveVerdict: after a save, a subsequent findVerdict must see the
        // new entry without touching the journal a second time.
        RunJournalPort mockJournal = mock(RunJournalPort.class);
        when(mockJournal.loadRuns()).thenReturn(new java.util.ArrayList<>());
        when(mockJournal.loadVerdicts()).thenReturn(new java.util.LinkedHashMap<>());
        SelfEvolvingRunService cachedService = new SelfEvolvingRunService(mockJournal, artifactBundleService, clock);

        cachedService.saveVerdict("run-fresh",
                RunVerdict.builder().id("v-fresh").outcomeStatus("COMPLETED").build());

        Optional<RunVerdict> found = cachedService.findVerdict("run-fresh");
        assertTrue(found.isPresent());
        // loadVerdicts is called at most once — subsequent reads must come
        // from the cache populated by saveVerdict.
        verify(mockJournal, org.mockito.Mockito.atMostOnce()).loadVerdicts();
    }

    @Test
    void shouldPersistRunFromCompleteRunViaSave() {
        // Guards against removed-call-to-save on completeRun: after
        // completion, the updated status must be visible through findRun
        // (which reads the cached/persisted state).
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-persist").metadata(Map.of()).build())
                .turnOutcome(TurnOutcome.builder().finishReason(FinishReason.SUCCESS).build())
                .build();
        when(artifactBundleService.snapshot(context))
                .thenReturn(ArtifactBundleRecord.builder().id("bundle-persist").build());
        when(artifactBundleService.refresh("bundle-persist", context))
                .thenReturn(ArtifactBundleRecord.builder().id("bundle-persist-2").build());

        RunRecord started = service.startRun(context);
        service.completeRun(started, context);

        Optional<RunRecord> found = service.findRun(started.getId());
        assertTrue(found.isPresent());
        assertEquals("COMPLETED", found.get().getStatus());
        assertEquals("bundle-persist-2", found.get().getArtifactBundleId());
    }

    @Test
    void shouldLoadRunsAsEmptyWhenStoredJsonIsInvalid() {
        StoragePort brokenStorage = mock(StoragePort.class);
        when(brokenStorage.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture("{"));
        when(brokenStorage.putTextAtomic(eq("self-evolving"), eq("runs.json"), anyString(), eq(true)))
                .thenReturn(CompletableFuture.completedFuture(null));
        SelfEvolvingRunService brokenService = new SelfEvolvingRunService(new JsonRunJournalAdapter(brokenStorage),
                artifactBundleService, clock);

        assertTrue(brokenService.getRuns().isEmpty());
    }

    private StoragePort mapBackedStorage(Map<String, String> persistedFiles) {
        StoragePort persistedStorage = mock(StoragePort.class);
        when(persistedStorage.getText(anyString(), anyString())).thenAnswer(invocation -> {
            String fileName = invocation.getArgument(1);
            return CompletableFuture.completedFuture(persistedFiles.get(fileName));
        });
        when(persistedStorage.putTextAtomic(eq("self-evolving"), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    String fileName = invocation.getArgument(1);
                    String content = invocation.getArgument(2);
                    persistedFiles.put(fileName, content);
                    return CompletableFuture.completedFuture(null);
                });
        return persistedStorage;
    }
}
