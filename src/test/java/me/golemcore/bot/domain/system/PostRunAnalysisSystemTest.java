package me.golemcore.bot.domain.system;

import me.golemcore.bot.adapter.outbound.hive.HiveEventBatchPublisher;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.service.DeterministicJudgeService;
import me.golemcore.bot.domain.service.EvolutionCandidateService;
import me.golemcore.bot.domain.service.LlmJudgeService;
import me.golemcore.bot.domain.service.PromotionWorkflowService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SelfEvolvingRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostRunAnalysisSystemTest {

    private RuntimeConfigService runtimeConfigService;
    private SelfEvolvingRunService selfEvolvingRunService;
    private DeterministicJudgeService deterministicJudgeService;
    private LlmJudgeService llmJudgeService;
    private EvolutionCandidateService evolutionCandidateService;
    private PromotionWorkflowService promotionWorkflowService;
    private HiveEventBatchPublisher hiveEventBatchPublisher;
    private PostRunAnalysisSystem system;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        selfEvolvingRunService = mock(SelfEvolvingRunService.class);
        deterministicJudgeService = mock(DeterministicJudgeService.class);
        llmJudgeService = mock(LlmJudgeService.class);
        evolutionCandidateService = mock(EvolutionCandidateService.class);
        promotionWorkflowService = mock(PromotionWorkflowService.class);
        hiveEventBatchPublisher = mock(HiveEventBatchPublisher.class);
        system = new PostRunAnalysisSystem(
                runtimeConfigService,
                selfEvolvingRunService,
                deterministicJudgeService,
                llmJudgeService,
                evolutionCandidateService,
                promotionWorkflowService,
                hiveEventBatchPublisher);
    }

    @Test
    void shouldNotProcessWhenSelfEvolvingDisabled() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(false);

        assertFalse(system.shouldProcess(buildContext()));
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
                .status("COMPLETED")
                .build();
        RunVerdict deterministicVerdict = RunVerdict.builder()
                .runId("run-1")
                .outcomeStatus("COMPLETED")
                .build();
        RunVerdict llmVerdict = RunVerdict.builder()
                .runId("run-1")
                .outcomeStatus("COMPLETED")
                .build();
        List<EvolutionCandidate> candidates = List.of(EvolutionCandidate.builder()
                .id("candidate-1")
                .build());
        when(selfEvolvingRunService.startRun(context)).thenReturn(startedRun);
        when(selfEvolvingRunService.completeRun(startedRun, context)).thenReturn(completedRun);
        when(deterministicJudgeService.evaluate(completedRun, null)).thenReturn(deterministicVerdict);
        when(llmJudgeService.judge(completedRun, null, deterministicVerdict)).thenReturn(llmVerdict);
        when(evolutionCandidateService.deriveCandidates(completedRun, llmVerdict)).thenReturn(candidates);

        assertTrue(system.shouldProcess(context));
        AgentContext result = system.process(context);

        verify(selfEvolvingRunService).startRun(context);
        verify(selfEvolvingRunService).completeRun(startedRun, context);
        verify(deterministicJudgeService).evaluate(completedRun, null);
        verify(llmJudgeService).judge(completedRun, null, deterministicVerdict);
        verify(evolutionCandidateService).deriveCandidates(completedRun, llmVerdict);
        verify(promotionWorkflowService).registerAndPlanCandidates(candidates);
        verify(hiveEventBatchPublisher).publishSelfEvolvingProjection(completedRun, llmVerdict, candidates);
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
                .status("COMPLETED")
                .build();
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
        when(deterministicJudgeService.evaluate(completedRun, null)).thenReturn(deterministicVerdict);
        when(llmJudgeService.judge(completedRun, null, deterministicVerdict)).thenReturn(llmVerdict);
        when(evolutionCandidateService.deriveCandidates(completedRun, llmVerdict)).thenReturn(List.of());

        assertTrue(system.shouldProcess(context));
        AgentContext result = system.process(context);

        verify(selfEvolvingRunService, never()).startRun(context);
        verify(selfEvolvingRunService).findRun("run-1");
        verify(selfEvolvingRunService).completeRun(existingRun, context);
        assertEquals("run-1", result.getAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID));
        assertEquals("bundle-1", result.getAttribute(ContextAttributes.SELF_EVOLVING_ARTIFACT_BUNDLE_ID));
    }

    @Test
    void shouldNotProcessWhenOutcomeMissingOrAnalysisAlreadyCompleted() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        AgentContext withoutOutcome = AgentContext.builder().build();
        AgentContext alreadyCompleted = buildContext();
        alreadyCompleted.setAttribute(ContextAttributes.SELF_EVOLVING_ANALYSIS_COMPLETED, true);

        assertFalse(system.shouldProcess(withoutOutcome));
        assertFalse(system.shouldProcess(alreadyCompleted));
    }

    @Test
    void shouldReturnContextImmediatelyWhenProcessingIsNotNeeded() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(false);
        AgentContext context = buildContext();

        AgentContext result = system.process(context);

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
                .status("COMPLETED")
                .build();
        RunVerdict deterministicVerdict = RunVerdict.builder().runId("run-2").build();
        RunVerdict llmVerdict = RunVerdict.builder().runId("run-2").build();
        when(selfEvolvingRunService.findRun("missing-run")).thenReturn(Optional.empty());
        when(selfEvolvingRunService.startRun(context)).thenReturn(startedRun);
        when(selfEvolvingRunService.completeRun(startedRun, context)).thenReturn(completedRun);
        when(deterministicJudgeService.evaluate(completedRun, null)).thenReturn(deterministicVerdict);
        when(llmJudgeService.judge(completedRun, null, deterministicVerdict)).thenReturn(llmVerdict);
        when(evolutionCandidateService.deriveCandidates(completedRun, llmVerdict)).thenReturn(List.of());

        AgentContext result = system.process(context);

        verify(selfEvolvingRunService).findRun("missing-run");
        verify(selfEvolvingRunService).startRun(context);
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
                .status("COMPLETED")
                .build();
        RunVerdict deterministicVerdict = RunVerdict.builder().runId("run-3").build();
        RunVerdict llmVerdict = RunVerdict.builder().runId("run-3").build();
        when(selfEvolvingRunService.startRun(context)).thenReturn(startedRun);
        when(selfEvolvingRunService.completeRun(startedRun, context)).thenReturn(completedRun);
        when(deterministicJudgeService.evaluate(completedRun, null)).thenReturn(deterministicVerdict);
        when(llmJudgeService.judge(completedRun, null, deterministicVerdict)).thenReturn(llmVerdict);
        when(evolutionCandidateService.deriveCandidates(completedRun, llmVerdict)).thenReturn(List.of());
        doThrow(new IllegalStateException("hive offline")).when(hiveEventBatchPublisher)
                .publishSelfEvolvingProjection(completedRun, llmVerdict, List.of());

        AgentContext result = system.process(context);

        verify(promotionWorkflowService).registerAndPlanCandidates(List.of());
        assertEquals(Boolean.TRUE, result.getAttribute(ContextAttributes.SELF_EVOLVING_ANALYSIS_COMPLETED));
    }

    private AgentContext buildContext() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-1").chatId("chat-1").build())
                .build();
        context.setTurnOutcome(TurnOutcome.builder()
                .finishReason(FinishReason.SUCCESS)
                .assistantText("done")
                .build());
        return context;
    }
}
