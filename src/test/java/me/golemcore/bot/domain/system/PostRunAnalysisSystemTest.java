package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.service.DeterministicJudgeService;
import me.golemcore.bot.domain.service.EvolutionCandidateService;
import me.golemcore.bot.domain.service.LlmJudgeService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SelfEvolvingRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostRunAnalysisSystemTest {

    private RuntimeConfigService runtimeConfigService;
    private SelfEvolvingRunService selfEvolvingRunService;
    private DeterministicJudgeService deterministicJudgeService;
    private LlmJudgeService llmJudgeService;
    private EvolutionCandidateService evolutionCandidateService;
    private PostRunAnalysisSystem system;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        selfEvolvingRunService = mock(SelfEvolvingRunService.class);
        deterministicJudgeService = mock(DeterministicJudgeService.class);
        llmJudgeService = mock(LlmJudgeService.class);
        evolutionCandidateService = mock(EvolutionCandidateService.class);
        system = new PostRunAnalysisSystem(
                runtimeConfigService,
                selfEvolvingRunService,
                deterministicJudgeService,
                llmJudgeService,
                evolutionCandidateService);
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
        when(selfEvolvingRunService.startRun(context)).thenReturn(startedRun);
        when(selfEvolvingRunService.completeRun(startedRun, context)).thenReturn(completedRun);
        when(deterministicJudgeService.evaluate(completedRun, null)).thenReturn(deterministicVerdict);
        when(llmJudgeService.judge(completedRun, null, deterministicVerdict)).thenReturn(llmVerdict);
        when(evolutionCandidateService.deriveCandidates(completedRun, llmVerdict))
                .thenReturn(List.of(EvolutionCandidate.builder()
                        .id("candidate-1")
                        .build()));

        assertTrue(system.shouldProcess(context));
        AgentContext result = system.process(context);

        verify(selfEvolvingRunService).startRun(context);
        verify(selfEvolvingRunService).completeRun(startedRun, context);
        verify(deterministicJudgeService).evaluate(completedRun, null);
        verify(llmJudgeService).judge(completedRun, null, deterministicVerdict);
        verify(evolutionCandidateService).deriveCandidates(completedRun, llmVerdict);
        assertEquals("run-1", result.getAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID));
        assertEquals("bundle-1", result.getAttribute(ContextAttributes.SELF_EVOLVING_ARTIFACT_BUNDLE_ID));
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
