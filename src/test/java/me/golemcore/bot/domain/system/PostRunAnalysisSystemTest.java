package me.golemcore.bot.domain.system;

import me.golemcore.bot.port.outbound.SelfEvolvingProjectionPublishPort;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.EvolutionProposal;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.selfevolving.benchmark.DeterministicJudgeService;
import me.golemcore.bot.domain.selfevolving.benchmark.LlmJudgeService;
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionCandidateService;
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionGateService;
import me.golemcore.bot.domain.selfevolving.candidate.LlmEvolutionService;
import me.golemcore.bot.domain.selfevolving.promotion.PromotionWorkflowService;
import me.golemcore.bot.domain.selfevolving.tactic.TacticOutcomeJournalService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.selfevolving.run.SelfEvolvingRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostRunAnalysisSystemTest {

    private RuntimeConfigService runtimeConfigService;
    private SelfEvolvingRunService selfEvolvingRunService;
    private DeterministicJudgeService deterministicJudgeService;
    private LlmJudgeService llmJudgeService;
    private LlmEvolutionService llmEvolutionService;
    private EvolutionCandidateService evolutionCandidateService;
    private EvolutionGateService evolutionGateService;
    private PromotionWorkflowService promotionWorkflowService;
    private TacticOutcomeJournalService tacticOutcomeJournalService;
    private SelfEvolvingProjectionPublishPort projectionPublishPort;
    private PostRunAnalysisSystem system;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        selfEvolvingRunService = mock(SelfEvolvingRunService.class);
        deterministicJudgeService = mock(DeterministicJudgeService.class);
        llmJudgeService = mock(LlmJudgeService.class);
        llmEvolutionService = mock(LlmEvolutionService.class);
        evolutionCandidateService = mock(EvolutionCandidateService.class);
        evolutionGateService = new EvolutionGateService();
        promotionWorkflowService = mock(PromotionWorkflowService.class);
        tacticOutcomeJournalService = mock(TacticOutcomeJournalService.class);
        projectionPublishPort = mock(SelfEvolvingProjectionPublishPort.class);
        system = new PostRunAnalysisSystem(
                runtimeConfigService,
                selfEvolvingRunService,
                deterministicJudgeService,
                llmJudgeService,
                llmEvolutionService,
                evolutionCandidateService,
                evolutionGateService,
                promotionWorkflowService,
                tacticOutcomeJournalService,
                projectionPublishPort);
        when(runtimeConfigService.getSelfEvolvingPromotionMode()).thenReturn("approval_gate");
    }

    @Test
    void shouldNotProcessWhenSelfEvolvingDisabled() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(false);

        assertFalse(system.shouldProcess(buildContext()));
    }

    @Test
    void shouldExposeSystemMetadata() {
        assertEquals("PostRunAnalysisSystem", system.getName());
        assertEquals(58, system.getOrder());
    }

    @Test
    void shouldCreateRunRecordWhenTurnOutcomeIsReady() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        AgentContext context = buildContext();
        RunRecord startedRun = RunRecord.builder()
                .id("run-1")
                .artifactBundleId("bundle-1")
                .build();
        RunRecord completedRun = RunRecord.builder()
                .id("run-1")
                .artifactBundleId("bundle-1")
                .traceId("trace-1")
                .status("COMPLETED")
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .build();
        VerdictEvidenceRef evidenceRef = VerdictEvidenceRef.builder()
                .spanId("span-1")
                .outputFragment("planner succeeded")
                .build();
        RunVerdict deterministicVerdict = RunVerdict.builder()
                .runId("run-1")
                .outcomeStatus("COMPLETED")
                .confidence(0.8)
                .evidenceRefs(List.of(evidenceRef))
                .build();
        RunVerdict llmVerdict = RunVerdict.builder()
                .runId("run-1")
                .outcomeStatus("COMPLETED")
                .confidence(0.8)
                .evidenceRefs(List.of(evidenceRef))
                .build();
        EvolutionProposal proposal = EvolutionProposal.builder()
                .summary("Capture the successful planner tactic as reusable guidance")
                .build();
        List<EvolutionCandidate> candidates = List.of(EvolutionCandidate.builder()
                .id("candidate-1")
                .baseVersion("bundle-1")
                .artifactStreamId("stream-1")
                .baseContentRevisionId("rev-1")
                .build());
        when(selfEvolvingRunService.startRun(context)).thenReturn(startedRun);
        when(selfEvolvingRunService.completeRun(startedRun, context)).thenReturn(completedRun);
        when(deterministicJudgeService.evaluate(completedRun, traceRecord)).thenReturn(deterministicVerdict);
        when(llmJudgeService.judge(eq(completedRun), eq(traceRecord), eq(deterministicVerdict), any(), any()))
                .thenReturn(llmVerdict);
        when(llmEvolutionService.propose(completedRun, llmVerdict)).thenReturn(proposal);
        when(evolutionCandidateService.deriveCandidates(completedRun, llmVerdict, proposal)).thenReturn(candidates);

        assertTrue(system.shouldProcess(context));
        AgentContext result = processAndAwait(context);

        verify(selfEvolvingRunService).startRun(context);
        verify(selfEvolvingRunService).completeRun(startedRun, context);
        verify(deterministicJudgeService).evaluate(completedRun, traceRecord);
        verify(llmJudgeService).judge(eq(completedRun), eq(traceRecord), eq(deterministicVerdict), any(), any());
        verify(selfEvolvingRunService).saveVerdict("run-1", llmVerdict);
        verify(llmEvolutionService).propose(completedRun, llmVerdict);
        verify(evolutionCandidateService).deriveCandidates(completedRun, llmVerdict, proposal);
        verify(promotionWorkflowService).registerCandidates(candidates);
        verify(promotionWorkflowService, never()).registerAndPlanCandidates(candidates);
        verify(promotionWorkflowService).bindCandidateBaseRevisions("bundle-1", candidates);
        verify(projectionPublishPort).publishSelfEvolvingProjection(completedRun, llmVerdict, candidates);
        assertEquals("run-1", result.getAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID));
        assertEquals("bundle-1", result.getAttribute(ContextAttributes.SELF_EVOLVING_ARTIFACT_BUNDLE_ID));
    }

    @Test
    void shouldCompleteExistingRunRecordStartedEarlier() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID, "run-1");
        context.setAttribute(ContextAttributes.SELF_EVOLVING_ARTIFACT_BUNDLE_ID, "bundle-1");
        RunRecord existingRun = RunRecord.builder()
                .id("run-1")
                .artifactBundleId("bundle-1")
                .status("RUNNING")
                .build();
        RunRecord completedRun = RunRecord.builder()
                .id("run-1")
                .artifactBundleId("bundle-1")
                .traceId("trace-1")
                .status("COMPLETED")
                .build();
        TraceRecord traceRecord = TraceRecord.builder().traceId("trace-1").build();
        RunVerdict deterministicVerdict = RunVerdict.builder()
                .runId("run-1")
                .outcomeStatus("COMPLETED")
                .build();
        RunVerdict llmVerdict = RunVerdict.builder()
                .runId("run-1")
                .outcomeStatus("COMPLETED")
                .build();
        when(selfEvolvingRunService.findRun("run-1")).thenReturn(java.util.Optional.of(existingRun));
        when(selfEvolvingRunService.completeRun(existingRun, context)).thenReturn(completedRun);
        when(deterministicJudgeService.evaluate(completedRun, traceRecord)).thenReturn(deterministicVerdict);
        when(llmJudgeService.judge(eq(completedRun), eq(traceRecord), eq(deterministicVerdict), any(), any()))
                .thenReturn(llmVerdict);
        when(llmEvolutionService.propose(completedRun, llmVerdict)).thenReturn(null);

        assertTrue(system.shouldProcess(context));
        AgentContext result = processAndAwait(context);

        verify(selfEvolvingRunService, never()).startRun(context);
        verify(selfEvolvingRunService).findRun("run-1");
        verify(selfEvolvingRunService).completeRun(existingRun, context);
        verify(evolutionCandidateService, never()).deriveCandidates(completedRun, llmVerdict, null);
        assertEquals("run-1", result.getAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID));
        assertEquals("bundle-1", result.getAttribute(ContextAttributes.SELF_EVOLVING_ARTIFACT_BUNDLE_ID));
    }

    @Test
    void shouldNotProcessWhenOutcomeMissingOrAnalysisAlreadyCompleted() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        AgentContext withoutOutcome = AgentContext.builder().build();
        AgentContext alreadyCompleted = buildContext();
        alreadyCompleted.setAttribute(ContextAttributes.SELF_EVOLVING_ANALYSIS_COMPLETED, true);

        assertFalse(system.shouldProcess(null));
        assertFalse(system.shouldProcess(withoutOutcome));
        assertFalse(system.shouldProcess(alreadyCompleted));
    }

    @Test
    void shouldReturnContextImmediatelyWhenProcessingIsNotNeeded() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(false);
        AgentContext context = buildContext();

        AgentContext result = processAndAwait(context);

        assertTrue(result == context);
        verify(selfEvolvingRunService, never()).startRun(context);
        verify(selfEvolvingRunService, never()).completeRun(null, context);
    }

    @Test
    void shouldStartNewRunWhenStoredRunIdCannotBeResolved() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID, "missing-run");
        RunRecord startedRun = RunRecord.builder()
                .id("run-2")
                .artifactBundleId("bundle-2")
                .build();
        RunRecord completedRun = RunRecord.builder()
                .id("run-2")
                .artifactBundleId("bundle-2")
                .traceId("trace-1")
                .status("COMPLETED")
                .build();
        TraceRecord traceRecord = TraceRecord.builder().traceId("trace-1").build();
        RunVerdict deterministicVerdict = RunVerdict.builder().runId("run-2").build();
        RunVerdict llmVerdict = RunVerdict.builder().runId("run-2").build();
        when(selfEvolvingRunService.findRun("missing-run")).thenReturn(Optional.empty());
        when(selfEvolvingRunService.startRun(context)).thenReturn(startedRun);
        when(selfEvolvingRunService.completeRun(startedRun, context)).thenReturn(completedRun);
        when(deterministicJudgeService.evaluate(completedRun, traceRecord)).thenReturn(deterministicVerdict);
        when(llmJudgeService.judge(eq(completedRun), eq(traceRecord), eq(deterministicVerdict), any(), any()))
                .thenReturn(llmVerdict);
        when(llmEvolutionService.propose(completedRun, llmVerdict)).thenReturn(null);

        AgentContext result = processAndAwait(context);

        verify(selfEvolvingRunService).findRun("missing-run");
        verify(selfEvolvingRunService).startRun(context);
        verify(evolutionCandidateService, never()).deriveCandidates(completedRun, llmVerdict, null);
        assertEquals("run-2", result.getAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID));
    }

    @Test
    void shouldContinueWhenHiveProjectionPublishFails() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        AgentContext context = buildContext();
        RunRecord startedRun = RunRecord.builder()
                .id("run-3")
                .artifactBundleId("bundle-3")
                .build();
        RunRecord completedRun = RunRecord.builder()
                .id("run-3")
                .artifactBundleId("bundle-3")
                .traceId("trace-1")
                .status("COMPLETED")
                .build();
        TraceRecord traceRecord = TraceRecord.builder().traceId("trace-1").build();
        RunVerdict deterministicVerdict = RunVerdict.builder().runId("run-3").build();
        RunVerdict llmVerdict = RunVerdict.builder().runId("run-3").build();
        when(selfEvolvingRunService.startRun(context)).thenReturn(startedRun);
        when(selfEvolvingRunService.completeRun(startedRun, context)).thenReturn(completedRun);
        when(deterministicJudgeService.evaluate(completedRun, traceRecord)).thenReturn(deterministicVerdict);
        when(llmJudgeService.judge(eq(completedRun), eq(traceRecord), eq(deterministicVerdict), any(), any()))
                .thenReturn(llmVerdict);
        when(llmEvolutionService.propose(completedRun, llmVerdict)).thenReturn(null);
        doThrow(new IllegalStateException("hive offline")).when(projectionPublishPort)
                .publishSelfEvolvingProjection(completedRun, llmVerdict, List.of());

        AgentContext result = processAndAwait(context);

        verify(evolutionCandidateService, never()).deriveCandidates(completedRun, llmVerdict, null);
        verify(promotionWorkflowService).registerCandidates(List.of());
        assertEquals(Boolean.TRUE, result.getAttribute(ContextAttributes.SELF_EVOLVING_ANALYSIS_COMPLETED));
    }

    @Test
    void shouldAutoAcceptCandidatesWhenPromotionModeIsConfigured() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigService.getSelfEvolvingPromotionMode()).thenReturn("auto_accept");
        AgentContext context = buildContext();
        RunRecord startedRun = RunRecord.builder()
                .id("run-4")
                .artifactBundleId("bundle-4")
                .build();
        RunRecord completedRun = RunRecord.builder()
                .id("run-4")
                .artifactBundleId("bundle-4")
                .traceId("trace-1")
                .status("COMPLETED")
                .build();
        TraceRecord traceRecord = TraceRecord.builder().traceId("trace-1").build();
        VerdictEvidenceRef evidence = VerdictEvidenceRef.builder().spanId("span-1").build();
        RunVerdict deterministicVerdict = RunVerdict.builder()
                .runId("run-4")
                .confidence(0.9)
                .evidenceRefs(List.of(evidence))
                .build();
        RunVerdict llmVerdict = RunVerdict.builder()
                .runId("run-4")
                .outcomeStatus("COMPLETED")
                .confidence(0.9)
                .evidenceRefs(List.of(evidence))
                .build();
        EvolutionProposal proposal = EvolutionProposal.builder()
                .summary("Auto-accept planner tactic")
                .build();
        List<EvolutionCandidate> candidates = List.of(EvolutionCandidate.builder().id("candidate-4").build());
        when(selfEvolvingRunService.startRun(context)).thenReturn(startedRun);
        when(selfEvolvingRunService.completeRun(startedRun, context)).thenReturn(completedRun);
        when(deterministicJudgeService.evaluate(completedRun, traceRecord)).thenReturn(deterministicVerdict);
        when(llmJudgeService.judge(eq(completedRun), eq(traceRecord), eq(deterministicVerdict), any(), any()))
                .thenReturn(llmVerdict);
        when(llmEvolutionService.propose(completedRun, llmVerdict)).thenReturn(proposal);
        when(evolutionCandidateService.deriveCandidates(completedRun, llmVerdict, proposal)).thenReturn(candidates);

        processAndAwait(context);

        verify(promotionWorkflowService).registerAndPlanCandidates(candidates);
        verify(promotionWorkflowService, never()).registerCandidates(candidates);
    }

    @Test
    void shouldSkipCandidateDerivationWhenEvolutionGateRejectsLowConfidence() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        AgentContext context = buildContext();
        RunRecord startedRun = RunRecord.builder().id("run-5").artifactBundleId("bundle-5").build();
        RunRecord completedRun = RunRecord.builder()
                .id("run-5").artifactBundleId("bundle-5").traceId("trace-1").status("COMPLETED").build();
        TraceRecord traceRecord = TraceRecord.builder().traceId("trace-1").build();
        RunVerdict deterministicVerdict = RunVerdict.builder().runId("run-5").build();
        RunVerdict llmVerdict = RunVerdict.builder()
                .runId("run-5")
                .outcomeStatus("COMPLETED")
                .confidence(0.3)
                .evidenceRefs(List.of(VerdictEvidenceRef.builder().spanId("span-1").build()))
                .build();
        EvolutionProposal proposal = EvolutionProposal.builder().summary("Low confidence proposal").build();
        when(selfEvolvingRunService.startRun(context)).thenReturn(startedRun);
        when(selfEvolvingRunService.completeRun(startedRun, context)).thenReturn(completedRun);
        when(deterministicJudgeService.evaluate(completedRun, traceRecord)).thenReturn(deterministicVerdict);
        when(llmJudgeService.judge(eq(completedRun), eq(traceRecord), eq(deterministicVerdict), any(), any()))
                .thenReturn(llmVerdict);
        when(llmEvolutionService.propose(completedRun, llmVerdict)).thenReturn(proposal);

        processAndAwait(context);

        verify(evolutionCandidateService, never()).deriveCandidates(completedRun, llmVerdict, proposal);
        verify(promotionWorkflowService).registerCandidates(List.of());
    }

    @Test
    void shouldRecordTacticOutcomesAfterVerdict() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        AgentContext context = buildContext();
        RunRecord startedRun = RunRecord.builder()
                .id("run-6").artifactBundleId("bundle-6")
                .appliedTacticIds(List.of("tactic-a", "tactic-b"))
                .build();
        RunRecord completedRun = RunRecord.builder()
                .id("run-6").artifactBundleId("bundle-6").traceId("trace-1").status("COMPLETED")
                .appliedTacticIds(List.of("tactic-a", "tactic-b"))
                .build();
        TraceRecord traceRecord = TraceRecord.builder().traceId("trace-1").build();
        RunVerdict deterministicVerdict = RunVerdict.builder().runId("run-6").build();
        RunVerdict llmVerdict = RunVerdict.builder()
                .runId("run-6").outcomeStatus("COMPLETED").build();
        when(selfEvolvingRunService.startRun(context)).thenReturn(startedRun);
        when(selfEvolvingRunService.completeRun(startedRun, context)).thenReturn(completedRun);
        when(deterministicJudgeService.evaluate(completedRun, traceRecord)).thenReturn(deterministicVerdict);
        when(llmJudgeService.judge(eq(completedRun), eq(traceRecord), eq(deterministicVerdict), any(), any()))
                .thenReturn(llmVerdict);
        when(llmEvolutionService.propose(completedRun, llmVerdict)).thenReturn(null);

        processAndAwait(context);

        verify(tacticOutcomeJournalService).record(org.mockito.ArgumentMatchers.argThat(
                entry -> "tactic-a".equals(entry.getTacticId()) && "success".equals(entry.getFinishReason())));
        verify(tacticOutcomeJournalService).record(org.mockito.ArgumentMatchers.argThat(
                entry -> "tactic-b".equals(entry.getTacticId()) && "success".equals(entry.getFinishReason())));
    }

    @Test
    void shouldRunBackgroundAnalysisOnDedicatedExecutor() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        AgentContext context = buildContext();
        RunRecord startedRun = RunRecord.builder()
                .id("run-7")
                .artifactBundleId("bundle-7")
                .build();
        RunRecord completedRun = RunRecord.builder()
                .id("run-7")
                .artifactBundleId("bundle-7")
                .traceId("trace-1")
                .status("COMPLETED")
                .build();
        TraceRecord traceRecord = TraceRecord.builder().traceId("trace-1").build();
        RunVerdict deterministicVerdict = RunVerdict.builder().runId("run-7").build();
        RunVerdict llmVerdict = RunVerdict.builder().runId("run-7").build();
        AtomicReference<String> backgroundThreadName = new AtomicReference<>();
        when(selfEvolvingRunService.startRun(context)).thenReturn(startedRun);
        when(selfEvolvingRunService.completeRun(startedRun, context)).thenReturn(completedRun);
        when(deterministicJudgeService.evaluate(completedRun, traceRecord)).thenReturn(deterministicVerdict);
        when(llmJudgeService.judge(eq(completedRun), eq(traceRecord), eq(deterministicVerdict), any(), any()))
                .thenAnswer(invocation -> {
                    backgroundThreadName.set(Thread.currentThread().getName());
                    return llmVerdict;
                });
        when(llmEvolutionService.propose(completedRun, llmVerdict)).thenReturn(null);

        processAndAwait(context);

        assertTrue(backgroundThreadName.get() != null
                && backgroundThreadName.get().startsWith("selfevolving-post-run-"));
    }

    private AgentContext processAndAwait(AgentContext context) {
        AgentContext result = system.process(context);
        if (system.getLastBackgroundAnalysis() != null) {
            system.getLastBackgroundAnalysis().join();
        }
        return result;
    }

    private AgentContext buildContext() {
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId("trace-1")
                .build();
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-1").chatId("chat-1").traces(List.of(traceRecord)).build())
                .traceContext(TraceContext.builder().traceId("trace-1").spanId("span-1").build())
                .build();
        context.setTurnOutcome(TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .assistantText("done")
                .build());
        return context;
    }
}
