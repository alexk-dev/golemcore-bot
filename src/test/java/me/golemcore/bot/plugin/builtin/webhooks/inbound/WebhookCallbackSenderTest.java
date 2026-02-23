package me.golemcore.bot.plugin.builtin.webhooks.inbound;

import me.golemcore.bot.plugin.builtin.webhooks.inbound.dto.CallbackPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookCallbackSenderTest {

    private static final String RUN_ID = "run-123";
    private static final String CHAT_ID = "hook:test";
    private static final String CALLBACK_URL = "https://callback.example/callback";

    private WebClient webClient;
    private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @SuppressWarnings("rawtypes")
    private WebClient.RequestHeadersSpec requestHeadersSpec;
    private WebClient.ResponseSpec responseSpec;
    private WebhookCallbackSender sender;

    @BeforeEach
    void setUp() {
        webClient = mock(WebClient.class);
        requestBodyUriSpec = mock(WebClient.RequestBodyUriSpec.class);
        requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
        responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(String.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);

        sender = new WebhookCallbackSender(webClient) {
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

    @Test
    void shouldPostCallbackPayloadToUrl() {
        when(responseSpec.toBodilessEntity()).thenReturn(Mono.just(ResponseEntity.ok().build()));

        CallbackPayload payload = CallbackPayload.builder()
                .runId(RUN_ID)
                .chatId(CHAT_ID)
                .status("completed")
                .response("Hello from agent")
                .model("balanced")
                .durationMs(1500)
                .build();

        sender.send(CALLBACK_URL, payload);

        verify(webClient).post();
        verify(requestBodyUriSpec).uri(eq(CALLBACK_URL));

        ArgumentCaptor<CallbackPayload> payloadCaptor = ArgumentCaptor.forClass(CallbackPayload.class);
        verify(requestBodyUriSpec).bodyValue(payloadCaptor.capture());
        CallbackPayload sentPayload = payloadCaptor.getValue();

        assertEquals(RUN_ID, sentPayload.getRunId());
        assertEquals(CHAT_ID, sentPayload.getChatId());
        assertEquals("completed", sentPayload.getStatus());
        assertEquals("Hello from agent", sentPayload.getResponse());
        assertEquals("balanced", sentPayload.getModel());
    }

    @Test
    void shouldSendFailurePayload() {
        when(responseSpec.toBodilessEntity()).thenReturn(Mono.just(ResponseEntity.ok().build()));

        CallbackPayload payload = CallbackPayload.builder()
                .runId(RUN_ID)
                .chatId(CHAT_ID)
                .status("failed")
                .error("Run cancelled or timed out")
                .durationMs(30000)
                .build();

        sender.send(CALLBACK_URL, payload);

        ArgumentCaptor<CallbackPayload> payloadCaptor = ArgumentCaptor.forClass(CallbackPayload.class);
        verify(requestBodyUriSpec).bodyValue(payloadCaptor.capture());
        CallbackPayload sentPayload = payloadCaptor.getValue();

        assertEquals("failed", sentPayload.getStatus());
        assertEquals("Run cancelled or timed out", sentPayload.getError());
        assertNull(sentPayload.getResponse());
    }

    @Test
    void shouldRetryOnServerError() {
        AtomicInteger attempts = new AtomicInteger();
        when(responseSpec.toBodilessEntity()).thenReturn(Mono.defer(() -> {
            int currentAttempt = attempts.incrementAndGet();
            if (currentAttempt == 1) {
                return Mono.error(WebClientResponseException.create(
                        500,
                        "Internal Server Error",
                        HttpHeaders.EMPTY,
                        new byte[0],
                        StandardCharsets.UTF_8));
            }
            return Mono.just(ResponseEntity.ok().build());
        }));

        CallbackPayload payload = CallbackPayload.builder()
                .runId(RUN_ID)
                .chatId(CHAT_ID)
                .status("completed")
                .response("result")
                .durationMs(100)
                .build();

        sender.send(CALLBACK_URL, payload);

        assertEquals(2, attempts.get());
        verify(requestBodyUriSpec, times(1)).uri(eq(CALLBACK_URL));
        verify(requestBodyUriSpec, times(1)).bodyValue(eq(payload));
        assertTrue(attempts.get() >= 2);
    }
}
