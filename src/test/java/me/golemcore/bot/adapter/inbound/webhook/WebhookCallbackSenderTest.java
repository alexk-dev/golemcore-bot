package me.golemcore.bot.adapter.inbound.webhook;

import me.golemcore.bot.adapter.inbound.webhook.dto.CallbackPayload;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookCallbackSenderTest {

    private static final String RUN_ID = "run-123";
    private static final String CHAT_ID = "hook:test";
    private static final String CALLBACK_PATH = "/callback";

    private MockWebServer mockServer;
    private WebhookCallbackSender sender;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        sender = new WebhookCallbackSender() {
            @Override
            protected Duration getRequestTimeout() {
                return Duration.ofSeconds(2);
            }

            @Override
            protected RetryBackoffSpec buildRetry(String callbackUrl) {
                return Retry.fixedDelay(3, Duration.ofMillis(10));
            }

            @Override
            public void send(String callbackUrl, CallbackPayload payload) {
                buildSendMono(callbackUrl, payload).block(Duration.ofSeconds(3));
            }
        };
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.close();
    }

    @Test
    void shouldPostCallbackPayloadToUrl() throws Exception {
        mockServer.enqueue(new MockResponse.Builder().code(200).build());

        CallbackPayload payload = CallbackPayload.builder()
                .runId(RUN_ID)
                .chatId(CHAT_ID)
                .status("completed")
                .response("Hello from agent")
                .model("balanced")
                .durationMs(1500)
                .build();

        sender.send(mockServer.url(CALLBACK_PATH).toString(), payload);

        RecordedRequest request = mockServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertTrue(request.getTarget().endsWith(CALLBACK_PATH));

        String body = request.getBody().utf8();
        assertTrue(body.contains("\"runId\":\"" + RUN_ID + "\""));
        assertTrue(body.contains("\"chatId\":\"" + CHAT_ID + "\""));
        assertTrue(body.contains("\"status\":\"completed\""));
        assertTrue(body.contains("\"response\":\"Hello from agent\""));
    }

    @Test
    void shouldSendFailurePayload() throws Exception {
        mockServer.enqueue(new MockResponse.Builder().code(200).build());

        CallbackPayload payload = CallbackPayload.builder()
                .runId(RUN_ID)
                .chatId(CHAT_ID)
                .status("failed")
                .error("Run cancelled or timed out")
                .durationMs(30000)
                .build();

        sender.send(mockServer.url(CALLBACK_PATH).toString(), payload);

        RecordedRequest request = mockServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);

        String body = request.getBody().utf8();
        assertTrue(body.contains("\"status\":\"failed\""));
        assertTrue(body.contains("\"error\":\"Run cancelled or timed out\""));
        // response should be absent (NON_NULL)
        assertFalse(body.contains("\"response\""));
    }

    @Test
    void shouldRetryOnServerError() throws Exception {
        // Enqueue failures followed by success
        mockServer.enqueue(new MockResponse.Builder().code(500).build());
        mockServer.enqueue(new MockResponse.Builder().code(200).build());

        CallbackPayload payload = CallbackPayload.builder()
                .runId(RUN_ID)
                .chatId(CHAT_ID)
                .status("completed")
                .response("result")
                .durationMs(100)
                .build();

        sender.send(mockServer.url(CALLBACK_PATH).toString(), payload);

        // First request (fails with 500)
        RecordedRequest first = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(first);

        RecordedRequest second = mockServer.takeRequest(10, TimeUnit.SECONDS);
        assertNotNull(second);

        assertEquals(2, mockServer.getRequestCount());
    }
}
