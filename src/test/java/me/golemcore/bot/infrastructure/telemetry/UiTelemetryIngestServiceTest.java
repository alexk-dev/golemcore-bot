package me.golemcore.bot.infrastructure.telemetry;

import me.golemcore.bot.domain.model.telemetry.UiTelemetryRollup;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UiTelemetryIngestServiceTest {

    private RuntimeConfigService runtimeConfigService;
    private TelemetryEventPublisher publisher;
    private UiTelemetryIngestService service;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        publisher = mock(TelemetryEventPublisher.class);
        service = new UiTelemetryIngestService(runtimeConfigService, publisher);
    }

    @Test
    void shouldSkipPublishingWhenTelemetryIsDisabled() {
        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(false);

        service.ingest(sampleRequest());

        verify(publisher, never()).publishAnonymousEvent(eq("ui_counter"), eq("ui:anon-123"), argThat(anyMap()));
        verify(publisher, never()).publishAnonymousEvent(eq("ui_error_rollup"), eq("ui:anon-123"), argThat(anyMap()));
    }

    @Test
    void shouldPublishFlatCounterAndErrorEvents() {
        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(true);

        service.ingest(sampleRequest());

        verify(publisher).publishAnonymousEvent(
                eq("ui_counter"),
                eq("ui:anon-123"),
                argThat((ArgumentMatcher<Map<String, Object>>) properties -> "settings_open_count"
                        .equals(properties.get("counter_name"))
                        && Long.valueOf(2L).equals(properties.get("count"))
                        && !properties.containsKey("counter_key")));

        verify(publisher).publishAnonymousEvent(
                eq("ui_counter"),
                eq("ui:anon-123"),
                argThat((ArgumentMatcher<Map<String, Object>>) properties -> "settings_section_views_by_key"
                        .equals(properties.get("counter_name"))
                        && "telemetry".equals(properties.get("counter_key"))
                        && Long.valueOf(1L).equals(properties.get("count"))));

        verify(publisher).publishAnonymousEvent(
                eq("ui_counter"),
                eq("ui:anon-123"),
                argThat((ArgumentMatcher<Map<String, Object>>) properties -> "route_view_count"
                        .equals(properties.get("counter_name"))
                        && "/sessions/:id".equals(properties.get("counter_key"))
                        && Long.valueOf(1L).equals(properties.get("count"))));

        ArgumentCaptor<Map<String, Object>> errorCaptor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publishAnonymousEvent(eq("ui_error_rollup"), eq("ui:anon-123"), errorCaptor.capture());
        Map<String, Object> errorProps = errorCaptor.getValue();
        List<?> errorGroups = (List<?>) errorProps.get("error_groups");
        assertEquals(1, errorGroups.size());
        Map<?, ?> firstGroup = (Map<?, ?>) errorGroups.getFirst();
        assertEquals("window|/sessions/:id|TypeError", firstGroup.get("fingerprint"));
        assertEquals("/sessions/:id", firstGroup.get("route"));
        assertEquals(3L, firstGroup.get("count"));
    }

    @Test
    void shouldRejectBlankAnonymousIds() {
        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(true);
        UiTelemetryRollup request = sampleRequest();
        request.setAnonymousId("   ");

        assertThrows(IllegalArgumentException.class, () -> service.ingest(request));
    }

    @Test
    void shouldRejectInvalidPeriodWindow() {
        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(true);
        UiTelemetryRollup request = sampleRequest();
        request.setPeriodEnd(request.getPeriodStart());

        assertThrows(IllegalArgumentException.class, () -> service.ingest(request));
    }

    @Test
    void shouldSkipPublishingWhenCountersAndErrorsCollapseToEmpty() {
        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(true);
        UiTelemetryRollup request = sampleRequest();
        request.getUsage().setCounters(new LinkedHashMap<>(Map.of(" ", 1L, "negative", -1L)));
        request.getUsage().setByRoute(new LinkedHashMap<>(Map.of(
                "route_view_count",
                new LinkedHashMap<>(Map.of(" ", 2L, "/sessions/123e4567-e89b-12d3-a456-426614174000", -1L)))));
        request.getErrors().setGroups(List.of(UiTelemetryRollup.ErrorGroup.builder().count(0).build()));

        service.ingest(request);

        verify(publisher, never()).publishAnonymousEvent(eq("ui_counter"), eq("ui:anon-123"), argThat(anyMap()));
        verify(publisher, never()).publishAnonymousEvent(eq("ui_error_rollup"), eq("ui:anon-123"), argThat(anyMap()));
    }

    private UiTelemetryRollup sampleRequest() {
        UiTelemetryRollup request = new UiTelemetryRollup();
        request.setAnonymousId("anon-123");
        request.setSchemaVersion(1);
        request.setBucketMinutes(15);
        request.setRelease("dashboard-1.2.3");
        request.setPeriodStart(Instant.parse("2026-04-06T10:00:00Z"));
        request.setPeriodEnd(Instant.parse("2026-04-06T10:15:00Z"));

        UiTelemetryRollup.Usage usage = new UiTelemetryRollup.Usage();
        usage.setCounters(new LinkedHashMap<>(Map.of("settings_open_count", 2L)));
        usage.setByRoute(new LinkedHashMap<>(Map.of(
                "settings_section_views_by_key", new LinkedHashMap<>(Map.of("telemetry", 1L)),
                "route_view_count",
                new LinkedHashMap<>(Map.of("/sessions/123e4567-e89b-12d3-a456-426614174000", 1L)))));
        request.setUsage(usage);

        UiTelemetryRollup.ErrorGroup errorGroup = new UiTelemetryRollup.ErrorGroup();
        errorGroup.setFingerprint("spoofed");
        errorGroup.setRoute("/sessions/123e4567-e89b-12d3-a456-426614174000");
        errorGroup.setErrorName("TypeError");
        errorGroup.setMessage("prompt: tell me my secrets");
        errorGroup.setSource("window");
        errorGroup.setComponentStack("at TelemetryTab");
        errorGroup.setCount(3);
        UiTelemetryRollup.Errors errors = new UiTelemetryRollup.Errors();
        errors.setGroups(List.of(errorGroup));
        request.setErrors(errors);
        return request;
    }

    @SuppressWarnings("unchecked")
    private ArgumentMatcher<Map<String, Object>> anyMap() {
        return properties -> true;
    }
}
