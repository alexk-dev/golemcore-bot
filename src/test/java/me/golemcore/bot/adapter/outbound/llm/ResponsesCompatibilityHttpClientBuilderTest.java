package me.golemcore.bot.adapter.outbound.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.CancellationUnsupportedHandle;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesCompatibilityHttpClientBuilderTest {

    private static final String KEY = "test-key";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldCompleteTextResponseWhenCompletedEventHasEmptyOutput() throws Exception {
        StubStreamingHttpClient delegateClient = new StubStreamingHttpClient(List.of(
                event("response.output_text.delta", """
                        {
                          "type": "response.output_text.delta",
                          "content_index": 0,
                          "delta": "Hello from compatibility layer",
                          "item_id": "msg_test_123",
                          "output_index": 0
                        }
                        """),
                event("response.output_item.done", """
                        {
                          "type": "response.output_item.done",
                          "item": {
                            "id": "msg_test_123",
                            "type": "message",
                            "status": "completed",
                            "role": "assistant",
                            "content": [
                              {
                                "type": "output_text",
                                "text": "Hello from compatibility layer"
                              }
                            ]
                          },
                          "output_index": 0
                        }
                        """),
                event("response.completed", """
                        {
                          "type": "response.completed",
                          "response": {
                            "id": "resp_test_123",
                            "object": "response",
                            "created_at": 1775521153,
                            "status": "completed",
                            "model": "gpt-5.4",
                            "output": [],
                            "usage": {
                              "input_tokens": 10,
                              "output_tokens": 5,
                              "total_tokens": 15
                            }
                          }
                        }
                        """)));
        HttpClientBuilder delegateBuilder = new StubHttpClientBuilder(delegateClient);
        ResponsesCompatibilityHttpClientBuilder compatibilityBuilder = new ResponsesCompatibilityHttpClientBuilder(
                delegateBuilder, objectMapper);

        StreamingChatModel model = OpenAiResponsesStreamingChatModel.builder()
                .apiKey(KEY)
                .modelName("gpt-5.4")
                .httpClientBuilder(compatibilityBuilder)
                .build();

        List<String> partials = new ArrayList<>();
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        model.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("Hi")))
                .build(), new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        partials.add(partialResponse);
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse chatResponse) {
                        completed.set(chatResponse);
                    }

                    @Override
                    public void onError(Throwable error) {
                        failure.set(error);
                    }
                });

        assertEquals(List.of("Hello from compatibility layer"), partials);
        assertNotNull(completed.get());
        assertEquals("Hello from compatibility layer", completed.get().aiMessage().text());
        assertEquals(15, completed.get().tokenUsage().totalTokenCount());
        assertNull(failure.get());

        JsonNode requestBody = objectMapper.readTree(delegateClient.lastRequest.body());
        assertTrue(requestBody.path("stream").asBoolean(false));
    }

    @Test
    void shouldSynthesizeMessageFromDeltasWhenOutputItemDoneIsMissing() {
        StubStreamingHttpClient delegateClient = new StubStreamingHttpClient(List.of(
                event("response.output_text.delta", """
                        {
                          "type": "response.output_text.delta",
                          "content_index": 0,
                          "delta": "Hello ",
                          "item_id": "msg_test_123",
                          "output_index": 0
                        }
                        """),
                event("response.output_text.delta", """
                        {
                          "type": "response.output_text.delta",
                          "content_index": 0,
                          "delta": "world",
                          "item_id": "msg_test_123",
                          "output_index": 0
                        }
                        """),
                event("response.completed", """
                        {
                          "type": "response.completed",
                          "response": {
                            "id": "resp_test_123",
                            "object": "response",
                            "created_at": 1775521153,
                            "status": "completed",
                            "model": "gpt-5.4",
                            "output": [],
                            "usage": {
                              "input_tokens": 10,
                              "output_tokens": 5,
                              "total_tokens": 15
                            }
                          }
                        }
                        """)));
        HttpClientBuilder delegateBuilder = new StubHttpClientBuilder(delegateClient);
        ResponsesCompatibilityHttpClientBuilder compatibilityBuilder = new ResponsesCompatibilityHttpClientBuilder(
                delegateBuilder, objectMapper);

        StreamingChatModel model = OpenAiResponsesStreamingChatModel.builder()
                .apiKey(KEY)
                .modelName("gpt-5.4")
                .httpClientBuilder(compatibilityBuilder)
                .build();

        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        model.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("Hi")))
                .build(), new StreamingChatResponseHandler() {
                    @Override
                    public void onCompleteResponse(ChatResponse chatResponse) {
                        completed.set(chatResponse);
                    }

                    @Override
                    public void onError(Throwable error) {
                        failure.set(error);
                    }
                });

        assertNotNull(completed.get());
        assertEquals("Hello world", completed.get().aiMessage().text());
        assertNull(failure.get());
    }

    @Test
    void shouldPassThroughToolCallsWhenCompletedEventHasEmptyOutput() {
        StubStreamingHttpClient delegateClient = new StubStreamingHttpClient(List.of(
                event("response.output_item.added", """
                        {
                          "type": "response.output_item.added",
                          "item": {
                            "id": "fc_test_123",
                            "type": "function_call",
                            "call_id": "call_test_123",
                            "name": "weather"
                          },
                          "output_index": 0
                        }
                        """),
                event("response.function_call_arguments.done", """
                        {
                          "type": "response.function_call_arguments.done",
                          "item_id": "fc_test_123",
                          "arguments": "{\\"city\\":\\"Paris\\"}"
                        }
                        """),
                event("response.completed", """
                        {
                          "type": "response.completed",
                          "response": {
                            "id": "resp_tool_123",
                            "object": "response",
                            "created_at": 1775521153,
                            "status": "completed",
                            "model": "gpt-5.4",
                            "output": [],
                            "usage": {
                              "input_tokens": 10,
                              "output_tokens": 5,
                              "total_tokens": 15
                            }
                          }
                        }
                        """)));
        HttpClientBuilder delegateBuilder = new StubHttpClientBuilder(delegateClient);
        ResponsesCompatibilityHttpClientBuilder compatibilityBuilder = new ResponsesCompatibilityHttpClientBuilder(
                delegateBuilder, objectMapper);

        StreamingChatModel model = OpenAiResponsesStreamingChatModel.builder()
                .apiKey(KEY)
                .modelName("gpt-5.4")
                .httpClientBuilder(compatibilityBuilder)
                .build();

        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        model.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("Hi")))
                .build(), new StreamingChatResponseHandler() {
                    @Override
                    public void onCompleteResponse(ChatResponse chatResponse) {
                        completed.set(chatResponse);
                    }

                    @Override
                    public void onError(Throwable error) {
                        failure.set(error);
                    }
                });

        assertNotNull(completed.get());
        assertTrue(completed.get().aiMessage().hasToolExecutionRequests());
        assertEquals(1, completed.get().aiMessage().toolExecutionRequests().size());
        assertEquals("call_test_123", completed.get().aiMessage().toolExecutionRequests().get(0).id());
        assertEquals("weather", completed.get().aiMessage().toolExecutionRequests().get(0).name());
        assertEquals("{\"city\":\"Paris\"}", completed.get().aiMessage().toolExecutionRequests().get(0).arguments());
        assertNull(failure.get());
    }

    @Test
    void shouldCombineTextWithToolCallsWhenFunctionCallDoneEventIsPresent() {
        StubStreamingHttpClient delegateClient = new StubStreamingHttpClient(List.of(
                event("response.output_text.delta", """
                        {
                          "type": "response.output_text.delta",
                          "content_index": 0,
                          "delta": "Use weather tool",
                          "item_id": "msg_test_123",
                          "output_index": 1
                        }
                        """),
                event("response.output_item.done", """
                        {
                          "type": "response.output_item.done",
                          "item": {
                            "id": "fc_test_123",
                            "type": "function_call",
                            "call_id": "call_test_123",
                            "name": "weather",
                            "arguments": "{\\"city\\":\\"Paris\\"}"
                          },
                          "output_index": 0
                        }
                        """),
                event("response.completed", """
                        {
                          "type": "response.completed",
                          "sequence_number": 7,
                          "response": {
                            "id": "resp_tool_123",
                            "object": "response",
                            "created_at": 1775521153,
                            "status": "completed",
                            "model": "gpt-5.4",
                            "output": [],
                            "usage": {
                              "input_tokens": 10,
                              "output_tokens": 5,
                              "total_tokens": 15
                            }
                          }
                        }
                        """)));
        HttpClientBuilder delegateBuilder = new StubHttpClientBuilder(delegateClient);
        ResponsesCompatibilityHttpClientBuilder compatibilityBuilder = new ResponsesCompatibilityHttpClientBuilder(
                delegateBuilder, objectMapper);

        StreamingChatModel model = OpenAiResponsesStreamingChatModel.builder()
                .apiKey(KEY)
                .modelName("gpt-5.4")
                .httpClientBuilder(compatibilityBuilder)
                .build();

        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        model.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("Hi")))
                .build(), new StreamingChatResponseHandler() {
                    @Override
                    public void onCompleteResponse(ChatResponse chatResponse) {
                        completed.set(chatResponse);
                    }

                    @Override
                    public void onError(Throwable error) {
                        failure.set(error);
                    }
                });

        assertNotNull(completed.get());
        assertEquals("Use weather tool", completed.get().aiMessage().text());
        assertTrue(completed.get().aiMessage().hasToolExecutionRequests());
        assertEquals(1, completed.get().aiMessage().toolExecutionRequests().size());
        assertNull(failure.get());
    }

    @Test
    void shouldDelegateBuilderTimeoutsAndNonStreamingExecute() {
        SuccessfulHttpResponse response = SuccessfulHttpResponse.builder()
                .statusCode(201)
                .body("ok")
                .build();
        StubStreamingHttpClient delegateClient = new StubStreamingHttpClient(List.of(), response, false, null);
        StubHttpClientBuilder delegateBuilder = new StubHttpClientBuilder(delegateClient);
        ResponsesCompatibilityHttpClientBuilder compatibilityBuilder = new ResponsesCompatibilityHttpClientBuilder(
                delegateBuilder, objectMapper);

        assertSame(compatibilityBuilder, compatibilityBuilder.connectTimeout(Duration.ofSeconds(3)));
        assertSame(compatibilityBuilder, compatibilityBuilder.readTimeout(Duration.ofSeconds(7)));
        assertEquals(Duration.ofSeconds(3), compatibilityBuilder.connectTimeout());
        assertEquals(Duration.ofSeconds(7), compatibilityBuilder.readTimeout());

        HttpClient client = compatibilityBuilder.build();
        HttpRequest request = HttpRequest.builder()
                .method(HttpMethod.GET)
                .url("https://example.com/v1/models")
                .build();

        assertSame(response, client.execute(request));
        assertSame(request, delegateClient.lastRequest);
    }

    @Test
    void shouldBypassCompatibilityLayerWhenRequestIsNotStreamingResponses() {
        StubStreamingHttpClient delegateClient = new StubStreamingHttpClient(List.of(
                event("response.completed", """
                        {
                          "type": "response.completed",
                          "sequence_number": 11,
                          "response": {
                            "id": "resp_test_123",
                            "output": []
                          }
                        }
                        """)));
        ResponsesCompatibilityHttpClientBuilder.ResponsesCompatibilityHttpClient client = new ResponsesCompatibilityHttpClientBuilder.ResponsesCompatibilityHttpClient(
                delegateClient,
                objectMapper);
        CapturingServerSentEventListener listener = new CapturingServerSentEventListener();

        client.execute(streamingResponsesRequest("""
                {
                  "model": "gpt-5.4",
                  "stream": false
                }
                """), null, listener);

        assertEquals(1, listener.events.size());
        assertTrue(listener.events.get(0).data().contains("\"sequence_number\": 11"));
    }

    @Test
    void shouldBypassCompatibilityLayerWhenRequestBodyIsInvalidJsonOrUrlIsMalformed() {
        StubStreamingHttpClient delegateClient = new StubStreamingHttpClient(List.of(
                event("response.completed", """
                        {
                          "type": "response.completed",
                          "sequence_number": 13,
                          "response": {
                            "id": "resp_test_123",
                            "output": []
                          }
                        }
                        """)));
        ResponsesCompatibilityHttpClientBuilder.ResponsesCompatibilityHttpClient client = new ResponsesCompatibilityHttpClientBuilder.ResponsesCompatibilityHttpClient(
                delegateClient,
                objectMapper);

        CapturingServerSentEventListener invalidJsonListener = new CapturingServerSentEventListener();
        client.execute(streamingResponsesRequest("{not-json"), null, invalidJsonListener);
        assertEquals(1, invalidJsonListener.events.size());
        assertTrue(invalidJsonListener.events.get(0).data().contains("\"sequence_number\": 13"));

        CapturingServerSentEventListener malformedUrlListener = new CapturingServerSentEventListener();
        client.execute(HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("::bad-url")
                .body("""
                        {
                          "model": "gpt-5.4",
                          "stream": true
                        }
                        """)
                .build(), null, malformedUrlListener);
        assertEquals(1, malformedUrlListener.events.size());
        assertTrue(malformedUrlListener.events.get(0).data().contains("\"sequence_number\": 13"));
    }

    @Test
    void shouldPreserveCompletedEventEnvelopeWhenOutputAlreadyPresent() throws Exception {
        StubStreamingHttpClient delegateClient = new StubStreamingHttpClient(List.of(
                event("response.completed", """
                        {
                          "type": "response.completed",
                          "sequence_number": 25,
                          "response": {
                            "id": "resp_test_123",
                            "type": "response",
                            "created": 1775521153,
                            "status": "completed",
                            "model": "gpt-5.4",
                            "output": [
                              {
                                "id": "msg_test_123",
                                "type": "message",
                                "status": "completed",
                                "role": "assistant",
                                "content": [
                                  {
                                    "type": "output_text",
                                    "text": "Hello"
                                  }
                                ]
                              }
                            ]
                          }
                        }
                        """)));
        ResponsesCompatibilityHttpClientBuilder.ResponsesCompatibilityHttpClient client = new ResponsesCompatibilityHttpClientBuilder.ResponsesCompatibilityHttpClient(
                delegateClient,
                objectMapper);
        CapturingServerSentEventListener listener = new CapturingServerSentEventListener();

        client.execute(streamingResponsesRequest("""
                {
                  "model": "gpt-5.4",
                  "stream": true
                }
                """), null, listener);

        JsonNode completedEvent = objectMapper.readTree(listener.events.get(0).data());
        assertEquals(25, completedEvent.path("sequence_number").asInt());
        assertEquals("Hello", completedEvent.path("response").path("output").get(0).path("content").get(0)
                .path("text").asText());
        assertEquals(1775521153L, completedEvent.path("response").path("created").asLong());
    }

    @Test
    void shouldForwardContextBasedEventsAndLifecycleCallbacks() {
        RuntimeException expected = new RuntimeException("boom");
        StubStreamingHttpClient delegateClient = new StubStreamingHttpClient(List.of(
                new ServerSentEvent("custom", ""),
                new ServerSentEvent("custom", "not-json")),
                SuccessfulHttpResponse.builder()
                        .statusCode(200)
                        .body("")
                        .build(),
                true, expected);
        ResponsesCompatibilityHttpClientBuilder.ResponsesCompatibilityHttpClient client = new ResponsesCompatibilityHttpClientBuilder.ResponsesCompatibilityHttpClient(
                delegateClient,
                objectMapper);
        CapturingServerSentEventListener listener = new CapturingServerSentEventListener();

        client.execute(streamingResponsesRequest("""
                {
                  "model": "gpt-5.4",
                  "stream": true
                }
                """), null, listener);

        assertEquals(2, listener.events.size());
        assertSame(expected, listener.error);
        assertEquals(1, listener.closeCalls);
    }

    @Test
    void shouldIgnoreBlankDeltaAndNonObjectOutputItemWhenSynthesizingOutput() throws Exception {
        StubStreamingHttpClient delegateClient = new StubStreamingHttpClient(List.of(
                event("response.output_text.delta", """
                        {
                          "type": "response.output_text.delta",
                          "delta": "",
                          "output_index": 0
                        }
                        """),
                event("response.output_item.done", """
                        {
                          "type": "response.output_item.done",
                          "item": "not-an-object",
                          "output_index": 0
                        }
                        """),
                event("response.completed", """
                        {
                          "type": "response.completed",
                          "sequence_number": 33,
                          "response": {
                            "id": "resp_test_123",
                            "created_at": 1775521153,
                            "status": "completed",
                            "model": "gpt-5.4",
                            "output": []
                          }
                        }
                        """)));
        ResponsesCompatibilityHttpClientBuilder.ResponsesCompatibilityHttpClient client = new ResponsesCompatibilityHttpClientBuilder.ResponsesCompatibilityHttpClient(
                delegateClient,
                objectMapper);
        CapturingServerSentEventListener listener = new CapturingServerSentEventListener();

        client.execute(streamingResponsesRequest("""
                {
                  "model": "gpt-5.4",
                  "stream": true
                }
                """), null, listener);

        JsonNode completedEvent = objectMapper.readTree(listener.events.get(2).data());
        assertTrue(completedEvent.path("response").path("output").isArray());
        assertEquals(0, completedEvent.path("response").path("output").size());
        assertEquals(33, completedEvent.path("sequence_number").asInt());
    }

    private ServerSentEvent event(String eventName, String jsonData) {
        return new ServerSentEvent(eventName, jsonData);
    }

    private HttpRequest streamingResponsesRequest(String body) {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("https://example.com/v1/responses")
                .body(body)
                .build();
    }

    private static final class StubHttpClientBuilder implements HttpClientBuilder {

        private final HttpClient httpClient;
        private Duration connectTimeout;
        private Duration readTimeout;

        private StubHttpClientBuilder(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public Duration connectTimeout() {
            return connectTimeout;
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        @Override
        public Duration readTimeout() {
            return readTimeout;
        }

        @Override
        public HttpClientBuilder readTimeout(Duration timeout) {
            this.readTimeout = timeout;
            return this;
        }

        @Override
        public HttpClient build() {
            return httpClient;
        }
    }

    private static final class StubStreamingHttpClient implements HttpClient {

        private final List<ServerSentEvent> events;
        private final SuccessfulHttpResponse response;
        private final boolean useContextEvents;
        private final Throwable terminalError;
        private HttpRequest lastRequest;

        private StubStreamingHttpClient(List<ServerSentEvent> events) {
            this(events, SuccessfulHttpResponse.builder().statusCode(200).body("").build(), false, null);
        }

        private StubStreamingHttpClient(List<ServerSentEvent> events, SuccessfulHttpResponse response,
                boolean useContextEvents, Throwable terminalError) {
            this.events = events;
            this.response = response;
            this.useContextEvents = useContextEvents;
            this.terminalError = terminalError;
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            this.lastRequest = request;
            return response;
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            this.lastRequest = request;
            listener.onOpen(response);
            for (ServerSentEvent event : events) {
                if (useContextEvents) {
                    listener.onEvent(event, new ServerSentEventContext(new CancellationUnsupportedHandle()));
                } else {
                    listener.onEvent(event);
                }
            }
            if (terminalError != null) {
                listener.onError(terminalError);
            }
            listener.onClose();
        }
    }

    private static final class CapturingServerSentEventListener implements ServerSentEventListener {

        private final List<ServerSentEvent> events = new ArrayList<>();
        private Throwable error;
        private int closeCalls;

        @Override
        public void onEvent(ServerSentEvent event) {
            events.add(event);
        }

        @Override
        public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
            events.add(event);
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onClose() {
            closeCalls++;
        }
    }
}
