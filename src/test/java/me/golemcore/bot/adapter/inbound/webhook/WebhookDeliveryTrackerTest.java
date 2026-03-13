package me.golemcore.bot.adapter.inbound.webhook;

import me.golemcore.bot.adapter.inbound.webhook.dto.CallbackPayload;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebhookDeliveryTrackerTest {

    private WebhookCallbackSender callbackSender;
    private BotProperties botProperties;
    private WebhookDeliveryTracker tracker;

    @BeforeEach
    void setUp() {
        callbackSender = mock(WebhookCallbackSender.class);
        botProperties = new BotProperties();
        botProperties.getWebhooks().setDeliveryHistoryMaxEntries(10);
        tracker = new WebhookDeliveryTracker(callbackSender, botProperties);
    }

    @Test
    void shouldRegisterPendingDeliveryAndListIt() {
        String deliveryId = tracker.registerPendingDelivery(
                "run-1",
                "chat-1",
                "https://example.com/callback",
                "smart");

        List<WebhookDeliveryTracker.DeliverySummary> deliveries = tracker.listDeliveries(null, 20);

        assertEquals(1, deliveries.size());
        assertEquals(deliveryId, deliveries.get(0).deliveryId());
        assertEquals("PENDING", deliveries.get(0).status());
    }

    @Test
    void shouldCapturePayloadForDelivery() {
        String deliveryId = tracker.registerPendingDelivery(
                "run-1",
                "chat-1",
                "https://example.com/callback",
                "smart");

        CallbackPayload payload = CallbackPayload.builder()
                .runId("run-1")
                .chatId("chat-1")
                .status("completed")
                .response("done")
                .model("smart")
                .durationMs(99)
                .build();

        tracker.capturePayload(deliveryId, payload, false);

        WebhookDeliveryTracker.DeliveryDetail detail = tracker.getDelivery(deliveryId).orElseThrow();
        assertEquals("completed", detail.payload().status());
        assertEquals("done", detail.payload().response());
        assertEquals("smart", detail.payload().model());
    }

    @Test
    void shouldRetryDeliveryWithStoredPayload() {
        String deliveryId = tracker.registerPendingDelivery(
                "run-retry",
                "chat-retry",
                "https://example.com/callback",
                "smart");

        CallbackPayload payload = CallbackPayload.builder()
                .runId("run-retry")
                .chatId("chat-retry")
                .status("completed")
                .response("done")
                .model("smart")
                .durationMs(200)
                .build();
        tracker.capturePayload(deliveryId, payload, false);

        tracker.retryDelivery(deliveryId);

        verify(callbackSender).send(any(String.class), any(CallbackPayload.class),
                any(WebhookCallbackSender.DeliveryObserver.class));
    }

    @Test
    void shouldSendTrackedTestCallback() {
        WebhookDeliveryTracker.TestCallbackCommand command = new WebhookDeliveryTracker.TestCallbackCommand(
                "https://example.com/callback",
                "run-test",
                "chat-test",
                "balanced",
                "completed",
                "ok",
                100,
                null);

        WebhookDeliveryTracker.DeliveryDetail detail = tracker.sendTestCallback(command);

        assertEquals("run-test", detail.runId());
        assertEquals("test", detail.source());
        verify(callbackSender).send(any(String.class), any(CallbackPayload.class),
                any(WebhookCallbackSender.DeliveryObserver.class));
    }

    @Test
    void shouldUpdateStatusToSuccessViaObserver() {
        String deliveryId = tracker.registerPendingDelivery(
                "run-1",
                "chat-1",
                "https://example.com/callback",
                "smart");

        WebhookCallbackSender.DeliveryObserver observer = tracker.createObserver(deliveryId);

        observer.onAttempt("https://example.com/callback", CallbackPayload.builder().build(), 1);
        observer.onSuccess("https://example.com/callback", CallbackPayload.builder().build(), 1);

        WebhookDeliveryTracker.DeliveryDetail detail = tracker.getDelivery(deliveryId).orElseThrow();
        assertEquals("SUCCESS", detail.status());
        assertEquals(1, detail.attempts());
        assertFalse(detail.events().isEmpty());
    }

    @Test
    void shouldUpdateStatusToFailedViaObserver() {
        String deliveryId = tracker.registerPendingDelivery(
                "run-1",
                "chat-1",
                "https://example.com/callback",
                "smart");

        WebhookCallbackSender.DeliveryObserver observer = tracker.createObserver(deliveryId);

        observer.onAttempt("https://example.com/callback", CallbackPayload.builder().build(), 1);
        observer.onFailure("https://example.com/callback", CallbackPayload.builder().build(), 1,
                new IllegalStateException("network"));

        WebhookDeliveryTracker.DeliveryDetail detail = tracker.getDelivery(deliveryId).orElseThrow();
        assertEquals("FAILED", detail.status());
        assertEquals(1, detail.attempts());
        assertTrue(detail.lastError().contains("network"));
    }

    @Test
    void shouldFilterByStatus() {
        String successId = tracker.registerPendingDelivery(
                "run-success",
                "chat-success",
                "https://example.com/callback",
                "smart");
        String failedId = tracker.registerPendingDelivery(
                "run-failed",
                "chat-failed",
                "https://example.com/callback",
                "smart");

        WebhookCallbackSender.DeliveryObserver successObserver = tracker.createObserver(successId);
        successObserver.onSuccess("https://example.com/callback", CallbackPayload.builder().build(), 1);

        WebhookCallbackSender.DeliveryObserver failedObserver = tracker.createObserver(failedId);
        failedObserver.onFailure("https://example.com/callback", CallbackPayload.builder().build(), 1,
                new IllegalArgumentException("bad"));

        List<WebhookDeliveryTracker.DeliverySummary> successes = tracker.listDeliveries("SUCCESS", 10);
        List<WebhookDeliveryTracker.DeliverySummary> failures = tracker.listDeliveries("FAILED", 10);

        assertEquals(1, successes.size());
        assertEquals(successId, successes.get(0).deliveryId());
        assertEquals(1, failures.size());
        assertEquals(failedId, failures.get(0).deliveryId());
    }

    @Test
    void shouldValidateCallbackUrl() {
        tracker.validateCallbackUrl("https://example.com/callback");
        tracker.validateCallbackUrl("http://localhost:8080/callback");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> tracker.validateCallbackUrl("ftp://example.com/hook"));
        assertNotNull(exception.getMessage());
    }

    @Test
    void shouldValidateStatusFilter() {
        tracker.validateStatusFilter("SUCCESS");
        tracker.validateStatusFilter("pending");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> tracker.validateStatusFilter("unknown-status"));
        assertTrue(exception.getMessage().contains("Unsupported delivery status filter"));
    }
}
