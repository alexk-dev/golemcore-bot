package me.golemcore.bot.infrastructure.telemetry;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.telemetry.UiTelemetryRollup;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UiTelemetryIngestService {

    private final RuntimeConfigService runtimeConfigService;
    private final TelemetryEventPublisher telemetryEventPublisher;

    public void ingest(UiTelemetryRollup request) {
        validate(request);
        if (!runtimeConfigService.isTelemetryEnabled()) {
            return;
        }

        String distinctId = "ui:" + request.getAnonymousId().trim();
        publishFlatCounters(request, distinctId);
        Map<String, Object> errorProperties = buildErrorProperties(request);
        if (hasErrorData(errorProperties)) {
            telemetryEventPublisher.publishAnonymousEvent("ui_error_rollup", distinctId, errorProperties);
        }
    }

    private void validate(UiTelemetryRollup request) {
        if (request == null) {
            throw new IllegalArgumentException("Telemetry rollup is required");
        }
        if (request.getAnonymousId() == null || request.getAnonymousId().trim().isEmpty()) {
            throw new IllegalArgumentException("anonymousId is required");
        }
        if (request.getPeriodStart() == null || request.getPeriodEnd() == null) {
            throw new IllegalArgumentException("periodStart and periodEnd are required");
        }
        if (!request.getPeriodEnd().isAfter(request.getPeriodStart())) {
            throw new IllegalArgumentException("periodEnd must be after periodStart");
        }
        if (request.getBucketMinutes() == null || request.getBucketMinutes() <= 0) {
            throw new IllegalArgumentException("bucketMinutes must be positive");
        }
    }

    private void publishFlatCounters(UiTelemetryRollup request, String distinctId) {
        Map<String, Long> counters = sanitizeCounters(
                request.getUsage() != null ? request.getUsage().getCounters() : Map.of());
        for (Map.Entry<String, Long> entry : counters.entrySet()) {
            Map<String, Object> properties = baseProperties(request);
            properties.put("counter_name", entry.getKey());
            properties.put("count", entry.getValue());
            telemetryEventPublisher.publishAnonymousEvent("ui_counter", distinctId, properties);
        }

        Map<String, Map<String, Long>> keyedCounters = sanitizeKeyedCounters(
                request.getUsage() != null ? request.getUsage().getByRoute() : Map.of());
        for (Map.Entry<String, Map<String, Long>> outer : keyedCounters.entrySet()) {
            for (Map.Entry<String, Long> inner : outer.getValue().entrySet()) {
                Map<String, Object> properties = baseProperties(request);
                properties.put("counter_name", outer.getKey());
                properties.put("counter_key", inner.getKey());
                properties.put("count", inner.getValue());
                telemetryEventPublisher.publishAnonymousEvent("ui_counter", distinctId, properties);
            }
        }
    }

    private boolean hasErrorData(Map<String, Object> errorProperties) {
        Object errorGroups = errorProperties.get("error_groups");
        return errorGroups instanceof List<?> groups && !groups.isEmpty();
    }

    private Map<String, Object> buildErrorProperties(UiTelemetryRollup request) {
        Map<String, Object> properties = baseProperties(request);
        List<Map<String, Object>> errorGroups = new ArrayList<>();
        for (UiTelemetryRollup.ErrorGroup group : request.getErrors().getGroups()) {
            if (group == null || group.getCount() == null || group.getCount() <= 0) {
                continue;
            }
            Map<String, Object> errorGroup = new LinkedHashMap<>();
            String sanitizedRoute = TelemetrySanitizer.sanitizeRoute(group.getRoute());
            String sanitizedErrorName = TelemetrySanitizer.sanitizeErrorName(group.getErrorName());
            String sanitizedSource = TelemetrySanitizer.sanitizeErrorSource(group.getSource());
            errorGroup.put("fingerprint",
                    TelemetrySanitizer.createUiErrorFingerprint(sanitizedSource, sanitizedRoute, sanitizedErrorName));
            errorGroup.put("route", sanitizedRoute);
            errorGroup.put("error_name", sanitizedErrorName);
            errorGroup.put("source", sanitizedSource);
            errorGroup.put("count", group.getCount().longValue());
            errorGroups.add(errorGroup);
        }
        properties.put("error_groups", errorGroups);
        return properties;
    }

    private Map<String, Object> baseProperties(UiTelemetryRollup request) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("schema_version", request.getSchemaVersion());
        properties.put("period_start", toIsoString(request.getPeriodStart()));
        properties.put("period_end", toIsoString(request.getPeriodEnd()));
        properties.put("bucket_minutes", request.getBucketMinutes());
        putIfNotBlank(properties, "release", request.getRelease());
        return properties;
    }

    private Map<String, Long> sanitizeCounters(Map<String, Long> counters) {
        Map<String, Long> sanitized = new LinkedHashMap<>();
        if (counters == null) {
            return sanitized;
        }
        counters.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value <= 0) {
                return;
            }
            sanitized.put(key.trim(), value);
        });
        return sanitized;
    }

    private Map<String, Map<String, Long>> sanitizeKeyedCounters(Map<String, Map<String, Long>> keyedCounters) {
        Map<String, Map<String, Long>> sanitized = new LinkedHashMap<>();
        if (keyedCounters == null) {
            return sanitized;
        }
        keyedCounters.forEach((key, values) -> {
            if (key == null || key.isBlank() || values == null || values.isEmpty()) {
                return;
            }
            Map<String, Long> sanitizedValues = new LinkedHashMap<>();
            values.forEach((valueKey, count) -> {
                if (valueKey == null || valueKey.isBlank() || count == null || count <= 0) {
                    return;
                }
                String sanitizedValueKey = TelemetrySanitizer.sanitizeUsageValue(key.trim(), valueKey);
                sanitizedValues.merge(sanitizedValueKey, count, Long::sum);
            });
            if (!sanitizedValues.isEmpty()) {
                sanitized.put(key.trim(), sanitizedValues);
            }
        });
        return sanitized;
    }

    private String toIsoString(Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (target == null || key == null || value == null || value.isBlank()) {
            return;
        }
        target.put(key, value);
    }
}
