package me.golemcore.bot.domain.system;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import dev.langchain4j.exception.UnsupportedFeatureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmErrorClassifierTest {

    @Test
    void shouldClassifyLangchainRateLimitFromCauseChain() {
        Throwable throwable = new CompletionException(
                new RuntimeException("wrapper", new RateLimitException("too many requests")));

        String code = LlmErrorClassifier.classifyFromThrowable(throwable);

        assertEquals(LlmErrorClassifier.LANGCHAIN4J_RATE_LIMIT, code);
    }

    @Test
    void shouldClassifyLangchainHttp401AsAuthentication() {
        Throwable throwable = new HttpException(401, "unauthorized");

        String code = LlmErrorClassifier.classifyFromThrowable(throwable);

        assertEquals(LlmErrorClassifier.LANGCHAIN4J_AUTHENTICATION, code);
    }

    @ParameterizedTest
    @CsvSource({
            "429, llm.langchain4j.rate_limit",
            "401, llm.langchain4j.authentication",
            "403, llm.langchain4j.authentication",
            "408, llm.langchain4j.timeout",
            "504, llm.langchain4j.timeout",
            "500, llm.langchain4j.internal_server",
            "400, llm.langchain4j.invalid_request",
            "200, llm.langchain4j.http_error"
    })
    void shouldClassifyLangchainHttpStatuses(int statusCode, String expectedCode) {
        String code = LlmErrorClassifier.classifyFromThrowable(new HttpException(statusCode, "status"));

        assertEquals(expectedCode, code);
    }

    @Test
    void shouldClassifyLangchainSpecificExceptions() {
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_AUTHENTICATION,
                LlmErrorClassifier.classifyFromThrowable(new AuthenticationException("auth")));
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_CONTENT_FILTERED,
                LlmErrorClassifier.classifyFromThrowable(new ContentFilteredException("filtered")));
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_INTERNAL_SERVER,
                LlmErrorClassifier.classifyFromThrowable(new InternalServerException("internal")));
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_INVALID_REQUEST,
                LlmErrorClassifier.classifyFromThrowable(new InvalidRequestException("invalid")));
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_MODEL_NOT_FOUND,
                LlmErrorClassifier.classifyFromThrowable(new ModelNotFoundException("not-found")));
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_UNSUPPORTED_FEATURE,
                LlmErrorClassifier.classifyFromThrowable(new UnsupportedFeatureException("unsupported")));
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_UNRESOLVED_MODEL_SERVER,
                LlmErrorClassifier.classifyFromThrowable(new UnresolvedModelServerException("unresolved")));
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_TIMEOUT,
                LlmErrorClassifier.classifyFromThrowable(new TimeoutException("timeout")));
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_RETRIABLE,
                LlmErrorClassifier.classifyFromThrowable(new RetriableException("retriable")));
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_NON_RETRIABLE,
                LlmErrorClassifier.classifyFromThrowable(new NonRetriableException("non-retriable")));
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_ERROR,
                LlmErrorClassifier.classifyFromThrowable(new LangChain4jException("generic")));
    }

    @Test
    void shouldClassifyRequestAbortAndTimeoutFromJdkExceptions() {
        assertEquals(LlmErrorClassifier.REQUEST_ABORTED,
                LlmErrorClassifier.classifyFromThrowable(new CancellationException("cancelled")));
        assertEquals(LlmErrorClassifier.REQUEST_ABORTED,
                LlmErrorClassifier
                        .classifyFromThrowable(new RuntimeException(new InterruptedException("interrupted"))));
        assertEquals(LlmErrorClassifier.REQUEST_TIMEOUT,
                LlmErrorClassifier.classifyFromThrowable(new SocketTimeoutException("socket timeout")));
        assertEquals(LlmErrorClassifier.REQUEST_TIMEOUT,
                LlmErrorClassifier.classifyFromThrowable(new HttpTimeoutException("http timeout")));
        assertEquals(LlmErrorClassifier.REQUEST_TIMEOUT,
                LlmErrorClassifier.classifyFromThrowable(new java.util.concurrent.TimeoutException("timeout")));
    }

    @Test
    void shouldPreferEmbeddedCodeOverExceptionType() {
        String code = LlmErrorClassifier.classifyFromThrowable(
                new RateLimitException("[llm.custom.synthetic] explicit code"));

        assertEquals("llm.custom.synthetic", code);
    }

    @Test
    void shouldReturnUnknownForDiagnosticWithoutEmbeddedCode() {
        String code = LlmErrorClassifier.classifyFromDiagnostic(
                "Error streaming, falling back to non-streaming mode: Content block not found");

        assertEquals(LlmErrorClassifier.UNKNOWN, code);
    }

    @Test
    void shouldExtractEmbeddedCodeFromDiagnostic() {
        String code = LlmErrorClassifier.classifyFromDiagnostic(
                "[llm.stream.parse_content_block_not_found] stream parser failed");

        assertEquals(LlmErrorClassifier.STREAM_PARSE_CONTENT_BLOCK_NOT_FOUND, code);
    }

    @Test
    void shouldHandleDiagnosticAndCodeHelpersEdgeCases() {
        assertEquals(LlmErrorClassifier.UNKNOWN, LlmErrorClassifier.classifyFromDiagnostic(null));
        assertEquals(LlmErrorClassifier.UNKNOWN, LlmErrorClassifier.classifyFromDiagnostic("  "));
        assertEquals("llm.sample.code", LlmErrorClassifier.extractCode("[llm.sample.code] details"));
        assertNull(LlmErrorClassifier.extractCode("llm.sample.code"));
        assertNull(LlmErrorClassifier.extractCode("[missing_end"));
        assertEquals("[llm.x]", LlmErrorClassifier.withCode("llm.x", ""));
        assertEquals("[llm.x] details", LlmErrorClassifier.withCode("llm.x", "details"));
        assertEquals("[llm.x] details", LlmErrorClassifier.withCode("llm.x", "[llm.x] details"));
    }

    @Test
    void shouldReturnUnknownWhenThrowableChainHasNoKnownSignals() {
        String code = LlmErrorClassifier
                .classifyFromThrowable(new CompletionException(new RuntimeException("generic")));

        assertEquals(LlmErrorClassifier.UNKNOWN, code);
    }
}
