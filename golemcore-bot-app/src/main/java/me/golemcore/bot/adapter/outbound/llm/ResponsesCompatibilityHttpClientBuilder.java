package me.golemcore.bot.adapter.outbound.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.CancellationUnsupportedHandle;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Temporary compatibility shim for OpenAI-compatible /v1/responses endpoints
 * that stream partial text but emit {@code response.completed} with
 * {@code response.output: []}, which langchain4j 1.12.2 cannot finalize.
 */
@Slf4j
final class ResponsesCompatibilityHttpClientBuilder implements HttpClientBuilder {

    private final HttpClientBuilder delegate;
    private final ObjectMapper objectMapper;

    ResponsesCompatibilityHttpClientBuilder(HttpClientBuilder delegate, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Duration connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration timeout) {
        delegate.connectTimeout(timeout);
        return this;
    }

    @Override
    public Duration readTimeout() {
        return delegate.readTimeout();
    }

    @Override
    public HttpClientBuilder readTimeout(Duration timeout) {
        delegate.readTimeout(timeout);
        return this;
    }

    @Override
    public HttpClient build() {
        return new ResponsesCompatibilityHttpClient(delegate.build(), objectMapper);
    }

    @Slf4j
    static final class ResponsesCompatibilityHttpClient implements HttpClient {

        private static final String FIELD_CONTENT = "content";
        private static final String FIELD_CREATED = "created";
        private static final String FIELD_CREATED_AT = "created_at";
        private static final String FIELD_DELTA = "delta";
        private static final String FIELD_ITEM = "item";
        private static final String FIELD_OUTPUT = "output";
        private static final String FIELD_OUTPUT_INDEX = "output_index";
        private static final String FIELD_RESPONSE = "response";
        private static final String FIELD_TEXT = "text";
        private static final String FIELD_TYPE = "type";
        private static final String ITEM_ID = "item_id";
        private static final String MESSAGE_TYPE = "message";
        private static final String OUTPUT_TEXT_DELTA_EVENT = "response.output_text.delta";
        private static final String OUTPUT_ITEM_DONE_EVENT = "response.output_item.done";
        private static final String OUTPUT_TEXT_TYPE = "output_text";
        private static final String RESPONSES_PATH_SUFFIX = "/responses";
        private static final String RESPONSE_COMPLETED_EVENT = "response.completed";
        private static final String TYPE_RESPONSE = "response";

        private final HttpClient delegate;
        private final ObjectMapper objectMapper;

        ResponsesCompatibilityHttpClient(HttpClient delegate, ObjectMapper objectMapper) {
            this.delegate = delegate;
            this.objectMapper = objectMapper;
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            return delegate.execute(request);
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            if (!shouldUseCompatibilityLayer(request)) {
                delegate.execute(request, parser, listener);
                return;
            }

            delegate.execute(request, parser, new NormalizingResponsesListener(listener, objectMapper));
        }

        private boolean shouldUseCompatibilityLayer(HttpRequest request) {
            if (request == null || request.method() != HttpMethod.POST || request.body() == null
                    || request.body().isBlank()) {
                return false;
            }
            if (!isResponsesEndpoint(request.url())) {
                return false;
            }
            try {
                JsonNode requestBody = objectMapper.readTree(request.body());
                return requestBody.path("stream").asBoolean(false);
            } catch (IOException exception) {
                log.debug("[LLM] Skipping Responses compatibility layer because request body is not JSON", exception);
                return false;
            }
        }

