package me.golemcore.bot.infrastructure.telemetry;

import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TelemetryEventPublisher {

    private final RuntimeConfigService runtimeConfigService;
    private final PostHogTelemetryClient postHogTelemetryClient;
    private final String appVersion;

    public TelemetryEventPublisher(RuntimeConfigService runtimeConfigService,
            PostHogTelemetryClient postHogTelemetryClient,
            ObjectProvider<BuildProperties> buildPropertiesProvider) {
        this.runtimeConfigService = runtimeConfigService;
        this.postHogTelemetryClient = postHogTelemetryClient;
        BuildProperties buildProps = buildPropertiesProvider.getIfAvailable();
        this.appVersion = buildProps != null ? buildProps.getVersion() : null;
    }

    public void publishAnonymousEvent(String eventName, String distinctId, Map<String, Object> properties) {
        if (!runtimeConfigService.isTelemetryEnabled()) {
            return;
        }
        if (eventName == null || eventName.isBlank() || distinctId == null || distinctId.isBlank()) {
            return;
        }

        Map<String, Object> safeProperties = new LinkedHashMap<>();
        if (properties != null) {
            safeProperties.putAll(properties);
        }
        if (appVersion != null && !appVersion.isBlank()) {
            safeProperties.put("app_version", appVersion);
        }
        safeProperties.put("$geoip_disable", true);
        postHogTelemetryClient.capture(eventName, distinctId, safeProperties);
    }
}
