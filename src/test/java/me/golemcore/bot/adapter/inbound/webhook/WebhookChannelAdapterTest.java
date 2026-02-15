package me.golemcore.bot.adapter.inbound.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookChannelAdapterTest {

    private WebhookCallbackSender callbackSender;
    private WebhookChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        callbackSender = mock(WebhookCallbackSender.class);
        adapter = new WebhookChannelAdapter(callbackSender);
    }

    @Test
    void shouldReturnWebhookChannelType() {
        assertEquals("webhook", adapter.getChannelType());
    }

    @Test
    void shouldAlwaysAuthorize() {
        assertTrue(adapter.isAuthorized("anyone"));
    }

    @Test
    void shouldAlwaysBeRunning() {
        assertTrue(adapter.isRunning());
    }

    @Test
    void shouldCompletePendingFutureOnSendMessage() throws Exception {
        CompletableFuture<String> future = adapter.registerPendingRun(
                "chat:1", "run-1", null, "balanced");

        adapter.sendMessage("chat:1", "Hello from agent");

        String result = future.get(1, TimeUnit.SECONDS);
        assertEquals("Hello from agent", result);
    }

    @Test
    void shouldSendCallbackOnResponse() {
        adapter.registerPendingRun("chat:2", "run-2", "https://example.com/cb", "smart");

        adapter.sendMessage("chat:2", "Agent response");

        verify(callbackSender).send(eq("https://example.com/cb"), argThat(payload -> "run-2".equals(payload.getRunId())
                && "completed".equals(payload.getStatus())
                && "Agent response".equals(payload.getResponse())
                && "smart".equals(payload.getModel())));
    }

    @Test
    void shouldNotCallbackWhenNoUrlConfigured() {
        adapter.registerPendingRun("chat:3", "run-3", null, "balanced");

        adapter.sendMessage("chat:3", "Response");

        verifyNoInteractions(callbackSender);
    }

    @Test
    void shouldHandleSendWithoutPendingRun() {
        // Should not throw
        assertDoesNotThrow(() -> adapter.sendMessage("unknown-chat", "Orphan response"));
    }

    @Test
    void shouldCancelPendingRunAndSendFailureCallback() {
        CompletableFuture<String> future = adapter.registerPendingRun(
                "chat:4", "run-4", "https://example.com/fail", "coding");

        adapter.cancelPendingRun("chat:4");

        assertTrue(future.isCancelled());
        verify(callbackSender).send(eq("https://example.com/fail"),
                argThat(payload -> "run-4".equals(payload.getRunId())
                        && "failed".equals(payload.getStatus())));
    }

    @Test
    void shouldNotCallbackOnCancelWithoutUrl() {
        CompletableFuture<String> future = adapter.registerPendingRun(
                "chat:5", "run-5", null, "balanced");

        adapter.cancelPendingRun("chat:5");

        assertTrue(future.isCancelled());
        verifyNoInteractions(callbackSender);
    }

    @Test
    void shouldHandleCancelWithoutPendingRun() {
        assertDoesNotThrow(() -> adapter.cancelPendingRun("nonexistent"));
    }
}
