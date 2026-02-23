package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.dto.UsageStatsResponse;
import me.golemcore.bot.domain.model.UsageMetric;
import me.golemcore.bot.domain.model.UsageStats;
import me.golemcore.bot.plugin.context.PluginPortResolver;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Token usage analytics endpoints.
 */
@RestController
@RequestMapping("/api/usage")
@RequiredArgsConstructor
public class UsageController {

    private final PluginPortResolver pluginPortResolver;

    @GetMapping("/stats")
    public Mono<ResponseEntity<UsageStatsResponse>> getStats(
            @RequestParam(defaultValue = "24h") String period) {
        UsageTrackingPort usageTrackingPort = pluginPortResolver.requireUsageTrackingPort();
        Duration duration = parsePeriod(period);
        Map<String, UsageStats> allStats = usageTrackingPort.getAllStats(duration);

        long totalRequests = 0;
        long totalInput = 0;
        long totalOutput = 0;
        long totalTokens = 0;
        long totalLatencyMs = 0;
        int statsCount = 0;

        for (UsageStats stats : allStats.values()) {
            totalRequests += stats.getTotalRequests();
            totalInput += stats.getTotalInputTokens();
            totalOutput += stats.getTotalOutputTokens();
            totalTokens += stats.getTotalTokens();
            if (stats.getAvgLatency() != null) {
                totalLatencyMs += stats.getAvgLatency().toMillis();
                statsCount++;
            }
        }

        UsageStatsResponse response = UsageStatsResponse.builder()
                .totalRequests(totalRequests)
                .totalInputTokens(totalInput)
                .totalOutputTokens(totalOutput)
                .totalTokens(totalTokens)
                .avgLatencyMs(statsCount > 0 ? totalLatencyMs / statsCount : 0)
                .build();
        return Mono.just(ResponseEntity.ok(response));
    }

    @GetMapping("/by-model")
    public Mono<ResponseEntity<Map<String, UsageStatsResponse.ModelUsage>>> getByModel(
            @RequestParam(defaultValue = "24h") String period) {
        UsageTrackingPort usageTrackingPort = pluginPortResolver.requireUsageTrackingPort();
        Duration duration = parsePeriod(period);
        Map<String, UsageStats> statsByModel = usageTrackingPort.getStatsByModel(duration);

        Map<String, UsageStatsResponse.ModelUsage> result = new LinkedHashMap<>();
        for (Map.Entry<String, UsageStats> entry : statsByModel.entrySet()) {
            UsageStats stats = entry.getValue();
            result.put(entry.getKey(), UsageStatsResponse.ModelUsage.builder()
                    .requests(stats.getTotalRequests())
                    .inputTokens(stats.getTotalInputTokens())
                    .outputTokens(stats.getTotalOutputTokens())
                    .totalTokens(stats.getTotalTokens())
                    .build());
        }
        return Mono.just(ResponseEntity.ok(result));
    }

    @GetMapping("/export")
    public Mono<ResponseEntity<List<UsageMetric>>> exportMetrics() {
        UsageTrackingPort usageTrackingPort = pluginPortResolver.requireUsageTrackingPort();
        List<UsageMetric> metrics = usageTrackingPort.exportMetrics();
        return Mono.just(ResponseEntity.ok(metrics));
    }

    private Duration parsePeriod(String period) {
        return switch (period) {
        case "7d" -> Duration.ofDays(7);
        case "30d" -> Duration.ofDays(30);
        default -> Duration.ofHours(24);
        };
    }
}