        private boolean isResponsesEndpoint(String url) {
            try {
                String path = URI.create(url).getPath();
                return path != null && path.toLowerCase(Locale.ROOT).endsWith(RESPONSES_PATH_SUFFIX);
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }

        private static final class NormalizingResponsesListener implements ServerSentEventListener {

            private final ServerSentEventListener delegate;
            private final ObjectMapper objectMapper;
            private final Map<Integer, ObjectNode> completedOutputItems = new TreeMap<>();
            private final Map<Integer, StringBuilder> partialTextByOutputIndex = new TreeMap<>();

            private NormalizingResponsesListener(ServerSentEventListener delegate, ObjectMapper objectMapper) {
                this.delegate = delegate;
                this.objectMapper = objectMapper;
            }

            @Override
            public void onOpen(SuccessfulHttpResponse response) {
                delegate.onOpen(response);
            }

            @Override
            public void onEvent(ServerSentEvent event) {
                handleEvent(event, new ServerSentEventContext(new CancellationUnsupportedHandle()));
            }

            @Override
            public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
                handleEvent(event, context);
            }

            private void handleEvent(ServerSentEvent event, ServerSentEventContext context) {
                JsonNode node = parseEventData(event);
                if (node == null) {
                    delegate.onEvent(event, context);
                    return;
                }

                String type = node.path(FIELD_TYPE).asText();
                if (OUTPUT_TEXT_DELTA_EVENT.equals(type)) {
                    capturePartialText(node);
                    delegate.onEvent(event, context);
                    return;
                }

                if (OUTPUT_ITEM_DONE_EVENT.equals(type)) {
                    captureCompletedOutputItem(node);
                    delegate.onEvent(event, context);
                    return;
                }

                if (RESPONSE_COMPLETED_EVENT.equals(type)) {
                    delegate.onEvent(normalizeCompletedEvent(event, node), context);
                    return;
                }

                delegate.onEvent(event, context);
            }

            @Override
            public void onError(Throwable error) {
                delegate.onError(error);
            }

            @Override
            public void onClose() {
                delegate.onClose();
            }

            private JsonNode parseEventData(ServerSentEvent event) {
                if (event == null || event.data() == null || event.data().isBlank()) {
                    return null;
                }
                try {
                    return objectMapper.readTree(event.data());
                } catch (IOException exception) {
                    return null;
                }
            }

            private void capturePartialText(JsonNode node) {
                int outputIndex = node.path(FIELD_OUTPUT_INDEX).asInt(0);
                String delta = node.path(FIELD_DELTA).asText();
                if (delta.isBlank()) {
                    return;
                }
                partialTextByOutputIndex.computeIfAbsent(outputIndex, ignored -> new StringBuilder())
                        .append(delta);
            }

            private void captureCompletedOutputItem(JsonNode node) {
                JsonNode item = node.path(FIELD_ITEM);
                if (!(item instanceof ObjectNode objectNode)) {
                    return;
                }
                if (!MESSAGE_TYPE.equals(objectNode.path(FIELD_TYPE).asText())) {
                    return;
                }
                int outputIndex = node.path(FIELD_OUTPUT_INDEX).asInt(0);
                completedOutputItems.put(outputIndex, objectNode.deepCopy());
            }

            private ServerSentEvent normalizeCompletedEvent(ServerSentEvent originalEvent, JsonNode node) {
                if (!(node instanceof ObjectNode eventNode)) {
                    return originalEvent;
                }
                JsonNode responseNode = node.path(FIELD_RESPONSE);
                if (!(responseNode instanceof ObjectNode objectNode)) {
                    return originalEvent;
                }

                ObjectNode normalizedResponse = objectNode.deepCopy();
                if (!normalizedResponse.hasNonNull(FIELD_TYPE)) {
                    normalizedResponse.put(FIELD_TYPE, TYPE_RESPONSE);
                }
                if (!normalizedResponse.hasNonNull(FIELD_CREATED) && normalizedResponse.hasNonNull(FIELD_CREATED_AT)) {
                    normalizedResponse.set(FIELD_CREATED, normalizedResponse.get(FIELD_CREATED_AT));
                }

                ArrayNode output = normalizedResponse.withArray(FIELD_OUTPUT);
                if (output.isEmpty()) {
                    ArrayNode synthesizedOutput = synthesizeOutput();
                    if (!synthesizedOutput.isEmpty()) {
                        normalizedResponse.set(FIELD_OUTPUT, synthesizedOutput);
                    }
                }

                ObjectNode normalizedEvent = eventNode.deepCopy();
                normalizedEvent.put(FIELD_TYPE, RESPONSE_COMPLETED_EVENT);
                normalizedEvent.set(FIELD_RESPONSE, normalizedResponse);
                try {
                    return new ServerSentEvent(RESPONSE_COMPLETED_EVENT,
                            objectMapper.writeValueAsString(normalizedEvent));
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to serialize normalized response.completed event",
                            exception);
                }
            }

            private ArrayNode synthesizeOutput() {
                ArrayNode output = objectMapper.createArrayNode();
                for (ObjectNode completedItem : completedOutputItems.values()) {
                    output.add(completedItem.deepCopy());
                }

                if (!output.isEmpty()) {
                    return output;
                }

                for (Map.Entry<Integer, StringBuilder> entry : partialTextByOutputIndex.entrySet()) {
                    String text = entry.getValue().toString();
                    if (text.isBlank()) {
                        continue;
                    }
                    ObjectNode item = objectMapper.createObjectNode();
                    item.put("id", "compat_msg_" + entry.getKey());
                    item.put(FIELD_TYPE, MESSAGE_TYPE);
                    item.put("status", "completed");
                    item.put("role", "assistant");

                    ArrayNode content = item.putArray(FIELD_CONTENT);
                    ObjectNode contentPart = objectMapper.createObjectNode();
                    contentPart.put(FIELD_TYPE, OUTPUT_TEXT_TYPE);
                    contentPart.put(FIELD_TEXT, text);
                    content.add(contentPart);

                    output.add(item);
                }

                return output;
            }
        }
    }
}
