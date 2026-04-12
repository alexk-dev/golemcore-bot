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
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;
    private final Clock clock;
    private volatile String cachedClientId;
    private volatile String resolvedAppVersion;
    private volatile long resolvedSessionId;
    private volatile boolean initialized;

    public TelemetryEventPublisher(RuntimeConfigService runtimeConfigService,
            GaTelemetryClient gaTelemetryClient,
            ObjectProvider<BuildProperties> buildPropertiesProvider,
            Clock clock) {
        this.runtimeConfigService = runtimeConfigService;
        this.gaTelemetryClient = gaTelemetryClient;
        this.buildPropertiesProvider = buildPropertiesProvider;
        this.clock = clock;
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
        ensureInitialized();

        String clientId = resolveClientId();
        Map<String, Object> enrichedParams = new LinkedHashMap<>();
        if (params != null) {
            enrichedParams.putAll(params);
        }
        if (resolvedAppVersion != null && !resolvedAppVersion.isBlank()) {
            enrichedParams.put("app_version", resolvedAppVersion);
        }

        gaTelemetryClient.sendEvent(clientId, resolvedSessionId, eventName, enrichedParams);
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        BuildProperties buildProps = buildPropertiesProvider.getIfAvailable();
        resolvedAppVersion = buildProps != null && buildProps.getVersion() != null ? buildProps.getVersion() : "";
        resolvedSessionId = clock.instant().getEpochSecond();
        initialized = true;
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
