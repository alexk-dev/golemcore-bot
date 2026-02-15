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

    private static final String PROVIDER_LANGCHAIN4J = "langchain4j";
    private static final String MODEL_GPT_51 = "gpt-5.1";
    private static final String USAGE_DIR = "usage";
    private static final String MODEL_GPT_52 = "gpt-5.2";
    private static final String KEY_LANGCHAIN4J_GPT51 = PROVIDER_LANGCHAIN4J + "/" + MODEL_GPT_51;
    private static final String KEY_LANGCHAIN4J_GPT52 = PROVIDER_LANGCHAIN4J + "/" + MODEL_GPT_52;

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

        tracker.recordUsage(PROVIDER_LANGCHAIN4J, MODEL_GPT_51, usage);

        UsageStats stats = tracker.getStats(PROVIDER_LANGCHAIN4J, Duration.ofHours(1));
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

        tracker.recordUsage(PROVIDER_LANGCHAIN4J, MODEL_GPT_51, usage);

        assertEquals(PROVIDER_LANGCHAIN4J, usage.getProviderId());
        assertEquals(MODEL_GPT_51, usage.getModel());
        assertNotNull(usage.getTimestamp());
    }

    @Test
    void persistsUsageToStorage() {
        LlmUsage usage = LlmUsage.builder()
                .inputTokens(10).outputTokens(5).totalTokens(15)
                .timestamp(Instant.now())
                .build();

        tracker.recordUsage(PROVIDER_LANGCHAIN4J, MODEL_GPT_51, usage);

        verify(storagePort).appendText(eq(USAGE_DIR), contains(PROVIDER_LANGCHAIN4J), contains(MODEL_GPT_51));
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
            tracker.recordUsage(PROVIDER_LANGCHAIN4J, MODEL_GPT_51, usage);
        }

        UsageStats stats = tracker.getStats(PROVIDER_LANGCHAIN4J, Duration.ofHours(1));
        assertEquals(5, stats.getTotalRequests());
        assertEquals(500, stats.getTotalInputTokens());
        assertEquals(250, stats.getTotalOutputTokens());
        assertEquals(750, stats.getTotalTokens());
    }

    // ===== getStatsByModel =====

    @Test
    void groupsStatsByModel() {
        Instant now = Instant.now();
        tracker.recordUsage(PROVIDER_LANGCHAIN4J, MODEL_GPT_51, usage(100, 50, now));
        tracker.recordUsage(PROVIDER_LANGCHAIN4J, MODEL_GPT_51, usage(200, 100, now));
        tracker.recordUsage(PROVIDER_LANGCHAIN4J, MODEL_GPT_52, usage(300, 150, now));

        Map<String, UsageStats> byModel = tracker.getStatsByModel(Duration.ofHours(1));

        assertEquals(2, byModel.size());
        assertTrue(byModel.containsKey(KEY_LANGCHAIN4J_GPT51));
        assertTrue(byModel.containsKey(KEY_LANGCHAIN4J_GPT52));

        assertEquals(2, byModel.get(KEY_LANGCHAIN4J_GPT51).getTotalRequests());
        assertEquals(1, byModel.get(KEY_LANGCHAIN4J_GPT52).getTotalRequests());
        assertEquals(450, byModel.get(KEY_LANGCHAIN4J_GPT52).getTotalTokens());
    }

    // ===== Period filtering =====

    @Test
    void filtersOldUsageByPeriod() {
        // Recent usage
        tracker.recordUsage(PROVIDER_LANGCHAIN4J, MODEL_GPT_51, usage(100, 50, Instant.now()));
        // Old usage (8 days ago)
        LlmUsage old = LlmUsage.builder()
                .inputTokens(999).outputTokens(999).totalTokens(1998)
                .timestamp(Instant.now().minus(Duration.ofDays(8)))
                .build();
        tracker.recordUsage(PROVIDER_LANGCHAIN4J, MODEL_GPT_51, old);

        // Query last 24h — should only see the recent one
        UsageStats stats = tracker.getStats(PROVIDER_LANGCHAIN4J, Duration.ofHours(24));
        assertEquals(1, stats.getTotalRequests());
        assertEquals(150, stats.getTotalTokens());
    }

    // ===== Disabled =====

    @Test
    void skipsRecordingWhenDisabled() {
        properties.getUsage().setEnabled(false);

        LlmUsage usage = usage(100, 50, Instant.now());
        tracker.recordUsage(PROVIDER_LANGCHAIN4J, MODEL_GPT_51, usage);

        UsageStats stats = tracker.getStats(PROVIDER_LANGCHAIN4J, Duration.ofHours(1));
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
        tracker.recordUsage(PROVIDER_LANGCHAIN4J, MODEL_GPT_51, usage(100, 50, Instant.now()));

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
        tracker.recordUsage("p", MODEL_GPT_51, usage(10, 5, now));
        tracker.recordUsage("p", MODEL_GPT_51, usage(10, 5, now));
        tracker.recordUsage("p", MODEL_GPT_51, usage(10, 5, now));
        tracker.recordUsage("p", MODEL_GPT_52, usage(10, 5, now));

        UsageStats stats = tracker.getStats("p", Duration.ofHours(1));
        assertEquals(MODEL_GPT_51, stats.getModel());
    }

    // ===== Loading persisted data =====

    @Test
    void shouldSkipMalformedJsonlLinesOnLoad() {
        String validLine = "{\"inputTokens\":100,\"outputTokens\":50,\"totalTokens\":150,\"providerId\":\"p\",\"model\":\"m\",\"timestamp\":\""
                + Instant.now() + "\"}";
        String mixedContent = validLine + "\nthis is not json\n" + validLine;

        when(storagePort.listObjects(USAGE_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("test.jsonl")));
        when(storagePort.getText(USAGE_DIR, "test.jsonl"))
                .thenReturn(CompletableFuture.completedFuture(mixedContent));

        LlmUsageTrackerImpl freshTracker = new LlmUsageTrackerImpl(storagePort, properties, objectMapper);
        freshTracker.init();

        // Should have loaded 2 valid records, skipping the malformed one
        UsageStats stats = freshTracker.getStats("p", Duration.ofHours(1));
        assertEquals(2, stats.getTotalRequests());
    }

    @Test
    void shouldHandleEmptyFileOnLoad() {
        when(storagePort.listObjects(USAGE_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("empty.jsonl")));
        when(storagePort.getText(USAGE_DIR, "empty.jsonl"))
                .thenReturn(CompletableFuture.completedFuture(""));

        LlmUsageTrackerImpl freshTracker = new LlmUsageTrackerImpl(storagePort, properties, objectMapper);
        assertDoesNotThrow(() -> freshTracker.init());
    }

    @Test
    void shouldSkipNonJsonlFilesOnLoad() {
        when(storagePort.listObjects(USAGE_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("readme.txt", "data.csv")));

        LlmUsageTrackerImpl freshTracker = new LlmUsageTrackerImpl(storagePort, properties, objectMapper);
        assertDoesNotThrow(() -> freshTracker.init());
        // Should not attempt to read non-jsonl files
        verify(storagePort, never()).getText(eq(USAGE_DIR), eq("readme.txt"));
    }

    @Test
    void shouldSkipOldRecordsOnLoad() {
        Instant oldTimestamp = Instant.now().minus(Duration.ofDays(10));
        String oldLine = "{\"inputTokens\":100,\"outputTokens\":50,\"totalTokens\":150,\"providerId\":\"p\",\"model\":\"m\",\"timestamp\":\""
                + oldTimestamp + "\"}";

        when(storagePort.listObjects(USAGE_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("old.jsonl")));
        when(storagePort.getText(USAGE_DIR, "old.jsonl"))
                .thenReturn(CompletableFuture.completedFuture(oldLine));

        LlmUsageTrackerImpl freshTracker = new LlmUsageTrackerImpl(storagePort, properties, objectMapper);
        freshTracker.init();

        UsageStats stats = freshTracker.getStats("p", Duration.ofHours(24));
        assertEquals(0, stats.getTotalRequests());
    }

    @Test
    void shouldHandleStorageFailureOnLoadGracefully() {
        when(storagePort.listObjects(USAGE_DIR, ""))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Storage unavailable")));

        LlmUsageTrackerImpl freshTracker = new LlmUsageTrackerImpl(storagePort, properties, objectMapper);
        assertDoesNotThrow(() -> freshTracker.init());
    }

    @Test
    void shouldSkipLoadingWhenDisabled() {
        properties.getUsage().setEnabled(false);

        when(storagePort.listObjects(USAGE_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("data.jsonl")));

        LlmUsageTrackerImpl freshTracker = new LlmUsageTrackerImpl(storagePort, properties, objectMapper);
        freshTracker.init();

        // Should not list objects when disabled
        verify(storagePort, never()).getText(eq(USAGE_DIR), anyString());
    }

    // ===== getAllStats =====

    @Test
    void shouldReturnAllProviderStats() {
        Instant now = Instant.now();
        tracker.recordUsage("openai", MODEL_GPT_51, usage(100, 50, now));
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

    // ===== Average latency =====

    @Test
    void shouldComputeAverageLatency() {
        Instant now = Instant.now();
        LlmUsage usage1 = LlmUsage.builder()
                .inputTokens(10).outputTokens(5).totalTokens(15)
                .timestamp(now).latency(Duration.ofMillis(100))
                .build();
        LlmUsage usage2 = LlmUsage.builder()
                .inputTokens(10).outputTokens(5).totalTokens(15)
                .timestamp(now).latency(Duration.ofMillis(300))
                .build();

        tracker.recordUsage("p", "m", usage1);
        tracker.recordUsage("p", "m", usage2);

        UsageStats stats = tracker.getStats("p", Duration.ofHours(1));
        assertEquals(Duration.ofMillis(200), stats.getAvgLatency());
    }

    @Test
    void shouldReturnZeroLatencyWhenNoLatencyData() {
        Instant now = Instant.now();
        LlmUsage usage = LlmUsage.builder()
                .inputTokens(10).outputTokens(5).totalTokens(15)
                .timestamp(now)
                .build(); // no latency

        tracker.recordUsage("p", "m", usage);

        UsageStats stats = tracker.getStats("p", Duration.ofHours(1));
        assertEquals(Duration.ZERO, stats.getAvgLatency());
    }

    // ===== Null provider/model handling =====

    @Test
    void shouldHandleNullProviderInUsage() {
        LlmUsage usage = LlmUsage.builder()
                .inputTokens(10).outputTokens(5).totalTokens(15)
                .timestamp(Instant.now())
                .build();

        tracker.recordUsage(null, "m", usage);

        // Should be indexed under "unknown" provider
        UsageStats stats = tracker.getStats("unknown", Duration.ofHours(1));
        assertEquals(1, stats.getTotalRequests());
    }

    @Test
    void shouldHandleNullModelInUsage() {
        LlmUsage usage = LlmUsage.builder()
                .inputTokens(10).outputTokens(5).totalTokens(15)
                .timestamp(Instant.now())
                .build();

        tracker.recordUsage("p", null, usage);

        UsageStats stats = tracker.getStats("p", Duration.ofHours(1));
        assertEquals(1, stats.getTotalRequests());
    }

    // ===== Model breakdown in stats =====

    @Test
    void shouldTrackRequestsByModel() {
        Instant now = Instant.now();
        tracker.recordUsage("p", MODEL_GPT_51, usage(100, 50, now));
        tracker.recordUsage("p", MODEL_GPT_51, usage(100, 50, now));
        tracker.recordUsage("p", MODEL_GPT_52, usage(200, 100, now));

        UsageStats stats = tracker.getStats("p", Duration.ofHours(1));
        assertNotNull(stats.getRequestsByModel());
        assertEquals(2, stats.getRequestsByModel().get(MODEL_GPT_51));
        assertEquals(1, stats.getRequestsByModel().get(MODEL_GPT_52));
    }

    @Test
    void shouldTrackTokensByModel() {
        Instant now = Instant.now();
        tracker.recordUsage("p", MODEL_GPT_51, usage(100, 50, now));
        tracker.recordUsage("p", MODEL_GPT_52, usage(200, 100, now));

        UsageStats stats = tracker.getStats("p", Duration.ofHours(1));
        assertNotNull(stats.getTokensByModel());
        assertEquals(150, stats.getTokensByModel().get(MODEL_GPT_51));
        assertEquals(300, stats.getTokensByModel().get(MODEL_GPT_52));
    }

    // ===== Destroy =====

    @Test
    void shouldHandleDestroy() {
        assertDoesNotThrow(() -> tracker.destroy());
    }

    // ===== File read failure on load =====

    @Test
    void shouldHandleFileReadFailureOnLoad() {
        when(storagePort.listObjects(USAGE_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("bad.jsonl")));
        when(storagePort.getText(USAGE_DIR, "bad.jsonl"))
                .thenThrow(new RuntimeException("Read failed"));

        LlmUsageTrackerImpl freshTracker = new LlmUsageTrackerImpl(storagePort, properties, objectMapper);
        assertDoesNotThrow(() -> freshTracker.init());
    }

    // ===== Export metrics per model =====

    @Test
    void shouldExportPerModelMetrics() {
        Instant now = Instant.now();
        tracker.recordUsage("p1", "model-a", usage(100, 50, now));
        tracker.recordUsage("p1", "model-b", usage(200, 100, now));

        List<UsageMetric> metrics = tracker.exportMetrics();

        // Should have provider-level and model-level metrics
        assertTrue(metrics.stream().anyMatch(m -> "llm.tokens.total".equals(m.getName())
                && m.getTags().containsValue("model-a")));
        assertTrue(metrics.stream().anyMatch(m -> "llm.requests.total".equals(m.getName())
                && m.getTags().containsValue("model-b")));
    }

    // ===== Null list from storage =====

    @Test
    void shouldHandleNullListFromStorage() {
        when(storagePort.listObjects(USAGE_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(null));

        LlmUsageTrackerImpl freshTracker = new LlmUsageTrackerImpl(storagePort, properties, objectMapper);
        assertDoesNotThrow(() -> freshTracker.init());
    }

    // ===== getStatsByModel with null provider/model =====

    @Test
    void shouldHandleNullProviderAndModelInStatsByModel() {
        Instant now = Instant.now();
        LlmUsage usage = LlmUsage.builder()
                .inputTokens(100).outputTokens(50).totalTokens(150)
                .timestamp(now)
                .latency(Duration.ofMillis(100))
                .build();

        tracker.recordUsage(null, null, usage);

        Map<String, UsageStats> byModel = tracker.getStatsByModel(Duration.ofHours(1));
        assertFalse(byModel.isEmpty());
        assertTrue(byModel.containsKey("unknown/unknown"));
    }

    // ===== getAllStats empty =====

    @Test
    void shouldReturnEmptyMapWhenNoUsageRecorded() {
        Map<String, UsageStats> allStats = tracker.getAllStats(Duration.ofHours(1));
        assertTrue(allStats.isEmpty());
    }

    // ===== Export metrics empty =====

    @Test
    void shouldReturnEmptyMetricsWhenNoUsage() {
        List<UsageMetric> metrics = tracker.exportMetrics();
        assertTrue(metrics.isEmpty());
    }

    // ===== Load with blank lines =====

    @Test
    void shouldSkipBlankLinesOnLoad() {
        String validLine = "{\"inputTokens\":100,\"outputTokens\":50,\"totalTokens\":150,\"providerId\":\"p\",\"model\":\"m\",\"timestamp\":\""
                + Instant.now() + "\"}";
        String content = "\n\n" + validLine + "\n\n\n" + validLine + "\n";

        when(storagePort.listObjects(USAGE_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("blanks.jsonl")));
        when(storagePort.getText(USAGE_DIR, "blanks.jsonl"))
                .thenReturn(CompletableFuture.completedFuture(content));

        LlmUsageTrackerImpl freshTracker = new LlmUsageTrackerImpl(storagePort, properties, objectMapper);
        freshTracker.init();

        UsageStats stats = freshTracker.getStats("p", Duration.ofHours(1));
        assertEquals(2, stats.getTotalRequests());
    }

    // ===== Load record with null timestamp: loaded into memory but filtered out by
    // period query =====

    @Test
    void shouldLoadRecordWithNullTimestampButFilterByPeriod() {
        String line = "{\"inputTokens\":100,\"outputTokens\":50,\"totalTokens\":150,\"providerId\":\"p\",\"model\":\"m\"}";

        when(storagePort.listObjects(USAGE_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("null-ts.jsonl")));
        when(storagePort.getText(USAGE_DIR, "null-ts.jsonl"))
                .thenReturn(CompletableFuture.completedFuture(line));

        LlmUsageTrackerImpl freshTracker = new LlmUsageTrackerImpl(storagePort, properties, objectMapper);
        freshTracker.init();

        // Null timestamp records are loaded but filtered out by filterByPeriod
        // (timestamp != null check)
        UsageStats stats = freshTracker.getStats("p", Duration.ofHours(1));
        assertEquals(0, stats.getTotalRequests());
    }

    // ===== recordUsage auto-sets timestamp when null =====

    @Test
    void shouldAutoSetTimestampWhenNull() {
        LlmUsage usage = LlmUsage.builder()
                .inputTokens(100).outputTokens(50).totalTokens(150)
                .timestamp(null)
                .build();

        tracker.recordUsage("p", "m", usage);

        // recordUsage sets timestamp to Instant.now() if null
        assertNotNull(usage.getTimestamp());
        UsageStats stats = tracker.getStats("p", Duration.ofHours(1));
        assertEquals(1, stats.getTotalRequests());
    }

    // ===== aggregateUsages with all null models =====

    @Test
    void shouldReturnNullPrimaryModelWhenAllModelsNull() {
        LlmUsage usage = LlmUsage.builder()
                .inputTokens(10).outputTokens(5).totalTokens(15)
                .timestamp(Instant.now())
                .build();
        usage.setModel(null);

        // Record directly to bypass the model setter in recordUsage
        tracker.recordUsage(null, null, usage);

        UsageStats stats = tracker.getStats("unknown", Duration.ofHours(1));
        assertNull(stats.getModel());
    }

    private LlmUsage usage(int input, int output, Instant ts) {
        return LlmUsage.builder()
                .inputTokens(input).outputTokens(output).totalTokens(input + output)
                .timestamp(ts)
                .latency(Duration.ofMillis(100))
                .build();
    }
}
