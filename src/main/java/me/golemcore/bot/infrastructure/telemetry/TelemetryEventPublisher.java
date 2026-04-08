package me.golemcore.bot.infrastructure.telemetry;

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class TelemetryEventPublisher {

    private final RuntimeConfigService runtimeConfigService;
    private final GaTelemetryClient gaTelemetryClient;
    private final String appVersion;
    private final long sessionId;
    private volatile String cachedClientId;

    public TelemetryEventPublisher(RuntimeConfigService runtimeConfigService,
            GaTelemetryClient gaTelemetryClient,
            ObjectProvider<BuildProperties> buildPropertiesProvider,
            Clock clock) {
        this.runtimeConfigService = runtimeConfigService;
        this.gaTelemetryClient = gaTelemetryClient;
        BuildProperties buildProps = buildPropertiesProvider.getIfAvailable();
        this.appVersion = buildProps != null ? buildProps.getVersion() : null;
        this.sessionId = clock.instant().getEpochSecond();
    }

    /**
     * Publish an event to GA4. The {@code clientId} is resolved from RuntimeConfig
     * (generated and persisted on first call). Parameters are enriched with
     * {@code app_version} automatically.
     */
    public void publishEvent(String eventName, Map<String, Object> params) {
        if (!runtimeConfigService.isTelemetryEnabled()) {
            return;
        }
        if (eventName == null || eventName.isBlank()) {
            return;
        }

        String clientId = resolveClientId();
        Map<String, Object> enrichedParams = new LinkedHashMap<>();
        if (params != null) {
            enrichedParams.putAll(params);
        }
        if (appVersion != null && !appVersion.isBlank()) {
            enrichedParams.put("app_version", appVersion);
        }

        gaTelemetryClient.sendEvent(clientId, sessionId, eventName, enrichedParams);
    }

    private String resolveClientId() {
        String cached = cachedClientId;
        if (cached != null) {
            return cached;
        }
        RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig.TelemetryConfig telemetryConfig = config.getTelemetry();
        if (telemetryConfig != null && telemetryConfig.getClientId() != null
                && !telemetryConfig.getClientId().isBlank()) {
            cachedClientId = telemetryConfig.getClientId();
            return cachedClientId;
        }
        String generated = UUID.randomUUID().toString();
        try {
            if (config.getTelemetry() == null) {
                config.setTelemetry(new RuntimeConfig.TelemetryConfig());
            }
            config.getTelemetry().setClientId(generated);
            runtimeConfigService.updateRuntimeConfig(config);
            log.info("[Telemetry] Generated and persisted GA4 client_id: {}", generated);
        } catch (Exception exception) {
            log.warn("[Telemetry] Failed to persist GA4 client_id: {}", exception.getMessage());
        }
        cachedClientId = generated;
        return generated;
    }
}
