package me.golemcore.bot.domain.selfevolving.promotion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.EvolutionProposal;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import me.golemcore.bot.adapter.outbound.selfevolving.JsonPromotionWorkflowStateAdapter;
import me.golemcore.bot.domain.selfevolving.artifact.ArtifactBundleService;
import me.golemcore.bot.domain.selfevolving.artifact.EvolutionArtifactIdentityService;
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionCandidateDerivationService;
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionCandidateService;
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionCandidateTacticMaterializer;
import me.golemcore.bot.domain.selfevolving.tactic.InMemoryTacticRecordStorePort;
import me.golemcore.bot.domain.selfevolving.tactic.TacticRecordService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.adapter.outbound.selfevolving.JsonArtifactRepositoryAdapter;

class PromotionWorkflowServiceTest {

    private StoragePort storagePort;
    private RuntimeConfigService runtimeConfigService;
    private EvolutionCandidateService evolutionCandidateService;
    private TacticRecordService tacticRecordService;
    private ArtifactBundleService artifactBundleService;
    private PromotionWorkflowService promotionWorkflowService;
    private Map<String, String> persistedFiles;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        persistedFiles = new ConcurrentHashMap<>();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        when(storagePort.getText(anyString(), anyString())).thenAnswer(invocation -> {
            String fileName = invocation.getArgument(1);
            return CompletableFuture.completedFuture(persistedFiles.get(fileName));
        });
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String fileName = invocation.getArgument(1);
                    String content = invocation.getArgument(2);
                    persistedFiles.put(fileName, content);
                    return CompletableFuture.completedFuture(null);
                });
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    String fileName = invocation.getArgument(1);
                    String content = invocation.getArgument(2);
                    persistedFiles.put(fileName, content);
                    return CompletableFuture.completedFuture(null);
                });
        when(storagePort.listObjects(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String prefix = invocation.getArgument(1);
                    List<String> keys = persistedFiles.keySet().stream()
                            .filter(path -> path.startsWith(prefix))
                            .sorted()
                            .toList();
                    return CompletableFuture.completedFuture(keys);
                });
        when(runtimeConfigService.getSelfEvolvingPromotionMode()).thenReturn("approval_gate");
        when(runtimeConfigService.isSelfEvolvingPromotionShadowRequired()).thenReturn(false);
        when(runtimeConfigService.isSelfEvolvingPromotionCanaryRequired()).thenReturn(false);
        tacticRecordService = new TacticRecordService(new InMemoryTacticRecordStorePort(),
                Clock.fixed(Instant.parse("2026-03-31T16:00:00Z"), ZoneOffset.UTC), null, null);
        artifactBundleService = new ArtifactBundleService(new JsonArtifactRepositoryAdapter(storagePort),
                runtimeConfigService,
                Clock.fixed(Instant.parse("2026-03-31T16:00:00Z"), ZoneOffset.UTC));
        Clock clock = Clock.fixed(Instant.parse("2026-03-31T16:00:00Z"), ZoneOffset.UTC);
        evolutionCandidateService = new EvolutionCandidateService(
                tacticRecordService,
                artifactBundleService,
                new EvolutionArtifactIdentityService(new JsonArtifactRepositoryAdapter(storagePort), clock),
                new EvolutionCandidateDerivationService(clock),
                new EvolutionCandidateTacticMaterializer(clock));
        PromotionWorkflowStateService promotionWorkflowStateService = new PromotionWorkflowStateService(
                new PromotionWorkflowStore(new JsonPromotionWorkflowStateAdapter(storagePort)),
                evolutionCandidateService,
                new PromotionDecisionHydrationService());
        promotionWorkflowService = new PromotionWorkflowService(
                runtimeConfigService,
                promotionWorkflowStateService,
                new PromotionTargetResolver(runtimeConfigService),
                new PromotionExecutionService(artifactBundleService, clock),
                artifactBundleService);
    }

    @Test
    void shouldKeepCandidateProposedWhenApprovalGateRegistersAutomatically() {
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-1")
                .golemId("golem-1")
                .goal("derive")
                .artifactType("skill")
                .status("proposed")
                .build();

        List<PromotionDecision> decisions = promotionWorkflowService.registerAndPlanCandidates(List.of(candidate));

        assertTrue(decisions.isEmpty());
        EvolutionCandidate storedCandidate = promotionWorkflowService.getCandidates().getFirst();
        assertEquals("proposed", storedCandidate.getStatus());
        assertFalse(persistedFiles.containsKey("tactics/" + storedCandidate.getContentRevisionId() + ".json"));
    }

    @Test
    void shouldAutoAcceptCandidateIntoActiveTacticWhenConfigured() {
        when(runtimeConfigService.getSelfEvolvingPromotionMode()).thenReturn("auto_accept");
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-2")
                .golemId("golem-1")
                .goal("fix")
                .artifactType("tool_policy")
                .status("proposed")
                .baseVersion("bundle-2")
                .build();

        PromotionDecision decision = promotionWorkflowService.registerAndPlanCandidates(List.of(candidate)).getFirst();

        assertEquals("active", decision.getState());
        assertEquals("active", promotionWorkflowService.getCandidates().getFirst().getStatus());
        assertEquals("candidate-2:active", decision.getBundleId());
        assertEquals("bundle-2", decision.getOriginBundleId());
        assertFalse(promotionWorkflowService.getPromotionDecisions().isEmpty());
    }

    @Test
    void shouldActivateCandidateWhenManualPromotionIsRequestedInApprovalGateMode() {
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-manual")
                .golemId("golem-1")
                .goal("fix")
                .artifactType("tool_policy")
                .status("proposed")
                .baseVersion("bundle-manual")
                .build();
        promotionWorkflowService.registerCandidates(List.of(candidate));

        PromotionDecision decision = promotionWorkflowService.planPromotion("candidate-manual");

        assertEquals("active", decision.getState());
        assertEquals("approval_gate", decision.getMode());
        assertEquals("active", promotionWorkflowService.findCandidate("candidate-manual").orElseThrow().getStatus());
    }

    @Test
    void shouldAdvanceCandidateThroughShadowCanaryAndActiveWhenRolloutStagesAreRequired() {
        when(runtimeConfigService.isSelfEvolvingPromotionShadowRequired()).thenReturn(true);
        when(runtimeConfigService.isSelfEvolvingPromotionCanaryRequired()).thenReturn(true);
        artifactBundleService.save(ArtifactBundleRecord.builder()
                .id("bundle-rollout")
                .status("snapshot")
                .artifactRevisionBindings(Map.of("stream-1", "rev-1"))
                .createdAt(Instant.parse("2026-03-31T15:55:00Z"))
                .build());
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-rollout")
                .golemId("golem-1")
                .goal("fix")
                .artifactType("tool_policy")
                .artifactSubtype("tool_policy:usage")
                .artifactStreamId("stream-1")
                .originArtifactStreamId("stream-1")
                .artifactKey("tool_policy:usage")
                .contentRevisionId("rev-2")
                .baseContentRevisionId("rev-1")
                .baseVersion("bundle-rollout")
                .proposedDiff("Verify tool availability before shell execution.")
                .status("proposed")
                .build();
        promotionWorkflowService.registerCandidates(List.of(candidate));

        PromotionDecision shadowDecision = promotionWorkflowService.planPromotion("candidate-rollout");
        PromotionDecision canaryDecision = promotionWorkflowService.planPromotion("candidate-rollout");
        PromotionDecision activeDecision = promotionWorkflowService.planPromotion("candidate-rollout");

        assertEquals("shadowed", shadowDecision.getState());
        assertEquals("candidate", shadowDecision.getToLifecycleState());
        assertEquals("shadowed", shadowDecision.getToRolloutStage());
        assertEquals("canary", canaryDecision.getState());
        assertEquals("candidate", canaryDecision.getToLifecycleState());
        assertEquals("canary", canaryDecision.getToRolloutStage());
        assertEquals("active", activeDecision.getState());
        assertEquals("active", activeDecision.getToLifecycleState());
        assertEquals("active", activeDecision.getToRolloutStage());
        assertEquals("active", promotionWorkflowService.findCandidate("candidate-rollout").orElseThrow().getStatus());
        assertEquals("shadowed", artifactBundleService.getBundles().stream()
                .filter(bundle -> "candidate-rollout:shadowed".equals(bundle.getId()))
                .findFirst()
                .orElseThrow()
                .getStatus());
        assertEquals("canary", artifactBundleService.getBundles().stream()
                .filter(bundle -> "candidate-rollout:canary".equals(bundle.getId()))
                .findFirst()
                .orElseThrow()
                .getStatus());
        ArtifactBundleRecord activeBundle = artifactBundleService.getBundles().stream()
                .filter(bundle -> "candidate-rollout:active".equals(bundle.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("active", activeBundle.getStatus());
        assertEquals("rev-2", activeBundle.getArtifactRevisionBindings().get("stream-1"));
    }

    @Test
    void shouldCreateAndRefreshTacticRecordsForRetrievableCandidatesBeforeActivation() {
        when(runtimeConfigService.isSelfEvolvingPromotionShadowRequired()).thenReturn(true);
        when(runtimeConfigService.isSelfEvolvingPromotionCanaryRequired()).thenReturn(true);
        artifactBundleService.save(ArtifactBundleRecord.builder()
                .id("bundle-tactic")
                .status("snapshot")
                .artifactRevisionBindings(Map.of("stream-tactic", "rev-base"))
                .createdAt(Instant.parse("2026-03-31T15:55:00Z"))
                .build());
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-tactic")
                .golemId("golem-1")
                .goal("derive")
                .artifactType("skill")
                .artifactStreamId("stream-tactic")
                .originArtifactStreamId("stream-tactic")
                .artifactKey("skill:planner")
                .contentRevisionId("rev-tactic")
                .baseContentRevisionId("rev-base")
                .baseVersion("bundle-tactic")
                .proposedDiff("Reuse planning checkpoints before executing shell tools.")
                .status("proposed")
                .proposal(EvolutionProposal.builder()
                        .summary("Capture planner tactic")
                        .behaviorInstructions("Plan the work before tool execution.")
                        .toolInstructions("Prefer shell only after the plan is explicit.")
                        .expectedOutcome("More reliable multi-step execution.")
                        .build())
                .build();

        promotionWorkflowService.registerCandidates(List.of(candidate));

        TacticRecord proposed = tacticRecordService.getById("rev-tactic").orElseThrow();
        assertEquals("candidate", proposed.getPromotionState());
        assertEquals("proposed", proposed.getRolloutStage());

        promotionWorkflowService.planPromotion("candidate-tactic");
        TacticRecord shadowed = tacticRecordService.getById("rev-tactic").orElseThrow();
        assertEquals("candidate", shadowed.getPromotionState());
        assertEquals("shadowed", shadowed.getRolloutStage());

        promotionWorkflowService.planPromotion("candidate-tactic");
        TacticRecord canary = tacticRecordService.getById("rev-tactic").orElseThrow();
        assertEquals("candidate", canary.getPromotionState());
        assertEquals("canary", canary.getRolloutStage());

        promotionWorkflowService.planPromotion("candidate-tactic");
        TacticRecord active = tacticRecordService.getById("rev-tactic").orElseThrow();
        assertEquals("active", active.getPromotionState());
        assertEquals("active", active.getRolloutStage());
    }

    @Test
    void shouldRegisterCandidatesBeforePlanning() {
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-3")
                .golemId("golem-1")
                .goal("fix")
                .artifactType("prompt")
                .status("proposed")
                .build();

        List<EvolutionCandidate> registered = promotionWorkflowService.registerCandidates(List.of(candidate));

        assertEquals(1, registered.size());
        assertEquals("candidate-3", promotionWorkflowService.getCandidates().getFirst().getId());
    }

    @Test
    void shouldReturnEmptyCollectionsWhenRegisteringNullCandidates() {
        assertTrue(promotionWorkflowService.registerCandidates(null).isEmpty());
        assertTrue(promotionWorkflowService.registerAndPlanCandidates(null).isEmpty());
        assertTrue(promotionWorkflowService.getCandidates().isEmpty());
        assertTrue(promotionWorkflowService.getPromotionDecisions().isEmpty());
    }

    @Test
    void shouldSkipBlankCandidateIdsWhenRegisteringCandidates() {
        EvolutionCandidate blankCandidate = EvolutionCandidate.builder()
                .id("   ")
                .golemId("golem-1")
                .goal("fix")
                .artifactType("prompt")
                .status("proposed")
                .build();
        EvolutionCandidate validCandidate = EvolutionCandidate.builder()
                .id("candidate-3b")
                .golemId("golem-1")
                .goal("fix")
                .artifactType("prompt")
                .status("proposed")
                .build();

        List<EvolutionCandidate> registered = promotionWorkflowService
                .registerCandidates(List.of(blankCandidate, validCandidate));

        assertEquals(1, registered.size());
        assertEquals("candidate-3b", registered.getFirst().getId());
        assertEquals(1, promotionWorkflowService.getCandidates().size());
        assertEquals("candidate-3b", promotionWorkflowService.getCandidates().getFirst().getId());
    }

    @Test
    void shouldPlanPromotionByCandidateIdAfterRegisteringCandidate() {
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-4")
                .golemId("golem-1")
                .goal("tune")
                .artifactType("routing policy")
                .status("proposed")
                .baseVersion("bundle-4")
                .build();
        promotionWorkflowService.registerCandidates(List.of(candidate));

        PromotionDecision decision = promotionWorkflowService.planPromotion("candidate-4");

        assertEquals("candidate-4", decision.getCandidateId());
        assertEquals("routing_policy", decision.getArtifactType());
        assertEquals("routing_policy:tier", decision.getArtifactSubtype());
        assertEquals("routing_policy:tier", decision.getArtifactKey());
        assertEquals("candidate", decision.getFromLifecycleState());
        assertEquals("active", decision.getToLifecycleState());
        assertEquals("candidate-4:active", decision.getBundleId());
    }

    @Test
    void shouldNormalizeLoadedCandidatesAndPersistBackfilledArtifactIdentity() throws Exception {
        EvolutionCandidate legacyCandidate = EvolutionCandidate.builder()
                .id("candidate-legacy")
                .golemId("golem-1")
                .goal("derive")
                .artifactType("prompt section")
                .status("approved_pending")
                .build();
        persistedFiles.put("candidates.json", objectMapper.writeValueAsString(List.of(legacyCandidate)));

        List<EvolutionCandidate> candidates = promotionWorkflowService.getCandidates();

        assertEquals(1, candidates.size());
        EvolutionCandidate normalized = candidates.getFirst();
        assertEquals("prompt", normalized.getArtifactType());
        assertEquals("prompt:section", normalized.getArtifactSubtype());
        assertEquals("prompt:section", normalized.getArtifactKey());
        assertEquals("approved", normalized.getLifecycleState());
        assertEquals("approved", normalized.getRolloutStage());
        assertNotNull(normalized.getArtifactStreamId());
        assertNotNull(normalized.getContentRevisionId());
        assertTrue(persistedFiles.get("candidates.json").contains("\"artifactType\":\"prompt\""));
        assertTrue(persistedFiles.get("candidates.json").contains("\"contentRevisionId\""));
    }

    @Test
    void shouldHydrateStoredDecisionsFromStoredCandidatesAndPersistNormalizedDecision() throws Exception {
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-5")
                .golemId("golem-1")
                .goal("fix")
                .artifactType("routing policy")
                .status("shadowed")
                .baseVersion("bundle-5")
                .build();
        PromotionDecision legacyDecision = PromotionDecision.builder()
                .id("decision-1")
                .candidateId("candidate-5")
                .toState("approved_pending")
                .build();
        persistedFiles.put("candidates.json", objectMapper.writeValueAsString(List.of(candidate)));
        persistedFiles.put("promotion-decisions.json", objectMapper.writeValueAsString(List.of(legacyDecision)));

        List<PromotionDecision> decisions = promotionWorkflowService.getPromotionDecisions();

        assertEquals(1, decisions.size());
        PromotionDecision hydrated = decisions.getFirst();
        assertEquals("routing_policy", hydrated.getArtifactType());
        assertEquals("routing_policy:tier", hydrated.getArtifactSubtype());
        assertEquals("routing_policy:tier", hydrated.getArtifactKey());
        assertEquals("approved", hydrated.getToLifecycleState());
        assertEquals("approved", hydrated.getToRolloutStage());
        assertEquals("candidate", hydrated.getFromLifecycleState());
        assertEquals("shadowed", hydrated.getFromRolloutStage());
        assertEquals("candidate-5:approved", hydrated.getBundleId());
        assertTrue(persistedFiles.get("promotion-decisions.json").contains("\"artifactKey\":\"routing_policy:tier\""));
    }

    @Test
    void shouldReturnEmptyCollectionsWhenStoredWorkflowJsonIsInvalid() {
        persistedFiles.put("candidates.json", "{");
        persistedFiles.put("promotion-decisions.json", "{");

        assertTrue(promotionWorkflowService.getCandidates().isEmpty());
        assertTrue(promotionWorkflowService.getPromotionDecisions().isEmpty());
    }

    @Test
    void shouldSkipNullCandidatesWhenRegisteringAndPlanning() {
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-6")
                .golemId("golem-1")
                .goal("fix")
                .artifactType("tool_policy")
                .status("proposed")
                .build();
        List<EvolutionCandidate> candidates = new ArrayList<>();
        candidates.add(null);
        candidates.add(candidate);

        List<PromotionDecision> decisions = promotionWorkflowService.registerAndPlanCandidates(candidates);

        assertTrue(decisions.isEmpty());
        assertEquals("candidate-6", promotionWorkflowService.getCandidates().getFirst().getId());
    }

    @Test
    void shouldThrowWhenPlanningUnknownCandidateId() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> promotionWorkflowService.planPromotion("missing"));

        assertEquals("Candidate not found: missing", exception.getMessage());
    }

    @Test
    void shouldThrowWhenPromotionModeIsUnsupported() {
        when(runtimeConfigService.getSelfEvolvingPromotionMode()).thenReturn("invalid_mode");
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-7")
                .golemId("golem-1")
                .goal("fix")
                .artifactType("prompt")
                .status("proposed")
                .build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> promotionWorkflowService.planPromotion(candidate));

        assertTrue(exception.getMessage().contains("Unsupported promotion mode"));
    }

    @Test
    void shouldThrowWhenPlanningBlankCandidate() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> promotionWorkflowService.planPromotion(EvolutionCandidate.builder().id(" ").build()));

        assertEquals("Candidate must not be blank", exception.getMessage());
    }

    @Test
    void shouldSkipApprovalRequestWhenApproveImmediatelyActivatesTactic() {
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-8")
                .golemId("golem-1")
                .goal("derive")
                .artifactType("skill")
                .status("proposed")
                .build();

        PromotionDecision approvalDecision = promotionWorkflowService.planPromotion(candidate);
        when(runtimeConfigService.getSelfEvolvingPromotionMode()).thenReturn("auto_accept");
        PromotionDecision shadowDecision = promotionWorkflowService.planPromotion(EvolutionCandidate.builder()
                .id("candidate-9")
                .golemId("golem-1")
                .goal("derive")
                .artifactType("skill")
                .status("proposed")
                .baseVersion("bundle-9")
                .build());

        assertEquals(null, approvalDecision.getApprovalRequestId());
        assertEquals(null, shadowDecision.getApprovalRequestId());
        assertEquals("candidate-9:active", shadowDecision.getBundleId());
    }

    @Test
    void shouldHydrateLegacyDecisionTargetsFromLifecycleAndRolloutFallbacks() throws Exception {
        List<EvolutionCandidate> candidates = List.of(
                candidate("candidate-active", "bundle-active"),
                candidate("candidate-reverted", "bundle-reverted"),
                candidate("candidate-approved", "bundle-approved"),
                candidate("candidate-canary", "bundle-canary"),
                candidate("candidate-replayed", "bundle-replayed"),
                candidate("candidate-default", "bundle-default"));
        List<PromotionDecision> decisions = List.of(
                PromotionDecision.builder().id("decision-active").candidateId("candidate-active").toState("active")
                        .build(),
                PromotionDecision.builder().id("decision-reverted").candidateId("candidate-reverted")
                        .toState("reverted")
                        .build(),
                PromotionDecision.builder().id("decision-approved").candidateId("candidate-approved")
                        .toLifecycleState("approved").build(),
                PromotionDecision.builder().id("decision-canary").candidateId("candidate-canary")
                        .toRolloutStage("canary")
                        .build(),
                PromotionDecision.builder().id("decision-replayed").candidateId("candidate-replayed")
                        .toRolloutStage("replayed").build(),
                PromotionDecision.builder().id("decision-default").candidateId("candidate-default").build());
        persistedFiles.put("candidates.json", objectMapper.writeValueAsString(candidates));
        persistedFiles.put("promotion-decisions.json", objectMapper.writeValueAsString(decisions));

        List<PromotionDecision> hydratedDecisions = promotionWorkflowService.getPromotionDecisions();

        assertEquals("active", findDecision(hydratedDecisions, "decision-active").getToLifecycleState());
        assertEquals("active", findDecision(hydratedDecisions, "decision-active").getToRolloutStage());
        assertEquals("candidate-active:active", findDecision(hydratedDecisions, "decision-active").getBundleId());

        assertEquals("reverted", findDecision(hydratedDecisions, "decision-reverted").getToLifecycleState());
        assertEquals("reverted", findDecision(hydratedDecisions, "decision-reverted").getToRolloutStage());

        assertEquals("approved", findDecision(hydratedDecisions, "decision-approved").getToLifecycleState());
        assertEquals("proposed", findDecision(hydratedDecisions, "decision-approved").getToRolloutStage());
        assertEquals("candidate-approved:proposed", findDecision(hydratedDecisions, "decision-approved").getBundleId());

        assertEquals("candidate", findDecision(hydratedDecisions, "decision-canary").getToLifecycleState());
        assertEquals("canary", findDecision(hydratedDecisions, "decision-canary").getToRolloutStage());
        assertEquals("candidate-canary:canary", findDecision(hydratedDecisions, "decision-canary").getBundleId());

        assertEquals("candidate", findDecision(hydratedDecisions, "decision-replayed").getToLifecycleState());
        assertEquals("replayed", findDecision(hydratedDecisions, "decision-replayed").getToRolloutStage());
        assertEquals("candidate-replayed:replayed", findDecision(hydratedDecisions, "decision-replayed").getBundleId());

        assertEquals("candidate", findDecision(hydratedDecisions, "decision-default").getToLifecycleState());
        assertEquals("proposed", findDecision(hydratedDecisions, "decision-default").getToRolloutStage());
        assertEquals("candidate-default:proposed", findDecision(hydratedDecisions, "decision-default").getBundleId());
    }

    @Test
    void shouldBindCandidateBaseRevisionsWhenArtifactBundleServiceIsPresent() {
        ArtifactBundleService artifactBundleService = mock(ArtifactBundleService.class);
        PromotionWorkflowStateService promotionWorkflowStateService = new PromotionWorkflowStateService(
                new PromotionWorkflowStore(new InMemoryPromotionWorkflowStatePort()),
                evolutionCandidateService,
                new PromotionDecisionHydrationService());
        PromotionWorkflowService serviceWithBundleBinding = new PromotionWorkflowService(
                runtimeConfigService,
                promotionWorkflowStateService,
                new PromotionTargetResolver(runtimeConfigService),
                new PromotionExecutionService(artifactBundleService,
                        Clock.fixed(Instant.parse("2026-03-31T16:00:00Z"), ZoneOffset.UTC)),
                artifactBundleService);
        List<EvolutionCandidate> candidates = List.of(candidate("candidate-10", "bundle-10"));

        serviceWithBundleBinding.bindCandidateBaseRevisions("bundle-10", candidates);

        verify(artifactBundleService).bindBaseRevisions("bundle-10", candidates);
    }

    private EvolutionCandidate candidate(String id, String baseVersion) {
        return EvolutionCandidate.builder()
                .id(id)
                .golemId("golem-1")
                .goal("fix")
                .artifactType("prompt")
                .status("proposed")
                .baseVersion(baseVersion)
                .build();
    }

    private PromotionDecision findDecision(List<PromotionDecision> decisions, String decisionId) {
        return decisions.stream()
                .filter(decision -> decisionId.equals(decision.getId()))
                .findFirst()
                .orElseThrow();
    }
}
