package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.dto.TelemetryRollupRequest;
import me.golemcore.bot.telemetry.UiTelemetryIngestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/telemetry")
@RequiredArgsConstructor
public class TelemetryController {

    private final UiTelemetryIngestService uiTelemetryIngestService;

    @PostMapping("/rollups")
    public Mono<ResponseEntity<Map<String, String>>> ingestRollup(@RequestBody TelemetryRollupRequest request) {
        try {
            uiTelemetryIngestService.ingest(request);
            return Mono.just(ResponseEntity.accepted().body(Map.of("status", "accepted")));
        } catch (IllegalArgumentException exception) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", exception.getMessage())));
        }
    }
}
