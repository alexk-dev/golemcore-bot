package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.LlmResponse;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * Classifies LLM failures into stable machine-readable reason codes.
 */
public final class LlmErrorClassifier {

    public static final String STREAM_EMPTY_NO_MESSAGE_START = "llm.stream.empty_no_message_start";
    public static final String STREAM_EMPTY_NO_CONTENT_BLOCKS = "llm.stream.empty_no_content_blocks";
    public static final String STREAM_PARSE_CONTENT_BLOCK_NOT_FOUND = "llm.stream.parse_content_block_not_found";
    public static final String FALLBACK_NON_STREAMING_FAILED = "llm.fallback.non_streaming_failed";
    public static final String NO_ASSISTANT_MESSAGE = "llm.no_assistant_message";
    public static final String EMPTY_ASSISTANT_CONTENT = "llm.empty_assistant_content";
    public static final String REQUEST_ABORTED = "llm.request.aborted";
    public static final String REQUEST_TIMEOUT = "llm.request.timeout";
    public static final String CONTEXT_LENGTH_EXCEEDED = "llm.context.length_exceeded";
    public static final String LANGCHAIN4J_RATE_LIMIT = "llm.langchain4j.rate_limit";
    public static final String LANGCHAIN4J_TIMEOUT = "llm.langchain4j.timeout";
    public static final String LANGCHAIN4J_AUTHENTICATION = "llm.langchain4j.authentication";
    public static final String LANGCHAIN4J_INVALID_REQUEST = "llm.langchain4j.invalid_request";
    public static final String LANGCHAIN4J_MODEL_NOT_FOUND = "llm.langchain4j.model_not_found";
    public static final String LANGCHAIN4J_CONTENT_FILTERED = "llm.langchain4j.content_filtered";
    public static final String LANGCHAIN4J_INTERNAL_SERVER = "llm.langchain4j.internal_server";
    public static final String LANGCHAIN4J_UNSUPPORTED_FEATURE = "llm.langchain4j.unsupported_feature";
    public static final String LANGCHAIN4J_UNRESOLVED_MODEL_SERVER = "llm.langchain4j.unresolved_model_server";
    public static final String LANGCHAIN4J_RETRIABLE = "llm.langchain4j.retriable";
    public static final String LANGCHAIN4J_NON_RETRIABLE = "llm.langchain4j.non_retriable";
    public static final String LANGCHAIN4J_HTTP_ERROR = "llm.langchain4j.http_error";
    public static final String LANGCHAIN4J_ERROR = "llm.langchain4j.error";
    public static final String UNKNOWN = "llm.error.unknown";

    private static final String LANGCHAIN4J_EXCEPTIONS_PREFIX = "dev.langchain4j.exception.";
    private static final String CLASS_RATE_LIMIT_EXCEPTION = LANGCHAIN4J_EXCEPTIONS_PREFIX + "RateLimitException";
    private static final String CLASS_TIMEOUT_EXCEPTION = LANGCHAIN4J_EXCEPTIONS_PREFIX + "TimeoutException";
    private static final String CLASS_AUTHENTICATION_EXCEPTION = LANGCHAIN4J_EXCEPTIONS_PREFIX
            + "AuthenticationException";
    private static final String CLASS_INVALID_REQUEST_EXCEPTION = LANGCHAIN4J_EXCEPTIONS_PREFIX
            + "InvalidRequestException";
    private static final String CLASS_MODEL_NOT_FOUND_EXCEPTION = LANGCHAIN4J_EXCEPTIONS_PREFIX
            + "ModelNotFoundException";
    private static final String CLASS_CONTENT_FILTERED_EXCEPTION = LANGCHAIN4J_EXCEPTIONS_PREFIX
            + "ContentFilteredException";
    private static final String CLASS_INTERNAL_SERVER_EXCEPTION = LANGCHAIN4J_EXCEPTIONS_PREFIX
            + "InternalServerException";
    private static final String CLASS_UNSUPPORTED_FEATURE_EXCEPTION = LANGCHAIN4J_EXCEPTIONS_PREFIX
            + "UnsupportedFeatureException";
    private static final String CLASS_UNRESOLVED_MODEL_SERVER_EXCEPTION = LANGCHAIN4J_EXCEPTIONS_PREFIX
            + "UnresolvedModelServerException";
    private static final String CLASS_RETRIABLE_EXCEPTION = LANGCHAIN4J_EXCEPTIONS_PREFIX + "RetriableException";
    private static final String CLASS_NON_RETRIABLE_EXCEPTION = LANGCHAIN4J_EXCEPTIONS_PREFIX
            + "NonRetriableException";
    private static final String CLASS_HTTP_EXCEPTION = LANGCHAIN4J_EXCEPTIONS_PREFIX + "HttpException";
    private static final String CLASS_LANGCHAIN4J_EXCEPTION = LANGCHAIN4J_EXCEPTIONS_PREFIX + "LangChain4jException";

