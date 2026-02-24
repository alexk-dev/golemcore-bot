package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.UsageStatsResponse;
import me.golemcore.bot.domain.model.UsageStats;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import reactor.test.StepVerifier;

class UsageControllerTest {

    private UsageTrackingPort usageTrackingPort;
    private UsageController controller;

    @BeforeEach
    void setUp() {
        usageTrackingPort = mock(UsageTrackingPort.class);
        controller = new UsageController(usageTrackingPort);
    }

    @Test
    void shouldReturnStatsDefault24h() {
        Map<String, UsageStats> stats = new LinkedHashMap<>();
        UsageStats s = UsageStats.builder()
                .totalRequests(10)
                .totalInputTokens(1000)
                .totalOutputTokens(500)
                .totalTokens(1500)
                .avgLatency(Duration.ofMillis(200))
                .build();
        stats.put("gpt-4o", s);

        when(usageTrackingPort.getAllStats(any(Duration.class))).thenReturn(stats);

        StepVerifier.create(controller.getStats("24h"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    UsageStatsResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals(10, body.getTotalRequests());
                    assertEquals(1000, body.getTotalInputTokens());
                    assertEquals(500, body.getTotalOutputTokens());
                    assertEquals(1500, body.getTotalTokens());
                    assertEquals(200, body.getAvgLatencyMs());
                })
                .verifyComplete();
    }

    @Test
    void shouldParsePeriod7d() {
        when(usageTrackingPort.getAllStats(any(Duration.class))).thenReturn(Map.of());

        StepVerifier.create(controller.getStats("7d"))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldParsePeriod30d() {
        when(usageTrackingPort.getAllStats(any(Duration.class))).thenReturn(Map.of());

        StepVerifier.create(controller.getStats("30d"))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldReturnByModel() {
        Map<String, UsageStats> byModel = new LinkedHashMap<>();
        UsageStats gpt4Stats = UsageStats.builder()
                .totalRequests(5)
                .totalInputTokens(500)
                .totalOutputTokens(250)
                .totalTokens(750)
                .build();
        byModel.put("gpt-4o", gpt4Stats);

        when(usageTrackingPort.getStatsByModel(any(Duration.class))).thenReturn(byModel);

        StepVerifier.create(controller.getByModel("24h"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    Map<String, UsageStatsResponse.ModelUsage> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(1, body.size());
                    UsageStatsResponse.ModelUsage usage = body.get("gpt-4o");
                    assertEquals(5, usage.getRequests());
                })
                .verifyComplete();
    }

    @Test
    void shouldExportMetrics() {
        when(usageTrackingPort.exportMetrics()).thenReturn(List.of());

        StepVerifier.create(controller.exportMetrics())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                })
                .verifyComplete();
    }

    @Test
    void shouldHandleEmptyStats() {
        when(usageTrackingPort.getAllStats(any(Duration.class))).thenReturn(Map.of());

        StepVerifier.create(controller.getStats("24h"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    UsageStatsResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals(0, body.getTotalRequests());
                    assertEquals(0, body.getAvgLatencyMs());
                })
                .verifyComplete();
    }
}
