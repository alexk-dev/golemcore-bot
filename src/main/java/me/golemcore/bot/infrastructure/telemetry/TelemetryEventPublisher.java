package me.golemcore.bot.infrastructure.telemetry;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TelemetryEventPublisher {

    private final RuntimeConfigService runtimeConfigService;
    private final BotProperties botProperties;
    private final PostHogTelemetryClient postHogTelemetryClient;

    public void publishAnonymousEvent(String eventName, String distinctId, Map<String, Object> properties) {
        if (!runtimeConfigService.isTelemetryEnabled()) {
            return;
        }
        if (eventName == null || eventName.isBlank() || distinctId == null || distinctId.isBlank()) {
            return;
        }
        if (botProperties.getTelemetry().getApiKey() == null || botProperties.getTelemetry().getApiKey().isBlank()) {
            return;
        }

        Map<String, Object> safeProperties = new LinkedHashMap<>();
        if (properties != null) {
            safeProperties.putAll(properties);
        }
        safeProperties.put("$geoip_disable", true);
        postHogTelemetryClient.capture(eventName, distinctId, safeProperties);
    }
}