    private LlmErrorClassifier() {
    }

    /**
     * Classify empty final-answer cases produced by the local tool loop.
     */
    public static String classifyEmptyFinalResponse(LlmResponse response) {
        if (response == null) {
            return NO_ASSISTANT_MESSAGE;
        }
        String content = response.getContent();
        if (content == null || content.isBlank()) {
            return EMPTY_ASSISTANT_CONTENT;
        }
        return UNKNOWN;
    }

    /**
     * Classify an LLM failure based on structured throwable types/cause chain.
     */
    public static String classifyFromThrowable(Throwable throwable) {
        if (throwable == null) {
            return UNKNOWN;
        }

        Set<Throwable> visited = new HashSet<>();
        Throwable current = throwable;
        while (current != null && !visited.contains(current)) {
            visited.add(current);

            String embedded = extractCode(current.getMessage());
            if (embedded != null && !embedded.isBlank()) {
                return embedded;
            }

            String byType = classifyKnownThrowable(current);
            if (!UNKNOWN.equals(byType)) {
                return byType;
            }

            String byMessage = classifyFromMessage(current.getMessage());
            if (!UNKNOWN.equals(byMessage)) {
                return byMessage;
            }

            current = current.getCause();
        }
        return UNKNOWN;
    }

    /**
     * Classify a diagnostic string. Free-form text is intentionally not parsed;
     * only embedded code markers are trusted.
     */
    public static String classifyFromDiagnostic(String diagnostic) {
        if (diagnostic == null || diagnostic.isBlank()) {
            return UNKNOWN;
        }

        String embedded = extractCode(diagnostic);
        if (embedded != null && !embedded.isBlank()) {
            return embedded;
        }

        return classifyFromMessage(diagnostic);
    }

    /**
     * Backward-compatible alias for older call sites.
     */
    public static String classifyFromErrorText(String llmError) {
        return classifyFromDiagnostic(llmError);
    }

    /**
     * Prefix a human diagnostic with a machine-readable code.
     */
    public static String withCode(String code, String message) {
        if (message == null || message.isBlank()) {
            return "[" + code + "]";
        }
        if (message.startsWith("[" + code + "]")) {
            return message;
        }
        return "[" + code + "] " + message;
    }

    /**
     * Extract a code from diagnostics like: "[llm.some.code] details".
     */
    public static String extractCode(String message) {
        if (message == null || message.isBlank() || message.charAt(0) != '[') {
            return null;
        }
        int end = message.indexOf(']');
        if (end <= 1) {
            return null;
        }
        return message.substring(1, end);
    }

    public static boolean isTransientCode(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return LANGCHAIN4J_RATE_LIMIT.equals(code)
                || LANGCHAIN4J_TIMEOUT.equals(code)
                || LANGCHAIN4J_INTERNAL_SERVER.equals(code)
                || REQUEST_TIMEOUT.equals(code)
                || LANGCHAIN4J_RETRIABLE.equals(code);
    }

    public static boolean isContextOverflowCode(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return CONTEXT_LENGTH_EXCEEDED.equals(code);
    }

