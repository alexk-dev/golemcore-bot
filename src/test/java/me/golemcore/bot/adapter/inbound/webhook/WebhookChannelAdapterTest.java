package me.golemcore.bot.adapter.inbound.webhook;

import me.golemcore.bot.adapter.inbound.webhook.dto.CallbackPayload;
import me.golemcore.bot.domain.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WebhookChannelAdapterTest {

    private static final String CHAT_ID = "hook:test-123";
    private static final String RUN_ID = "run-abc";
    private static final String CALLBACK_URL = "https://example.com/callback";
    private static final String MODEL = "balanced";
    private static final String RESPONSE_TEXT = "Agent response content";

    private WebhookCallbackSender callbackSender;
    private WebhookChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        callbackSender = mock(WebhookCallbackSender.class);
        adapter = new WebhookChannelAdapter(callbackSender);
    }

    // --- ChannelPort basics ---

    @Test
    void shouldReturnWebhookChannelType() {
        assertEquals("webhook", adapter.getChannelType());
    }

    @Test
    void shouldAlwaysBeRunning() {
        assertTrue(adapter.isRunning());
    }

    @Test
    void shouldAlwaysAuthorize() {
        assertTrue(adapter.isAuthorized("any-user"));
    }

    @Test
    void shouldHandleVoiceAsNoOp() throws Exception {
        CompletableFuture<Void> result = adapter.sendVoice(CHAT_ID, new byte[] { 1, 2, 3 });
        assertNull(result.get(1, TimeUnit.SECONDS));
    }

    // --- sendMessage with pending response ---

    @Test
    void shouldCompletePendingFutureOnSendMessage() throws Exception {
        CompletableFuture<String> future = adapter.registerPendingRun(CHAT_ID, RUN_ID, null, MODEL);

        adapter.sendMessage(CHAT_ID, RESPONSE_TEXT);

        assertEquals(RESPONSE_TEXT, future.get(1, TimeUnit.SECONDS));
    }

    @Test
    void shouldSendCallbackOnSendMessageWithCallbackUrl() throws Exception {
        adapter.registerPendingRun(CHAT_ID, RUN_ID, CALLBACK_URL, MODEL);

        adapter.sendMessage(CHAT_ID, RESPONSE_TEXT);

        ArgumentCaptor<CallbackPayload> captor = ArgumentCaptor.forClass(CallbackPayload.class);
        verify(callbackSender).send(eq(CALLBACK_URL), captor.capture());

        CallbackPayload payload = captor.getValue();
        assertEquals(RUN_ID, payload.getRunId());
        assertEquals(CHAT_ID, payload.getChatId());
        assertEquals("completed", payload.getStatus());
        assertEquals(RESPONSE_TEXT, payload.getResponse());
        assertEquals(MODEL, payload.getModel());
        assertTrue(payload.getDurationMs() >= 0);
        assertNull(payload.getError());
    }

    @Test
    void shouldNotSendCallbackWhenNoCallbackUrl() {
        adapter.registerPendingRun(CHAT_ID, RUN_ID, null, MODEL);

        adapter.sendMessage(CHAT_ID, RESPONSE_TEXT);

        verify(callbackSender, never()).send(eq(CALLBACK_URL), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldHandleSendMessageWithoutPendingRun() {
        // No registerPendingRun call — should not throw
        CompletableFuture<Void> result = adapter.sendMessage(CHAT_ID, RESPONSE_TEXT);
        assertNull(result.join());
        verify(callbackSender, never()).send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldDelegateMessageObjectToStringMethod() throws Exception {
        CompletableFuture<String> future = adapter.registerPendingRun(CHAT_ID, RUN_ID, null, MODEL);

        Message message = Message.builder()
                .chatId(CHAT_ID)
                .content(RESPONSE_TEXT)
                .build();
        adapter.sendMessage(message);

        assertEquals(RESPONSE_TEXT, future.get(1, TimeUnit.SECONDS));
    }

    // --- cancelPendingRun ---

    @Test
    void shouldCancelPendingFuture() {
        CompletableFuture<String> future = adapter.registerPendingRun(CHAT_ID, RUN_ID, null, MODEL);

        adapter.cancelPendingRun(CHAT_ID);

        assertTrue(future.isCancelled());
    }

    @Test
    void shouldSendFailureCallbackOnCancel() {
        adapter.registerPendingRun(CHAT_ID, RUN_ID, CALLBACK_URL, MODEL);

        adapter.cancelPendingRun(CHAT_ID);

        ArgumentCaptor<CallbackPayload> captor = ArgumentCaptor.forClass(CallbackPayload.class);
        verify(callbackSender).send(eq(CALLBACK_URL), captor.capture());

        CallbackPayload payload = captor.getValue();
        assertEquals(RUN_ID, payload.getRunId());
        assertEquals(CHAT_ID, payload.getChatId());
        assertEquals("failed", payload.getStatus());
        assertEquals("Run cancelled or timed out", payload.getError());
        assertNull(payload.getResponse());
    }

    @Test
    void shouldNotSendCallbackOnCancelWithoutCallbackUrl() {
        adapter.registerPendingRun(CHAT_ID, RUN_ID, null, MODEL);

        adapter.cancelPendingRun(CHAT_ID);

        verify(callbackSender, never()).send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldHandleCancelWithoutPendingRun() {
        // No registerPendingRun — should not throw
        adapter.cancelPendingRun(CHAT_ID);
        verify(callbackSender, never()).send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    // --- registerPendingRun overwrite ---

    @Test
    void shouldOverwritePreviousPendingRun() throws Exception {
        CompletableFuture<String> first = adapter.registerPendingRun(CHAT_ID, "run-1", null, MODEL);
        CompletableFuture<String> second = adapter.registerPendingRun(CHAT_ID, "run-2", null, MODEL);

        adapter.sendMessage(CHAT_ID, RESPONSE_TEXT);

        // Second future should be completed, first should not
        assertEquals(RESPONSE_TEXT, second.get(1, TimeUnit.SECONDS));
        assertFalse(first.isDone());
    }

    // --- sendMessage cleans up state ---

    @Test
    void shouldCleanUpStateAfterSendMessage() throws Exception {
        adapter.registerPendingRun(CHAT_ID, RUN_ID, CALLBACK_URL, MODEL);
        adapter.sendMessage(CHAT_ID, RESPONSE_TEXT);

        // Second sendMessage should not trigger callback again
        adapter.sendMessage(CHAT_ID, "another");

        verify(callbackSender).send(eq(CALLBACK_URL), org.mockito.ArgumentMatchers.any());
    }
}
