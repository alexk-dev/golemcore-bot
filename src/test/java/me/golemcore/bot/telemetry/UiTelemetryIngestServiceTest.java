package me.golemcore.bot.telemetry;

import me.golemcore.bot.adapter.inbound.web.dto.TelemetryRollupRequest;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        verify(publisher, never()).publishAnonymousEvent(eq("ui_usage_rollup"), eq("ui:anon-123"), argThat(anyMap()));
        verify(publisher, never()).publishAnonymousEvent(eq("ui_error_rollup"), eq("ui:anon-123"), argThat(anyMap()));
    }

    @Test
    void shouldPublishAnonymousUsageAndErrorRollups() {
        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(true);

        service.ingest(sampleRequest());

        verify(publisher).publishAnonymousEvent(
                eq("ui_usage_rollup"),
                eq("ui:anon-123"),
                argThat((ArgumentMatcher<Map<String, Object>>) properties -> {
                    Object counters = properties.get("counters");
                    Object keyedCounters = properties.get("keyed_counters");
                    return counters instanceof Map<?, ?> counterMap
                            && keyedCounters instanceof Map<?, ?> keyedCounterMap
                            && counterMap.get("settings_open_count").equals(2L)
                            && keyedCounterMap.containsKey("settings_section_views_by_key");
                }));

        verify(publisher).publishAnonymousEvent(
                eq("ui_error_rollup"),
                eq("ui:anon-123"),
                argThat((ArgumentMatcher<Map<String, Object>>) properties -> {
                    Object errorGroups = properties.get("error_groups");
                    if (!(errorGroups instanceof List<?> groups) || groups.isEmpty()) {
                        return false;
                    }
                    Object first = groups.getFirst();
                    if (!(first instanceof Map<?, ?> firstGroup)) {
                        return false;
                    }
                    return firstGroup.get("fingerprint").equals("window|/settings|TypeError|Boom")
                            && firstGroup.get("count").equals(3L)
                            && !firstGroup.containsKey("message")
                            && !firstGroup.containsKey("componentStack");
                }));
    }

    @Test
    void shouldRejectBlankAnonymousIds() {
        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(true);
        TelemetryRollupRequest request = sampleRequest();
        request.setAnonymousId("   ");

        assertThrows(IllegalArgumentException.class, () -> service.ingest(request));
    }

    private TelemetryRollupRequest sampleRequest() {
        TelemetryRollupRequest request = new TelemetryRollupRequest();
        request.setAnonymousId("anon-123");
        request.setSchemaVersion(1);
        request.setBucketMinutes(15);
        request.setRelease("dashboard-1.2.3");
        request.setPeriodStart(Instant.parse("2026-04-06T10:00:00Z"));
        request.setPeriodEnd(Instant.parse("2026-04-06T10:15:00Z"));

        TelemetryRollupRequest.Usage usage = new TelemetryRollupRequest.Usage();
        usage.setCounters(new LinkedHashMap<>(Map.of("settings_open_count", 2L)));
        usage.setByRoute(new LinkedHashMap<>(Map.of(
                "settings_section_views_by_key", new LinkedHashMap<>(Map.of("telemetry", 1L)))));
        request.setUsage(usage);

        TelemetryRollupRequest.ErrorGroup errorGroup = new TelemetryRollupRequest.ErrorGroup();
        errorGroup.setFingerprint("window|/settings|TypeError|Boom");
        errorGroup.setRoute("/settings");
        errorGroup.setErrorName("TypeError");
        errorGroup.setMessage("Boom");
        errorGroup.setSource("window");
        errorGroup.setComponentStack("at TelemetryTab");
        errorGroup.setCount(3);
        TelemetryRollupRequest.Errors errors = new TelemetryRollupRequest.Errors();
        errors.setGroups(List.of(errorGroup));
        request.setErrors(errors);
        return request;
    }

    @SuppressWarnings("unchecked")
    private ArgumentMatcher<Map<String, Object>> anyMap() {
        return properties -> true;
    }
}