    private static String classifyKnownThrowable(Throwable throwable) {
        if (throwable instanceof CancellationException || throwable instanceof InterruptedException) {
            return REQUEST_ABORTED;
        }
        if (throwable instanceof SocketTimeoutException
                || throwable instanceof HttpTimeoutException
                || throwable instanceof TimeoutException) {
            return REQUEST_TIMEOUT;
        }

        String className = throwable.getClass().getName();
        if (!className.startsWith(LANGCHAIN4J_EXCEPTIONS_PREFIX)) {
            return UNKNOWN;
        }

        if (CLASS_RATE_LIMIT_EXCEPTION.equals(className)) {
            return LANGCHAIN4J_RATE_LIMIT;
        }
        if (CLASS_TIMEOUT_EXCEPTION.equals(className)) {
            return LANGCHAIN4J_TIMEOUT;
        }
        if (CLASS_AUTHENTICATION_EXCEPTION.equals(className)) {
            return LANGCHAIN4J_AUTHENTICATION;
        }
        if (CLASS_INVALID_REQUEST_EXCEPTION.equals(className)) {
            return LANGCHAIN4J_INVALID_REQUEST;
        }
        if (CLASS_MODEL_NOT_FOUND_EXCEPTION.equals(className)) {
            return LANGCHAIN4J_MODEL_NOT_FOUND;
        }
        if (CLASS_CONTENT_FILTERED_EXCEPTION.equals(className)) {
            return LANGCHAIN4J_CONTENT_FILTERED;
        }
        if (CLASS_INTERNAL_SERVER_EXCEPTION.equals(className)) {
            return LANGCHAIN4J_INTERNAL_SERVER;
        }
        if (CLASS_UNSUPPORTED_FEATURE_EXCEPTION.equals(className)) {
            return LANGCHAIN4J_UNSUPPORTED_FEATURE;
        }
        if (CLASS_UNRESOLVED_MODEL_SERVER_EXCEPTION.equals(className)) {
            return LANGCHAIN4J_UNRESOLVED_MODEL_SERVER;
        }
        if (CLASS_HTTP_EXCEPTION.equals(className)) {
            return classifyHttpExceptionByStatus(throwable);
        }
        if (CLASS_RETRIABLE_EXCEPTION.equals(className)) {
            return LANGCHAIN4J_RETRIABLE;
        }
        if (CLASS_NON_RETRIABLE_EXCEPTION.equals(className)) {
            return LANGCHAIN4J_NON_RETRIABLE;
        }
        if (CLASS_LANGCHAIN4J_EXCEPTION.equals(className)) {
            return LANGCHAIN4J_ERROR;
        }
        return UNKNOWN;
    }

    private static String classifyHttpExceptionByStatus(Throwable throwable) {
        Integer statusCode = readHttpStatusCode(throwable);
        if (statusCode == null) {
            return LANGCHAIN4J_HTTP_ERROR;
        }
        if (statusCode == 429) {
            return LANGCHAIN4J_RATE_LIMIT;
        }
        if (statusCode == 401 || statusCode == 403) {
            return LANGCHAIN4J_AUTHENTICATION;
        }
        if (statusCode == 408 || statusCode == 504) {
            return LANGCHAIN4J_TIMEOUT;
        }
        if (statusCode >= 500) {
            return LANGCHAIN4J_INTERNAL_SERVER;
        }
        if (statusCode >= 400) {
            return LANGCHAIN4J_INVALID_REQUEST;
        }
        return LANGCHAIN4J_HTTP_ERROR;
    }

    private static Integer readHttpStatusCode(Throwable throwable) {
        try {
            Method method = throwable.getClass().getMethod("statusCode");
            Object result = method.invoke(throwable);
            if (result instanceof Integer) {
                return (Integer) result;
            }
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (IllegalAccessException ignored) {
            return null;
        } catch (InvocationTargetException ignored) {
            return null;
        }
        return null;
    }

    private static String classifyFromMessage(String message) {
        if (message == null || message.isBlank()) {
            return UNKNOWN;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("context length")
                || normalized.contains("context window")
                || normalized.contains("maximum context")
                || normalized.contains("token limit exceeded")
                || normalized.contains("prompt is too long")) {
            return CONTEXT_LENGTH_EXCEEDED;
        }

        return UNKNOWN;
    }
}
