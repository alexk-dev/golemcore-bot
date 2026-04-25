package me.golemcore.bot.domain.system.toolloop;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.system.LlmErrorClassifier;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultToolLoopSystemLlmFailureTest extends DefaultToolLoopSystemFixture {

    @Test
    void shouldRetryTwiceAndFailWhenLlmResponseIsAlwaysNull() {
        AgentContext context = buildContext();

        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(null));

        ToolLoopTurnResult result = system.processTurn(context);

        assertFalse(result.finalAnswerReady());
        assertEquals(3, result.llmCalls());
        assertNotNull(context.getAttribute(ContextAttributes.LLM_ERROR));
        assertEquals(LlmErrorClassifier.NO_ASSISTANT_MESSAGE,
                context.getAttribute(ContextAttributes.LLM_ERROR_CODE));
        verify(historyWriter, never()).appendFinalAssistantAnswer(any(), any(), any());
        verify(llmPort, times(3)).chat(any());
    }

    @Test
    void shouldRecoverFromEmptyFinalResponsesWithinRetryBudget() {
        AgentContext context = buildContext();

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(finalResponse("")))
                .thenReturn(CompletableFuture.completedFuture(finalResponse("   ")))
                .thenReturn(CompletableFuture.completedFuture(finalResponse("Recovered answer")));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(3, result.llmCalls());
        assertNull(context.getAttribute(ContextAttributes.LLM_ERROR));
        verify(historyWriter).appendFinalAssistantAnswer(any(), any(), eq("Recovered answer"));
    }

    @Test
    void shouldSetLangchainErrorCodeWhenLlmCallThrows() {
        AgentContext context = buildContext();
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("request timed out")));

        ToolLoopTurnResult result = system.processTurn(context);

        assertFalse(result.finalAnswerReady());
        assertEquals(1, result.llmCalls());
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_TIMEOUT,
                context.getAttribute(ContextAttributes.LLM_ERROR_CODE));
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        assertNotNull(llmError);
        assertTrue(llmError.startsWith("[" + LlmErrorClassifier.LANGCHAIN4J_TIMEOUT + "]"));
        assertFalse(context.getFailures().isEmpty());
        assertEquals(me.golemcore.bot.domain.model.FailureKind.EXCEPTION, context.getFailures().get(0).kind());
    }

    @Test
    void shouldLogTurnLevelRetriesForTransientLlmFailures() {
        AgentContext context = buildContext();
        DefaultToolLoopSystem retrySystem = buildSystemWithRuntimeConfig();
        stubRuntimeConfigDefaults();
        when(runtimeConfigService.isTurnAutoRetryEnabled()).thenReturn(true);
        when(runtimeConfigService.getTurnAutoRetryMaxAttempts()).thenReturn(2);
        when(runtimeConfigService.getTurnAutoRetryBaseDelayMs()).thenReturn(1L);
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("request timed out")))
                .thenReturn(CompletableFuture.completedFuture(finalResponse("Recovered")));

        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            ToolLoopTurnResult result = retrySystem.processTurn(context);

            assertTrue(result.finalAnswerReady());
            assertEquals(2, result.llmCalls());
            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertTrue(messages.stream().anyMatch(message -> message.contains(
                    "Transient LLM failure, scheduling retry (code=llm.langchain4j.timeout, retry=1/2")));
            assertTrue(messages.stream().anyMatch(message -> message.contains(
                    "LLM retry succeeded (code=llm.langchain4j.timeout, retry=1/2, llmCall=2, model=gpt-4o)")));
        } finally {
            ((Logger) LoggerFactory.getLogger(LlmCallPhase.class)).detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void shouldClassifyNestedRateLimitCauseWhenLlmCallThrows() {
        AgentContext context = buildContext();
        Throwable nested = new CompletionException(new RuntimeException("wrapper",
                new RateLimitException("too many requests")));
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.failedFuture(nested));

        ToolLoopTurnResult result = system.processTurn(context);

        assertFalse(result.finalAnswerReady());
        assertEquals(1, result.llmCalls());
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_RATE_LIMIT,
                context.getAttribute(ContextAttributes.LLM_ERROR_CODE));
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        assertNotNull(llmError);
        assertTrue(llmError.contains("RateLimitException"));
    }

    @Test
    void shouldPreferEmbeddedErrorCodeFromThrowableMessage() {
        AgentContext context = buildContext();
        String explicitCode = "llm.synthetic.explicit";
        Throwable throwable = new RuntimeException("[" + explicitCode + "] synthetic failure",
                new TimeoutException("request timed out"));
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.failedFuture(throwable));

        ToolLoopTurnResult result = system.processTurn(context);

        assertFalse(result.finalAnswerReady());
        assertEquals(explicitCode, context.getAttribute(ContextAttributes.LLM_ERROR_CODE));
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        assertNotNull(llmError);
        assertTrue(llmError.startsWith("[" + explicitCode + "]"));
    }

    @Test
    void shouldUsePlaceholderWhenRootCauseMessageIsMissing() {
        AgentContext context = buildContext();
        Throwable throwable = new RuntimeException(new RuntimeException((String) null));
        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.failedFuture(throwable));

        ToolLoopTurnResult result = system.processTurn(context);

        assertFalse(result.finalAnswerReady());
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        assertNotNull(llmError);
        assertTrue(llmError.contains("message=n/a"));
    }

    @Test
    void shouldNotRetryWhenVoiceOnlyResponseIsPresent() {
        AgentContext context = buildContext();
        context.setVoiceText("voice response");

        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(finalResponse(null)));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(1, result.llmCalls());
        verify(llmPort, times(1)).chat(any());
    }
}
