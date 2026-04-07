package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.inbound.web.dto.TelemetryRollupRequest;
import me.golemcore.bot.domain.model.telemetry.UiTelemetryRollup;
import me.golemcore.bot.telemetry.UiTelemetryIngestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/telemetry")
@RequiredArgsConstructor
@Slf4j
public class TelemetryController {

    private final UiTelemetryIngestService uiTelemetryIngestService;

    @PostMapping("/rollups")
    public Mono<ResponseEntity<Map<String, String>>> ingestRollup(@RequestBody TelemetryRollupRequest request) {
        try {
            UiTelemetryRollup rollup = toUiTelemetryRollup(request);
            if (rollup == null) {
                return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "request body must not be null")));
            }
            uiTelemetryIngestService.ingest(rollup);
            return Mono.just(ResponseEntity.accepted().body(Map.of("status", "accepted")));
        } catch (IllegalArgumentException exception) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", exception.getMessage())));
        }
    }

    private UiTelemetryRollup toUiTelemetryRollup(TelemetryRollupRequest request) {
        if (request == null) {
            return null;
        }
        return UiTelemetryRollup.builder()
                .anonymousId(request.getAnonymousId())
                .schemaVersion(request.getSchemaVersion())
                .release(request.getRelease())
                .periodStart(request.getPeriodStart())
                .periodEnd(request.getPeriodEnd())
                .bucketMinutes(request.getBucketMinutes())
                .usage(toUsage(request.getUsage()))
                .errors(toErrors(request.getErrors()))
                .build();
    }

    private UiTelemetryRollup.Usage toUsage(TelemetryRollupRequest.Usage request) {
        if (request == null) {
            return UiTelemetryRollup.Usage.builder().build();
        }
        return UiTelemetryRollup.Usage.builder()
                .counters(
                        request.getCounters() != null ? new LinkedHashMap<>(request.getCounters())
                                : new LinkedHashMap<>())
                .byRoute(
                        request.getByRoute() != null ? copyKeyedCounters(request.getByRoute())
                                : new LinkedHashMap<>())
                .build();
    }

    private UiTelemetryRollup.Errors toErrors(TelemetryRollupRequest.Errors request) {
        List<UiTelemetryRollup.ErrorGroup> groups = new ArrayList<>();
        if (request != null && request.getGroups() != null) {
            for (TelemetryRollupRequest.ErrorGroup group : request.getGroups()) {
                if (group == null) {
                    continue;
                }
                groups.add(UiTelemetryRollup.ErrorGroup.builder()
                        .fingerprint(group.getFingerprint())
                        .route(group.getRoute())
                        .errorName(group.getErrorName())
                        .message(group.getMessage())
                        .source(group.getSource())
                        .componentStack(group.getComponentStack())
                        .count(group.getCount())
                        .build());
            }
        }
        return UiTelemetryRollup.Errors.builder().groups(groups).build();
    }

    private Map<String, Map<String, Long>> copyKeyedCounters(Map<String, Map<String, Long>> source) {
        Map<String, Map<String, Long>> copy = new LinkedHashMap<>();
        source.forEach(
                (key, value) -> copy.put(key, value != null ? new LinkedHashMap<>(value) : new LinkedHashMap<>()));
        return copy;
    }
}
