package me.golemcore.bot.infrastructure.telemetry;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryEventPublisherTest {

    private RuntimeConfigService runtimeConfigService;
    private GaTelemetryClient gaTelemetryClient;
    private TelemetryEventPublisher publisher;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        gaTelemetryClient = mock(GaTelemetryClient.class);
        ObjectProvider<BuildProperties> buildPropertiesProvider = mock(ObjectProvider.class);
        BuildProperties buildProperties = mock(BuildProperties.class);
        when(buildProperties.getVersion()).thenReturn("1.0.0-test");
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(buildProperties);

        RuntimeConfig config = new RuntimeConfig();
        RuntimeConfig.TelemetryConfig telemetryConfig = new RuntimeConfig.TelemetryConfig();
        telemetryConfig.setClientId("test-client-id");
        config.setTelemetry(telemetryConfig);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(config);

        Clock clock = Clock.fixed(Instant.parse("2026-04-08T10:00:00Z"), ZoneOffset.UTC);
        publisher = new TelemetryEventPublisher(runtimeConfigService, gaTelemetryClient, buildPropertiesProvider,
                clock);
    }

    @Test
    void shouldSkipPublishingWhenTelemetryIsDisabled() {
        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(false);

        publisher.publishEvent("model_usage", Map.of("tier", "smart"));

        verify(gaTelemetryClient, never()).sendEvent(anyString(), anyLong(), anyString(), anyMap());
    }

    @Test
    void shouldSkipPublishingWhenEventNameIsBlank() {
        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(true);

        publisher.publishEvent(" ", Map.of());

        verify(gaTelemetryClient, never()).sendEvent(anyString(), anyLong(), anyString(), anyMap());
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldSendEventWithAppVersionAndClientId() {
        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(true);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model_name", "gpt-4o");

        publisher.publishEvent("model_usage", params);

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(gaTelemetryClient).sendEvent(eq("test-client-id"), anyLong(), eq("model_usage"),
                paramsCaptor.capture());
        assertEquals("gpt-4o", paramsCaptor.getValue().get("model_name"));
        assertEquals("1.0.0-test", paramsCaptor.getValue().get("app_version"));
        assertFalse(params.containsKey("app_version"));
    }

    @Test
    void shouldGenerateAndPersistClientIdWhenMissing() {
        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(true);
        RuntimeConfig config = new RuntimeConfig();
        config.setTelemetry(new RuntimeConfig.TelemetryConfig());
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(config);

        publisher.publishEvent("heartbeat", Map.of());

        verify(runtimeConfigService).updateRuntimeConfig(any(RuntimeConfig.class));
        verify(gaTelemetryClient).sendEvent(anyString(), anyLong(), eq("heartbeat"), anyMap());
    }
}
