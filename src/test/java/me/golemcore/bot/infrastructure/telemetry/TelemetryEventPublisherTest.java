package me.golemcore.bot.infrastructure.telemetry;

import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryEventPublisherTest {

    private RuntimeConfigService runtimeConfigService;
    private BotProperties botProperties;
    private PostHogTelemetryClient postHogTelemetryClient;
    private TelemetryEventPublisher publisher;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        botProperties = new BotProperties();
        postHogTelemetryClient = mock(PostHogTelemetryClient.class);
        publisher = new TelemetryEventPublisher(runtimeConfigService, botProperties, postHogTelemetryClient);
    }

    @Test
    void shouldSkipPublishingWhenTelemetryIsDisabled() {
        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(false);
        botProperties.getTelemetry().setApiKey("phc_test_key");

        publisher.publishAnonymousEvent("ui_usage_rollup", "ui:anon-123", Map.of("bucket_minutes", 15));

        verify(postHogTelemetryClient, never()).capture(eq("ui_usage_rollup"), eq("ui:anon-123"), anyMap());
    }

    @Test
    void shouldSkipPublishingWhenIdentifiersOrApiKeyAreBlank() {
        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(true);

        publisher.publishAnonymousEvent(" ", "ui:anon-123", Map.of());
        publisher.publishAnonymousEvent("ui_usage_rollup", " ", Map.of());
        publisher.publishAnonymousEvent("ui_usage_rollup", "ui:anon-123", Map.of());

        verify(postHogTelemetryClient, never()).capture(eq("ui_usage_rollup"), eq("ui:anon-123"), anyMap());
    }

    @Test
    void shouldForwardPropertiesWithGeoIpDisabledWithoutMutatingInput() {
        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(true);
        botProperties.getTelemetry().setApiKey("phc_test_key");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("bucket_minutes", 15);

        publisher.publishAnonymousEvent("ui_usage_rollup", "ui:anon-123", properties);

        ArgumentCaptor<Map<String, Object>> propertiesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(postHogTelemetryClient).capture(eq("ui_usage_rollup"), eq("ui:anon-123"), propertiesCaptor.capture());
        assertEquals(15, propertiesCaptor.getValue().get("bucket_minutes"));
        assertEquals(true, propertiesCaptor.getValue().get("$geoip_disable"));
        assertFalse(properties.containsKey("$geoip_disable"));
    }
}
