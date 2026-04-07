package me.golemcore.bot.adapter.outbound.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private ServerSentEvent event(String eventName, String jsonData) {
        return new ServerSentEvent(eventName, jsonData);
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
        private HttpRequest lastRequest;

        private StubStreamingHttpClient(List<ServerSentEvent> events) {
            this.events = events;
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            throw new UnsupportedOperationException("Non-streaming execute is not used in this test");
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            this.lastRequest = request;
            listener.onOpen(SuccessfulHttpResponse.builder()
                    .statusCode(200)
                    .body("")
                    .build());
            for (ServerSentEvent event : events) {
                listener.onEvent(event);
            }
            listener.onClose();
        }
    }
}
