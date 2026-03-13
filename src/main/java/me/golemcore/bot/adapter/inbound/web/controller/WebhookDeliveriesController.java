package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.webhook.WebhookDeliveryTracker;
import me.golemcore.bot.adapter.inbound.webhook.WebhookDeliveryTracker.DeliveryDetail;
import me.golemcore.bot.adapter.inbound.webhook.WebhookDeliveryTracker.DeliverySummary;
import me.golemcore.bot.adapter.inbound.webhook.WebhookDeliveryTracker.TestCallbackCommand;
import me.golemcore.bot.domain.service.StringValueSupport;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Dashboard-facing endpoints for webhook callback deliveries timeline.
 */
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class WebhookDeliveriesController {

    private static final int DEFAULT_DURATION_MS = 1;

    private final WebhookDeliveryTracker webhookDeliveryTracker;

    @GetMapping("/deliveries")
    public Mono<ResponseEntity<DeliveryListResponse>> listDeliveries(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        webhookDeliveryTracker.validateStatusFilter(status);
        List<DeliverySummary> deliveries = webhookDeliveryTracker.listDeliveries(status, limit);
        return Mono.just(ResponseEntity.ok(new DeliveryListResponse(deliveries)));
    }

    @GetMapping("/deliveries/{deliveryId}")
    public Mono<ResponseEntity<DeliveryDetail>> getDelivery(@PathVariable String deliveryId) {
        if (StringValueSupport.isBlank(deliveryId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deliveryId is required");
        }

        DeliveryDetail detail = webhookDeliveryTracker.getDelivery(deliveryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found"));
        return Mono.just(ResponseEntity.ok(detail));
    }

    @PostMapping("/deliveries/{deliveryId}/retry")
    public Mono<ResponseEntity<DeliveryDetail>> retryDelivery(@PathVariable String deliveryId) {
        if (StringValueSupport.isBlank(deliveryId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deliveryId is required");
        }

        DeliveryDetail detail = webhookDeliveryTracker.retryDelivery(deliveryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Delivery cannot be retried (missing callback URL or payload)"));
        return Mono.just(ResponseEntity.ok(detail));
    }

    @PostMapping("/deliveries/test")
    public Mono<ResponseEntity<DeliveryDetail>> sendTestDelivery(
            @RequestBody(required = false) TestDeliveryRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }

        TestCallbackCommand command = new TestCallbackCommand(
                request.callbackUrl(),
                request.runId(),
                request.chatId(),
                request.model(),
                request.payloadStatus(),
                request.response(),
                request.durationMs() != null ? request.durationMs() : DEFAULT_DURATION_MS,
                request.errorMessage());

        DeliveryDetail detail = webhookDeliveryTracker.sendTestCallback(command);
        return Mono.just(ResponseEntity.status(HttpStatus.ACCEPTED).body(detail));
    }

    public record DeliveryListResponse(List<DeliverySummary> deliveries) {
    }

    public record TestDeliveryRequest(
            String callbackUrl,
            String runId,
            String chatId,
            String model,
            String payloadStatus,
            String response,
            Long durationMs,
            String errorMessage) {
    }
}
