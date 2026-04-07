package me.golemcore.bot.domain.selfevolving.benchmark;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SessionService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmJudgeServiceTest {

    private LlmPort llmPort;
    private JudgeTierResolver judgeTierResolver;
    private JudgeTraceDigestService judgeTraceDigestService;
    private RuntimeConfigService runtimeConfigService;
    private SessionService sessionService;
    private TraceService traceService;
    private LlmJudgeService llmJudgeService;

    @BeforeEach
    void setUp() {
        llmPort = mock(LlmPort.class);
        judgeTierResolver = mock(JudgeTierResolver.class);
        judgeTraceDigestService = new JudgeTraceDigestService();
        runtimeConfigService = mock(RuntimeConfigService.class);
        sessionService = mock(SessionService.class);
        traceService = mock(TraceService.class);
        RuntimeConfig.SelfEvolvingJudgeConfig judgeConfig = RuntimeConfig.SelfEvolvingJudgeConfig.builder()
                .enabled(true)
                .requireEvidenceAnchors(true)
                .uncertaintyThreshold(0.5)
                .build();
        RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig = RuntimeConfig.SelfEvolvingConfig.builder()
                .judge(judgeConfig)
                .build();
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(selfEvolvingConfig);
        when(sessionService.getOrCreate(anyString(), anyString())).thenReturn(
                AgentSession.builder()
                        .id("judge:test-session")
                        .channelType("judge")
                        .chatId("test")
                        .traces(new ArrayList<>())
                        .build());
        when(traceService.startRootTrace(any(), anyString(), any(), any(), any())).thenReturn(
                TraceContext.builder()
                        .traceId("judge-trace-1")
                        .spanId("judge-span-1")
                        .build());
        llmJudgeService = new LlmJudgeService(llmPort, judgeTierResolver, judgeTraceDigestService,
                runtimeConfigService, sessionService, traceService);
    }

    @Test
    void shouldRejectJudgeVerdictWithoutEvidenceRefs() {
        RunVerdict verdictWithoutEvidence = RunVerdict.builder()
                .outcomeStatus("COMPLETED")
                .build();

        assertThrows(IllegalArgumentException.class, () -> llmJudgeService.validate(verdictWithoutEvidence));
    }

    @Test
    void shouldEscalateToTiebreakerWhenPrimaryVerdictIsUncertain() {
        when(llmPort.isAvailable()).thenReturn(true);
        when(judgeTierResolver.resolveSelection("primary"))
                .thenReturn(new ModelSelectionService.ModelSelection("provider/judge-standard", "medium"));
        when(judgeTierResolver.resolveSelection("tiebreaker"))
                .thenReturn(new ModelSelectionService.ModelSelection("provider/judge-premium", "high"));
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("""
                                {"outcomeStatus":"COMPLETED","outcomeSummary":"Task completed","confidence":0.2,
                                "promotionRecommendation":"approve_gated",
                                "evidenceRefs":[{"traceId":"trace-1","spanId":"span-outcome"}]}
                                """)
                        .build()))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("""
                                {"processStatus":"ISSUES_FOUND","processSummary":"Minor issues","confidence":0.4,
                                "processFindings":["tool_churn"],
                                "evidenceRefs":[{"traceId":"trace-1","spanId":"span-process"}]}
                                """)
                        .build()))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("""
                                {"outcomeStatus":"COMPLETED","processStatus":"ISSUES_FOUND",
                                "outcomeSummary":"Task completed after arbitration",
                                "processSummary":"Minor issues confirmed","confidence":0.92,
                                "promotionRecommendation":"shadow",
                                "processFindings":["tool_churn"],
                                "evidenceRefs":[{"traceId":"trace-1","spanId":"span-final"}]}
                                """)
                        .build()));

        RunVerdict verdict = llmJudgeService.judge(
                RunRecord.builder().id("run-1").traceId("trace-1").status("COMPLETED").build(),
                TraceRecord.builder().traceId("trace-1").build(),
                RunVerdict.builder()
                        .runId("run-1")
                        .outcomeStatus("COMPLETED")
                        .evidenceRefs(List.of(VerdictEvidenceRef.builder().traceId("trace-1").spanId("seed").build()))
                        .build(),
                "What is the weather?",
                "The weather is sunny today.");

        assertEquals(0.92, verdict.getConfidence());
        assertEquals("shadow", verdict.getPromotionRecommendation());
        assertEquals("COMPLETED", verdict.getOutcomeStatus());
        assertEquals("ISSUES_FOUND", verdict.getProcessStatus());
        assertTrue(verdict.getEvidenceRefs().stream().anyMatch(ref -> "span-final".equals(ref.getSpanId())));

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort, times(3)).chat(requestCaptor.capture());
        List<LlmRequest> requests = requestCaptor.getAllValues();
        assertEquals("provider/judge-standard", requests.get(0).getModel());
        assertEquals("provider/judge-standard", requests.get(1).getModel());
        assertEquals("provider/judge-premium", requests.get(2).getModel());
    }

    @Test
    void shouldFallbackToDeterministicVerdictWhenLlmJudgeIsUnavailable() {
        RunVerdict deterministicVerdict = RunVerdict.builder()
                .runId("run-1")
                .outcomeStatus("FAILED")
                .processStatus("ISSUES_FOUND")
                .evidenceRefs(List.of(VerdictEvidenceRef.builder().traceId("trace-1").spanId("seed").build()))
                .build();
        when(llmPort.isAvailable()).thenReturn(false);

        RunVerdict verdict = llmJudgeService.judge(
                RunRecord.builder().id("run-1").traceId("trace-1").status("FAILED").build(),
                TraceRecord.builder().traceId("trace-1").build(),
                deterministicVerdict,
                null, null);

        assertEquals("FAILED", verdict.getOutcomeStatus());
        assertEquals("ISSUES_FOUND", verdict.getProcessStatus());
    }
}
