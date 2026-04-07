package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.TelemetryRollupRequest;
import me.golemcore.bot.domain.model.telemetry.UiTelemetryRollup;
import me.golemcore.bot.infrastructure.telemetry.UiTelemetryIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TelemetryControllerTest {

    private UiTelemetryIngestService ingestService;
    private TelemetryController controller;

    @BeforeEach
    void setUp() {
        ingestService = mock(UiTelemetryIngestService.class);
        controller = new TelemetryController(ingestService);
    }

    @Test
    void shouldAcceptTelemetryRollups() {
        TelemetryRollupRequest request = sampleRequest();

        StepVerifier.create(controller.ingestRollup(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
                    assertEquals("accepted", response.getBody().get("status"));
                })
                .verifyComplete();

        ArgumentCaptor<UiTelemetryRollup> rollupCaptor = ArgumentCaptor.forClass(UiTelemetryRollup.class);
        verify(ingestService).ingest(rollupCaptor.capture());
        UiTelemetryRollup rollup = rollupCaptor.getValue();
        assertNotNull(rollup);
        assertEquals("anon-123", rollup.getAnonymousId());
        assertEquals(15, rollup.getBucketMinutes());
        assertEquals(2L, rollup.getUsage().getCounters().get("settings_open_count"));
        assertEquals(1, rollup.getErrors().getGroups().size());
        assertEquals("window", rollup.getErrors().getGroups().getFirst().getSource());
    }

    @Test
    void shouldReturnBadRequestWhenRollupIsInvalid() {
        TelemetryRollupRequest request = sampleRequest();
        doThrow(new IllegalArgumentException("anonymousId is required")).when(ingestService)
                .ingest(org.mockito.ArgumentMatchers.any(UiTelemetryRollup.class));

        StepVerifier.create(controller.ingestRollup(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
                    assertEquals("anonymousId is required", response.getBody().get("error"));
                })
                .verifyComplete();
    }

    private TelemetryRollupRequest sampleRequest() {
        TelemetryRollupRequest request = new TelemetryRollupRequest();
        request.setAnonymousId("anon-123");
        request.setSchemaVersion(1);
        request.setBucketMinutes(15);
        request.setPeriodStart(Instant.parse("2026-04-06T10:00:00Z"));
        request.setPeriodEnd(Instant.parse("2026-04-06T10:15:00Z"));

        TelemetryRollupRequest.Usage usage = new TelemetryRollupRequest.Usage();
        usage.setCounters(new LinkedHashMap<>(Map.of("settings_open_count", 2L)));
        usage.setByRoute(new LinkedHashMap<>(Map.of(
                "settings_section_views_by_key", new LinkedHashMap<>(Map.of("telemetry", 1L)))));
        request.setUsage(usage);

        TelemetryRollupRequest.ErrorGroup errorGroup = new TelemetryRollupRequest.ErrorGroup();
        errorGroup.setSource("window");
        errorGroup.setRoute("/settings/telemetry");
        errorGroup.setErrorName("TypeError");
        errorGroup.setCount(2);
        TelemetryRollupRequest.Errors errors = new TelemetryRollupRequest.Errors();
        errors.setGroups(java.util.List.of(errorGroup));
        request.setErrors(errors);
        return request;
    }
}
