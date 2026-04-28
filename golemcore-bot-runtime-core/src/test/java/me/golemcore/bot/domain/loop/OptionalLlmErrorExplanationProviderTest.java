package me.golemcore.bot.domain.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FailureEvent;
import me.golemcore.bot.domain.model.FailureKind;
import me.golemcore.bot.domain.model.FailureSource;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.RoutingOutcome;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.runtimeconfig.ModelRoutingConfigView;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OptionalLlmErrorExplanationProviderTest {

    private static final String ENABLED_PROPERTY = "golemcore.feedback.llm-error-explanation.enabled";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-04-27T00:00:00Z"), ZoneOffset.UTC);

    @AfterEach
    void clearFlag() {
        System.clearProperty(ENABLED_PROPERTY);
    }

    @Test
    void shouldSkipWhenFeatureFlagIsDisabled() {
        LlmPort llmPort = mock(LlmPort.class);
        OptionalLlmErrorExplanationProvider provider = new OptionalLlmErrorExplanationProvider(
                mock(ModelRoutingConfigView.class), llmPort, CLOCK);

        Optional<String> explanation = provider.explain(contextWithFailure("token=secret-value"));

        assertTrue(explanation.isEmpty());
        verify(llmPort, never()).chat(any());
    }

    @Test
    void shouldReturnRedactedExplanationWhenEnabled() {
        System.setProperty(ENABLED_PROPERTY, "true");
        ModelRoutingConfigView configView = mock(ModelRoutingConfigView.class);
        when(configView.getRoutingModel()).thenReturn("gpt-test");
        when(configView.getRoutingModelReasoning()).thenReturn("low");
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(true);
        when(llmPort.chat(any())).thenReturn(
                CompletableFuture.completedFuture(LlmResponse.builder().content("A safe explanation").build()));
        OptionalLlmErrorExplanationProvider provider = new OptionalLlmErrorExplanationProvider(configView, llmPort,
                CLOCK);

        Optional<String> explanation = provider.explain(contextWithFailure("token=secret-value"));

        assertEquals(Optional.of("A safe explanation"), explanation);
        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort).chat(captor.capture());
        assertEquals("gpt-test", captor.getValue().getModel());
        String prompt = captor.getValue().getMessages().getFirst().getContent();
        assertTrue(prompt.contains("token=<redacted>"));
        assertTrue(!prompt.contains("secret-value"));
    }

    @Test
    void shouldSkipWhenNoSafeErrorsExist() {
        System.setProperty(ENABLED_PROPERTY, "true");
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(true);
        OptionalLlmErrorExplanationProvider provider = new OptionalLlmErrorExplanationProvider(
                mock(ModelRoutingConfigView.class), llmPort, CLOCK);

        Optional<String> explanation = provider.explain(AgentContext.builder().build());

        assertTrue(explanation.isEmpty());
        verify(llmPort, never()).chat(any());
    }

    @Test
    void shouldSkipWhenContextIsMissing() {
        System.setProperty(ENABLED_PROPERTY, "true");
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(true);
        OptionalLlmErrorExplanationProvider provider = new OptionalLlmErrorExplanationProvider(
                mock(ModelRoutingConfigView.class), llmPort, CLOCK);

        Optional<String> explanation = provider.explain(null);

        assertTrue(explanation.isEmpty());
        verify(llmPort, never()).chat(any());
    }

    @Test
    void shouldSkipWhenLlmPortIsMissing() {
        System.setProperty(ENABLED_PROPERTY, "true");
        OptionalLlmErrorExplanationProvider provider = new OptionalLlmErrorExplanationProvider(
                mock(ModelRoutingConfigView.class), null, CLOCK);

        Optional<String> explanation = provider.explain(contextWithFailure("token=secret-value"));

        assertTrue(explanation.isEmpty());
    }

    @Test
    void shouldSkipWhenLlmIsUnavailable() {
        System.setProperty(ENABLED_PROPERTY, "true");
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);
        OptionalLlmErrorExplanationProvider provider = new OptionalLlmErrorExplanationProvider(
                mock(ModelRoutingConfigView.class), llmPort, CLOCK);

        Optional<String> explanation = provider.explain(contextWithFailure("bearer abc.def"));

        assertTrue(explanation.isEmpty());
        verify(llmPort, never()).chat(any());
    }

    @Test
    void shouldReturnEmptyWhenLlmResponseIsBlank() {
        System.setProperty(ENABLED_PROPERTY, "true");
        ModelRoutingConfigView configView = mock(ModelRoutingConfigView.class);
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(true);
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder().content(" ").build()));
        OptionalLlmErrorExplanationProvider provider = new OptionalLlmErrorExplanationProvider(configView, llmPort,
                CLOCK);

        Optional<String> explanation = provider.explain(contextWithFailure("password=sensitive"));

        assertTrue(explanation.isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenLlmResponseIsMissing() {
        System.setProperty(ENABLED_PROPERTY, "true");
        ModelRoutingConfigView configView = mock(ModelRoutingConfigView.class);
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(true);
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(null));
        OptionalLlmErrorExplanationProvider provider = new OptionalLlmErrorExplanationProvider(configView, llmPort,
                CLOCK);

        Optional<String> explanation = provider.explain(contextWithFailure("password=sensitive"));

        assertTrue(explanation.isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenLlmExplanationTimesOut() {
        System.setProperty(ENABLED_PROPERTY, "true");
        ModelRoutingConfigView configView = mock(ModelRoutingConfigView.class);
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(true);
        when(llmPort.chat(any())).thenReturn(timeoutFuture());
        OptionalLlmErrorExplanationProvider provider = new OptionalLlmErrorExplanationProvider(configView, llmPort,
                CLOCK);

        Optional<String> explanation = provider.explain(contextWithFailure("secret=value"));

        assertTrue(explanation.isEmpty());
    }

    @Test
    void shouldExplainRoutingOutcomeWithTraceMetadata() {
        System.setProperty(ENABLED_PROPERTY, "true");
        ModelRoutingConfigView configView = mock(ModelRoutingConfigView.class);
        when(configView.getRoutingModel()).thenReturn("gpt-test");
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(true);
        when(llmPort.chat(any())).thenReturn(
                CompletableFuture.completedFuture(LlmResponse.builder().content("A routed explanation").build()));
        OptionalLlmErrorExplanationProvider provider = new OptionalLlmErrorExplanationProvider(configView, llmPort,
                CLOCK);
        AgentContext context = AgentContext.builder().session(AgentSession.builder().id("session-1").build()).build();
        context.setTraceContext(TraceContext.builder().traceId("trace-1").spanId("span-1").parentSpanId("parent-1")
                .rootKind("turn").build());
        context.setTurnOutcome(TurnOutcome.builder()
                .routingOutcome(RoutingOutcome.builder().errorMessage("Bearer abc.def").build()).build());

        Optional<String> explanation = provider.explain(context);

        assertEquals(Optional.of("A routed explanation"), explanation);
        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort).chat(captor.capture());
        assertEquals("trace-1", captor.getValue().getTraceId());
        assertEquals("span-1", captor.getValue().getTraceSpanId());
        assertEquals("parent-1", captor.getValue().getTraceParentSpanId());
        assertEquals("turn", captor.getValue().getTraceRootKind());
        assertTrue(captor.getValue().getMessages().getFirst().getContent().contains("Bearer <redacted>"));
    }

    private static AgentContext contextWithFailure(String message) {
        AgentContext context = AgentContext.builder().session(AgentSession.builder().id("session-1").build()).build();
        context.setAttribute(ContextAttributes.LLM_ERROR, message);
        context.addFailure(
                new FailureEvent(FailureSource.SYSTEM, "test", FailureKind.EXCEPTION, message, CLOCK.instant()));
        return context;
    }

    private static CompletableFuture<LlmResponse> timeoutFuture() {
        return new CompletableFuture<>() {
            @Override
            public LlmResponse get(long timeout, TimeUnit unit)
                    throws InterruptedException, ExecutionException, TimeoutException {
                throw new TimeoutException("slow");
            }
        };
    }
}
