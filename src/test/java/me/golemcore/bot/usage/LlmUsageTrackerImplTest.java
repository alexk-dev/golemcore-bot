package me.golemcore.bot.usage;

import me.golemcore.bot.domain.model.LlmUsage;
import me.golemcore.bot.domain.model.UsageMetric;
import me.golemcore.bot.domain.model.UsageStats;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmUsageTrackerImplTest {

    private StoragePort storagePort;
    private BotProperties properties;
    private ObjectMapper objectMapper;
    private LlmUsageTrackerImpl tracker;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        properties = new BotProperties();
        properties.getUsage().setEnabled(true);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        // No persisted files on startup
        when(storagePort.listObjects(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        tracker = new LlmUsageTrackerImpl(storagePort, properties, objectMapper);
        tracker.init();
    }

    // ===== Record + Get Stats =====

    @Test
    void recordsAndRetrievesUsage() {
        LlmUsage usage = LlmUsage.builder()
                .inputTokens(100).outputTokens(50).totalTokens(150)
                .timestamp(Instant.now())
                .latency(Duration.ofMillis(500))
                .build();

        tracker.recordUsage("langchain4j", "gpt-5.1", usage);

        UsageStats stats = tracker.getStats("langchain4j", Duration.ofHours(1));
        assertEquals(1, stats.getTotalRequests());
        assertEquals(100, stats.getTotalInputTokens());
        assertEquals(50, stats.getTotalOutputTokens());
        assertEquals(150, stats.getTotalTokens());
    }

    @Test
    void setsProviderAndModelOnUsage() {
        LlmUsage usage = LlmUsage.builder()
                .inputTokens(10).outputTokens(5).totalTokens(15)
                .build();

        tracker.recordUsage("langchain4j", "gpt-5.1", usage);

        assertEquals("langchain4j", usage.getProviderId());
        assertEquals("gpt-5.1", usage.getModel());
        assertNotNull(usage.getTimestamp());
    }

    @Test
    void persistsUsageToStorage() {
        LlmUsage usage = LlmUsage.builder()
                .inputTokens(10).outputTokens(5).totalTokens(15)
                .timestamp(Instant.now())
                .build();

        tracker.recordUsage("langchain4j", "gpt-5.1", usage);

        verify(storagePort).appendText(eq("usage"), contains("langchain4j"), contains("gpt-5.1"));
    }

    // ===== Aggregation =====

    @Test
    void aggregatesMultipleUsages() {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            LlmUsage usage = LlmUsage.builder()
                    .inputTokens(100).outputTokens(50).totalTokens(150)
                    .timestamp(now)
                    .latency(Duration.ofMillis(200))
                    .build();
            tracker.recordUsage("langchain4j", "gpt-5.1", usage);
        }

        UsageStats stats = tracker.getStats("langchain4j", Duration.ofHours(1));
        assertEquals(5, stats.getTotalRequests());
        assertEquals(500, stats.getTotalInputTokens());
        assertEquals(250, stats.getTotalOutputTokens());
        assertEquals(750, stats.getTotalTokens());
    }

    // ===== getStatsByModel =====

    @Test
    void groupsStatsByModel() {
        Instant now = Instant.now();
        tracker.recordUsage("langchain4j", "gpt-5.1", usage(100, 50, now));
        tracker.recordUsage("langchain4j", "gpt-5.1", usage(200, 100, now));
        tracker.recordUsage("langchain4j", "gpt-5.2", usage(300, 150, now));

        Map<String, UsageStats> byModel = tracker.getStatsByModel(Duration.ofHours(1));

        assertEquals(2, byModel.size());
        assertTrue(byModel.containsKey("langchain4j/gpt-5.1"));
        assertTrue(byModel.containsKey("langchain4j/gpt-5.2"));

        assertEquals(2, byModel.get("langchain4j/gpt-5.1").getTotalRequests());
        assertEquals(1, byModel.get("langchain4j/gpt-5.2").getTotalRequests());
        assertEquals(450, byModel.get("langchain4j/gpt-5.2").getTotalTokens());
    }

    // ===== Period filtering =====

    @Test
    void filtersOldUsageByPeriod() {
        // Recent usage
        tracker.recordUsage("langchain4j", "gpt-5.1", usage(100, 50, Instant.now()));
        // Old usage (8 days ago)
        LlmUsage old = LlmUsage.builder()
                .inputTokens(999).outputTokens(999).totalTokens(1998)
                .timestamp(Instant.now().minus(Duration.ofDays(8)))
                .build();
        tracker.recordUsage("langchain4j", "gpt-5.1", old);

        // Query last 24h — should only see the recent one
        UsageStats stats = tracker.getStats("langchain4j", Duration.ofHours(24));
        assertEquals(1, stats.getTotalRequests());
        assertEquals(150, stats.getTotalTokens());
    }

    // ===== Disabled =====

    @Test
    void skipsRecordingWhenDisabled() {
        properties.getUsage().setEnabled(false);

        LlmUsage usage = usage(100, 50, Instant.now());
        tracker.recordUsage("langchain4j", "gpt-5.1", usage);

        UsageStats stats = tracker.getStats("langchain4j", Duration.ofHours(1));
        assertEquals(0, stats.getTotalRequests());
        verify(storagePort, never()).appendText(anyString(), anyString(), anyString());
    }

    // ===== Empty stats =====

    @Test
    void returnsEmptyStatsForUnknownProvider() {
        UsageStats stats = tracker.getStats("unknown", Duration.ofHours(1));
        assertEquals(0, stats.getTotalRequests());
        assertEquals(0, stats.getTotalTokens());
    }

    // ===== Export metrics =====

    @Test
    void exportsMetrics() {
        tracker.recordUsage("langchain4j", "gpt-5.1", usage(100, 50, Instant.now()));

        List<UsageMetric> metrics = tracker.exportMetrics();
        assertFalse(metrics.isEmpty());
        assertTrue(metrics.stream().anyMatch(m -> "llm.requests.total".equals(m.getName())));
        assertTrue(metrics.stream().anyMatch(m -> "llm.tokens.total".equals(m.getName())));
    }

    // ===== Primary model detection =====

    @Test
    void detectsPrimaryModel() {
        Instant now = Instant.now();
        // 3x gpt-5.1, 1x gpt-5.2 → primary is gpt-5.1
        tracker.recordUsage("p", "gpt-5.1", usage(10, 5, now));
        tracker.recordUsage("p", "gpt-5.1", usage(10, 5, now));
        tracker.recordUsage("p", "gpt-5.1", usage(10, 5, now));
        tracker.recordUsage("p", "gpt-5.2", usage(10, 5, now));

        UsageStats stats = tracker.getStats("p", Duration.ofHours(1));
        assertEquals("gpt-5.1", stats.getModel());
    }

    // ===== Loading persisted data =====

    @Test
    void shouldSkipMalformedJsonlLinesOnLoad() {
        String validLine = "{\"inputTokens\":100,\"outputTokens\":50,\"totalTokens\":150,\"providerId\":\"p\",\"model\":\"m\",\"timestamp\":\""
                + Instant.now() + "\"}";
        String mixedContent = validLine + "\nthis is not json\n" + validLine;

        when(storagePort.listObjects("usage", ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("test.jsonl")));
        when(storagePort.getText("usage", "test.jsonl"))
                .thenReturn(CompletableFuture.completedFuture(mixedContent));

        LlmUsageTrackerImpl freshTracker = new LlmUsageTrackerImpl(storagePort, properties, objectMapper);
        freshTracker.init();

        // Should have loaded 2 valid records, skipping the malformed one
        UsageStats stats = freshTracker.getStats("p", Duration.ofHours(1));
        assertEquals(2, stats.getTotalRequests());
    }

    @Test
    void shouldHandleEmptyFileOnLoad() {
        when(storagePort.listObjects("usage", ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("empty.jsonl")));
        when(storagePort.getText("usage", "empty.jsonl"))
                .thenReturn(CompletableFuture.completedFuture(""));

        LlmUsageTrackerImpl freshTracker = new LlmUsageTrackerImpl(storagePort, properties, objectMapper);
        assertDoesNotThrow(() -> freshTracker.init());
    }

    @Test
    void shouldSkipNonJsonlFilesOnLoad() {
        when(storagePort.listObjects("usage", ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("readme.txt", "data.csv")));

        LlmUsageTrackerImpl freshTracker = new LlmUsageTrackerImpl(storagePort, properties, objectMapper);
        assertDoesNotThrow(() -> freshTracker.init());
        // Should not attempt to read non-jsonl files
        verify(storagePort, never()).getText(eq("usage"), eq("readme.txt"));
    }

    @Test
    void shouldSkipOldRecordsOnLoad() {
        Instant oldTimestamp = Instant.now().minus(Duration.ofDays(10));
        String oldLine = "{\"inputTokens\":100,\"outputTokens\":50,\"totalTokens\":150,\"providerId\":\"p\",\"model\":\"m\",\"timestamp\":\""
                + oldTimestamp + "\"}";

        when(storagePort.listObjects("usage", ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("old.jsonl")));
        when(storagePort.getText("usage", "old.jsonl"))
                .thenReturn(CompletableFuture.completedFuture(oldLine));

        LlmUsageTrackerImpl freshTracker = new LlmUsageTrackerImpl(storagePort, properties, objectMapper);
        freshTracker.init();

        UsageStats stats = freshTracker.getStats("p", Duration.ofHours(24));
        assertEquals(0, stats.getTotalRequests());
    }

    @Test
    void shouldHandleStorageFailureOnLoadGracefully() {
        when(storagePort.listObjects("usage", ""))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Storage unavailable")));

        LlmUsageTrackerImpl freshTracker = new LlmUsageTrackerImpl(storagePort, properties, objectMapper);
        assertDoesNotThrow(() -> freshTracker.init());
    }

    @Test
    void shouldSkipLoadingWhenDisabled() {
        properties.getUsage().setEnabled(false);

        when(storagePort.listObjects("usage", ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("data.jsonl")));

        LlmUsageTrackerImpl freshTracker = new LlmUsageTrackerImpl(storagePort, properties, objectMapper);
        freshTracker.init();

        // Should not list objects when disabled
        verify(storagePort, never()).getText(eq("usage"), anyString());
    }

    // ===== getAllStats =====

    @Test
    void shouldReturnAllProviderStats() {
        Instant now = Instant.now();
        tracker.recordUsage("openai", "gpt-5.1", usage(100, 50, now));
        tracker.recordUsage("anthropic", "claude-4", usage(200, 100, now));

        Map<String, UsageStats> allStats = tracker.getAllStats(Duration.ofHours(1));

        assertEquals(2, allStats.size());
        assertTrue(allStats.containsKey("openai"));
        assertTrue(allStats.containsKey("anthropic"));
    }

    // ===== Persist failure =====

    @Test
    void shouldHandlePersistFailureGracefully() {
        when(storagePort.appendText(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Disk full"));

        LlmUsage usage = usage(100, 50, Instant.now());
        assertDoesNotThrow(() -> tracker.recordUsage("p", "m", usage));

        // Record should still be in memory even though persistence failed
        UsageStats stats = tracker.getStats("p", Duration.ofHours(1));
        assertEquals(1, stats.getTotalRequests());
    }

    private LlmUsage usage(int input, int output, Instant ts) {
        return LlmUsage.builder()
                .inputTokens(input).outputTokens(output).totalTokens(input + output)
                .timestamp(ts)
                .latency(Duration.ofMillis(100))
                .build();
    }
}
