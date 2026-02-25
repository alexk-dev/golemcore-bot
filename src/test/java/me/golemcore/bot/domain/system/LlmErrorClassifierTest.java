package me.golemcore.bot.domain.system;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
