package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.service.ContextBudgetPolicy;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.TraceBudgetService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.TraceSnapshotCompressionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultToolLoopSystemTraceTest extends DefaultToolLoopSystemFixture {

    @Test
    void shouldCopyTraceContextIntoLlmRequest() {
        AgentContext context = buildContext();
        context.setTraceContext(TraceContext.builder()
                .traceId("trace-1")
                .spanId("span-1")
                .rootKind("INGRESS")
                .build());
        LlmResponse response = finalResponse("Hello!");

        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        system.processTurn(context);

        org.mockito.ArgumentCaptor<me.golemcore.bot.domain.model.LlmRequest> captor = org.mockito.ArgumentCaptor
                .forClass(me.golemcore.bot.domain.model.LlmRequest.class);
        verify(llmPort).chat(captor.capture());
        assertEquals("trace-1", captor.getValue().getTraceId());
        assertEquals("span-1", captor.getValue().getTraceSpanId());
        assertEquals("INGRESS", captor.getValue().getTraceRootKind());
    }

    @Test
    void shouldRecordLlmAndToolChildSpansWithSnapshots() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID, "run-1");
        context.setAttribute(ContextAttributes.SELF_EVOLVING_ARTIFACT_BUNDLE_ID, "bundle-1");
        TraceService traceService = new TraceService(new TraceSnapshotCompressionService(), new TraceBudgetService());
        TraceContext rootTrace = traceService.startRootTrace(
                context.getSession(),
                TraceContext.builder()
                        .traceId("trace-1")
                        .spanId("root-span")
                        .rootKind(TraceSpanKind.INGRESS.name())
                        .build(),
                "telegram.message",
                TraceSpanKind.INGRESS,
                Instant.now(clock),
                Map.of("session.id", "sess-1"));
        context.setTraceContext(rootTrace);

        DefaultToolLoopSystem tracedSystem = DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(viewBuilder)
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnSettings))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .planService(planService)
                .runtimeConfigService(runtimeConfigService)
                .turnProgressService(turnProgressService)
                .traceService(traceService)
                .contextBudgetPolicy(new ContextBudgetPolicy(runtimeConfigService, modelSelectionService))
                .clock(clock)
                .build();

        stubRuntimeConfigDefaults();
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);
        when(runtimeConfigService.isPayloadSnapshotsEnabled()).thenReturn(true);
        when(runtimeConfigService.isTraceLlmPayloadCaptureEnabled()).thenReturn(true);
        when(runtimeConfigService.isTraceToolPayloadCaptureEnabled()).thenReturn(true);
        when(runtimeConfigService.getSessionTraceBudgetMb()).thenReturn(8);
        when(runtimeConfigService.getTraceMaxSnapshotSizeKb()).thenReturn(64);
        when(runtimeConfigService.getTraceMaxSnapshotsPerSpan()).thenReturn(4);

        Message.ToolCall toolCall = toolCall(TOOL_CALL_ID, TOOL_NAME);
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(toolCall))))
                .thenReturn(CompletableFuture.completedFuture(finalResponse(CONTENT_DONE)));
        when(toolExecutor.execute(any(), any())).thenReturn(new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null));

        ToolLoopTurnResult result = tracedSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        TraceRecord trace = context.getSession().getTraces().get(0);
        long llmSpanCount = trace.getSpans().stream()
                .filter(span -> "llm.chat".equals(span.getName()))
                .count();
        assertEquals(2L, llmSpanCount);
        assertTrue(trace.getSpans().stream()
                .anyMatch(span -> ("tool." + TOOL_NAME).equals(span.getName())));
        assertTrue(trace.getSpans().stream()
                .filter(span -> "llm.chat".equals(span.getName()))
                .allMatch(span -> !span.getSnapshots().isEmpty()));
        assertTrue(trace.getSpans().stream()
                .filter(span -> ("tool." + TOOL_NAME).equals(span.getName()))
                .allMatch(span -> !span.getSnapshots().isEmpty()));
        TraceSpanRecord toolSpan = trace.getSpans().stream()
                .filter(span -> ("tool." + TOOL_NAME).equals(span.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals("run-1", toolSpan.getAttributes().get("selfevolving.run.id"));
        assertEquals("bundle-1", toolSpan.getAttributes().get("selfevolving.artifact.bundle.id"));
    }

    @Test
    void shouldRecordRequestContextEventAndAttributesOnLlmSpan() {
        AgentContext context = buildContext();
        context.setActiveSkill(Skill.builder().name("planner").build());
        context.setModelTier("smart");
        context.setAttribute("model.tier.source", "skill");
        context.setAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID, "run-1");
        context.setAttribute(ContextAttributes.SELF_EVOLVING_ARTIFACT_BUNDLE_ID, "bundle-1");

        TraceService traceService = new TraceService(new TraceSnapshotCompressionService(), new TraceBudgetService());
        TraceContext rootTrace = traceService.startRootTrace(
                context.getSession(),
                TraceContext.builder()
                        .traceId("trace-1")
                        .spanId("root-span")
                        .rootKind(TraceSpanKind.INGRESS.name())
                        .build(),
                "telegram.message",
                TraceSpanKind.INGRESS,
                Instant.now(clock),
                Map.of("session.id", "sess-1"));
        context.setTraceContext(rootTrace);

        DefaultToolLoopSystem tracedSystem = DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(viewBuilder)
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnSettings))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .planService(planService)
                .runtimeConfigService(runtimeConfigService)
                .turnProgressService(turnProgressService)
                .traceService(traceService)
                .contextBudgetPolicy(new ContextBudgetPolicy(runtimeConfigService, modelSelectionService))
                .clock(clock)
                .build();

        stubRuntimeConfigDefaults();
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(finalResponse(CONTENT_DONE)));
        when(modelSelectionService.resolveForTier("smart"))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5-smart", "high"));

        ToolLoopTurnResult result = tracedSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        TraceRecord trace = context.getSession().getTraces().get(0);
        TraceSpanRecord llmSpan = trace.getSpans().stream()
                .filter(span -> "llm.chat".equals(span.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals("planner", llmSpan.getAttributes().get("context.skill.name"));
        assertEquals("smart", llmSpan.getAttributes().get("context.model.tier"));
        assertEquals("gpt-5-smart", llmSpan.getAttributes().get("context.model.id"));
        assertEquals("high", llmSpan.getAttributes().get("context.model.reasoning"));
        assertEquals("skill", llmSpan.getAttributes().get("context.model.source"));
        assertEquals("run-1", llmSpan.getAttributes().get("selfevolving.run.id"));
        assertEquals("bundle-1", llmSpan.getAttributes().get("selfevolving.artifact.bundle.id"));
        assertTrue(llmSpan.getEvents().stream()
                .anyMatch(event -> "request.context".equals(event.getName())
                        && "planner".equals(event.getAttributes().get("skill"))
                        && "smart".equals(event.getAttributes().get("tier"))
                        && "gpt-5-smart".equals(event.getAttributes().get("model_id"))
                        && "run-1".equals(event.getAttributes().get("run_id"))
                        && "bundle-1".equals(event.getAttributes().get("artifact_bundle_id"))));
    }
}
