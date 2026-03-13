package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.webhook.WebhookDeliveryTracker;
import me.golemcore.bot.adapter.inbound.webhook.WebhookDeliveryTracker.DeliveryDetail;
import me.golemcore.bot.adapter.inbound.webhook.WebhookDeliveryTracker.DeliveryEvent;
import me.golemcore.bot.adapter.inbound.webhook.WebhookDeliveryTracker.DeliverySummary;
import me.golemcore.bot.adapter.inbound.webhook.WebhookDeliveryTracker.PayloadSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookDeliveriesControllerTest {

    private WebhookDeliveryTracker webhookDeliveryTracker;
    private WebhookDeliveriesController controller;

    @BeforeEach
    void setUp() {
        webhookDeliveryTracker = mock(WebhookDeliveryTracker.class);
        controller = new WebhookDeliveriesController(webhookDeliveryTracker);
    }

    @Test
    void shouldListDeliveries() {
        DeliverySummary summary = new DeliverySummary(
                "delivery-1",
                "run-1",
                "chat-1",
                "agent",
                "https://example.com/callback",
                "smart",
                "SUCCESS",
                1,
                null,
                Instant.parse("2026-03-13T05:00:00Z"),
                Instant.parse("2026-03-13T05:00:02Z"));
        when(webhookDeliveryTracker.listDeliveries("SUCCESS", 20)).thenReturn(List.of(summary));

        StepVerifier.create(controller.listDeliveries("SUCCESS", 20))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    WebhookDeliveriesController.DeliveryListResponse body = response.getBody();
                    assertEquals(1, body.deliveries().size());
                    assertEquals("delivery-1", body.deliveries().get(0).deliveryId());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnDeliveryDetail() {
        DeliveryDetail detail = buildDetail("delivery-1");
        when(webhookDeliveryTracker.getDelivery("delivery-1")).thenReturn(Optional.of(detail));

        StepVerifier.create(controller.getDelivery("delivery-1"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("delivery-1", response.getBody().deliveryId());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnNotFoundForMissingDeliveryDetail() {
        when(webhookDeliveryTracker.getDelivery("delivery-missing")).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.getDelivery("delivery-missing"));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    @Test
    void shouldRetryDelivery() {
        DeliveryDetail detail = buildDetail("delivery-retry");
        when(webhookDeliveryTracker.retryDelivery("delivery-retry")).thenReturn(Optional.of(detail));

        StepVerifier.create(controller.retryDelivery("delivery-retry"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("delivery-retry", response.getBody().deliveryId());
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectRetryWhenDeliveryCannotBeRetried() {
        when(webhookDeliveryTracker.retryDelivery("delivery-no-callback")).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.retryDelivery("delivery-no-callback"));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void shouldSendTestDelivery() {
        DeliveryDetail detail = buildDetail("delivery-test");
        WebhookDeliveriesController.TestDeliveryRequest request = new WebhookDeliveriesController.TestDeliveryRequest(
                "https://example.com/callback",
                "run-test",
                "chat-test",
                "balanced",
                "completed",
                "ok",
                100L,
                null);

        when(webhookDeliveryTracker.sendTestCallback(org.mockito.ArgumentMatchers.any())).thenReturn(detail);

        StepVerifier.create(controller.sendTestDelivery(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
                    assertEquals("delivery-test", response.getBody().deliveryId());
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectBlankDeliveryId() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.getDelivery(" "));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void shouldRejectMissingTestRequestBody() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.sendTestDelivery(null));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    private static DeliveryDetail buildDetail(String deliveryId) {
        return new DeliveryDetail(
                deliveryId,
                "run-1",
                "chat-1",
                "agent",
                "https://example.com/callback",
                "smart",
                "SUCCESS",
                1,
                null,
                Instant.parse("2026-03-13T05:00:00Z"),
                Instant.parse("2026-03-13T05:00:02Z"),
                new PayloadSnapshot("completed", "ok", "smart", 1200L, null),
                List.of(new DeliveryEvent(1, "SUCCESS", "SUCCESS",
                        Instant.parse("2026-03-13T05:00:02Z"), 1, "Callback delivered")));
    }
}
