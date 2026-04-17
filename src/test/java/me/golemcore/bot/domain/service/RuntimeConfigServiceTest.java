package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.adapter.outbound.config.StorageRuntimeConfigPersistenceAdapter;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import me.golemcore.bot.domain.selfevolving.SelfEvolvingBootstrapOverrideService;

@SuppressWarnings("PMD.TooManyMethods") // Comprehensive test coverage for critical config service
class RuntimeConfigServiceTest {

    private StoragePort storagePort;
    private RuntimeConfigService service;
    private ObjectMapper objectMapper;
    /** Per-file storage for modular config sections */
    private Map<String, String> persistedSections;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        persistedSections = new ConcurrentHashMap<>();

        // Mock putTextAtomic to capture written content per section file
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    String fileName = invocation.getArgument(1);
                    String content = invocation.getArgument(2);
                    persistedSections.put(fileName, content);
                    return CompletableFuture.completedFuture(null);
                });

        // Mock getText to return persisted content per section file
        when(storagePort.getText(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String fileName = invocation.getArgument(1);
                    return CompletableFuture.completedFuture(persistedSections.get(fileName));
                });

        service = new RuntimeConfigService(
                new StorageRuntimeConfigPersistenceAdapter(storagePort),
                new SelfEvolvingBootstrapOverrideService(
                        me.golemcore.bot.support.TestPorts.settings(new BotProperties())));
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    // ==================== Lazy Loading ====================

    @Test
    void shouldCreateDefaultConfigWhenStorageEmpty() {
        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config);
        assertNotNull(config.getModelRegistry());
        assertNotNull(config.getTelegram());
        assertNotNull(config.getModelRouter());
        assertNotNull(config.getLlm());
        assertNotNull(config.getTools());
        assertNotNull(config.getTools().getShellEnvironmentVariables());
        assertTrue(config.getTools().getShellEnvironmentVariables().isEmpty());
        assertNotNull(config.getTracing());
        assertTrue(config.getTracing().getEnabled());
        assertFalse(config.getTracing().getPayloadSnapshotsEnabled());
        assertEquals(128, config.getTracing().getSessionTraceBudgetMb());
    }

    @Test
    void shouldDefaultTelemetryToTrueForFreshInstall() {
        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config.getTelemetry());
        assertTrue(Boolean.TRUE.equals(config.getTelemetry().getEnabled()));
    }

    @Test
    void shouldDefaultTelemetryToFalseForUpgradeWithoutTelemetrySection() throws Exception {
        RuntimeConfig.UsageConfig usage = RuntimeConfig.UsageConfig.builder()
                .enabled(true)
                .build();
        persistedSections.put("usage.json", objectMapper.writeValueAsString(usage));

        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config.getTelemetry());
        assertFalse(Boolean.TRUE.equals(config.getTelemetry().getEnabled()));
    }

    @Test
    void shouldPreserveIndependentTacticsToggleWithinSelfEvolvingConfig() {
        RuntimeConfig config = service.getRuntimeConfig();
        config.getSelfEvolving().setEnabled(true);
        config.getSelfEvolving().getTactics().setEnabled(false);

        service.updateRuntimeConfig(config);

        assertTrue(config.getSelfEvolving().getEnabled());
        assertFalse(config.getSelfEvolving().getTactics().getEnabled());
    }

    @Test
    void shouldLoadModelRegistryConfigFromStorage() throws Exception {
        RuntimeConfig.ModelRegistryConfig modelRegistry = RuntimeConfig.ModelRegistryConfig.builder()
                .repositoryUrl("https://github.com/alexk-dev/golemcore-models")
                .branch("develop")
                .build();
        persistedSections.put("model-registry.json", objectMapper.writeValueAsString(modelRegistry));

        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config.getModelRegistry());
        assertEquals("https://github.com/alexk-dev/golemcore-models", config.getModelRegistry().getRepositoryUrl());
        assertEquals("develop", config.getModelRegistry().getBranch());
    }

    @Test
    void shouldPersistModelRegistrySection() throws Exception {
        RuntimeConfig config = service.getRuntimeConfig();
        config.setModelRegistry(RuntimeConfig.ModelRegistryConfig.builder()
                .repositoryUrl("https://github.com/alexk-dev/golemcore-models")
                .branch("main")
                .build());

        service.updateRuntimeConfig(config);

        assertTrue(persistedSections.containsKey("model-registry.json"));
        Map<?, ?> persistedModelRegistry = readPersistedJsonMap("model-registry.json");
        assertEquals("https://github.com/alexk-dev/golemcore-models", persistedModelRegistry.get("repositoryUrl"));
        assertEquals("main", persistedModelRegistry.get("branch"));
    }

    @Test
    void shouldNormalizeBlankModelRegistryBranchToMain() {
        RuntimeConfig config = service.getRuntimeConfig();
        config.setModelRegistry(RuntimeConfig.ModelRegistryConfig.builder()
                .repositoryUrl("https://github.com/alexk-dev/golemcore-models")
                .branch("  ")
                .build());

        service.updateRuntimeConfig(config);

        assertEquals("main", config.getModelRegistry().getBranch());
    }

    @Test
    void shouldLoadConfigFromStorage() throws Exception {
        RuntimeConfig.ModelRouterConfig modelRouter = RuntimeConfig.ModelRouterConfig.builder()
                .balancedModel("custom/model")
                .build();
        String json = objectMapper.writeValueAsString(modelRouter);
        persistedSections.put("model-router.json", json);

        RuntimeConfig config = service.getRuntimeConfig();

        assertEquals("custom/model", config.getModelRouter().getBalancedModel());
    }

    @Test
    void shouldNormalizeModelRouterToCanonicalTierBindingsInFixedOrder() throws Exception {
        RuntimeConfig config = service.getRuntimeConfig();

        service.updateRuntimeConfig(config);

        Map<?, ?> persistedModelRouter = readPersistedJsonMap("model-router.json");
        assertTrue(persistedModelRouter.containsKey("routing"));
        assertTrue(persistedModelRouter.containsKey("tiers"));

        Map<?, ?> tiers = castMap(persistedModelRouter.get("tiers"));
        assertEquals(List.of(
                "balanced",
                "smart",
                "deep",
                "coding",
                "special1",
                "special2",
                "special3",
                "special4",
                "special5"), List.copyOf(tiers.keySet()));

        Map<?, ?> routing = castMap(persistedModelRouter.get("routing"));
        assertPersistedModelMissing(routing);
        assertEquals("none", routing.get("reasoning"));
        assertPersistedModelMissing(castMap(tiers.get("balanced")));
        assertPersistedModelMissing(castMap(tiers.get("smart")));
        assertPersistedModelMissing(castMap(tiers.get("deep")));
        assertPersistedModelMissing(castMap(tiers.get("coding")));
    }

    @Test
    void shouldMigrateLegacyFlatModelRouterSectionToCanonicalTierBindings() throws Exception {
        Map<String, Object> legacyModelRouter = new LinkedHashMap<>();
        legacyModelRouter.put("routingModel", "openai/gpt-5.2-codex");
        legacyModelRouter.put("routingModelReasoning", "none");
        legacyModelRouter.put("balancedModel", "legacy/balanced");
        legacyModelRouter.put("balancedModelReasoning", "low");
        legacyModelRouter.put("smartModel", "legacy/smart");
        legacyModelRouter.put("smartModelReasoning", "medium");
        legacyModelRouter.put("deepModel", "legacy/deep");
        legacyModelRouter.put("deepModelReasoning", "high");
        legacyModelRouter.put("codingModel", "legacy/coding");
        legacyModelRouter.put("codingModelReasoning", "xhigh");
        legacyModelRouter.put("dynamicTierEnabled", true);
        persistedSections.put("model-router.json", objectMapper.writeValueAsString(legacyModelRouter));

        RuntimeConfig config = service.getRuntimeConfig();
        service.updateRuntimeConfig(config);

        Map<?, ?> persistedModelRouter = readPersistedJsonMap("model-router.json");
        Map<?, ?> routing = castMap(persistedModelRouter.get("routing"));
        Map<?, ?> tiers = castMap(persistedModelRouter.get("tiers"));

        assertPersistedModelId(routing, "openai/gpt-5.2-codex");
        assertPersistedModelId(castMap(tiers.get("balanced")), "legacy/balanced");
        assertEquals("low", castMap(tiers.get("balanced")).get("reasoning"));
        assertPersistedModelId(castMap(tiers.get("smart")), "legacy/smart");
        assertEquals("medium", castMap(tiers.get("smart")).get("reasoning"));
        assertPersistedModelId(castMap(tiers.get("deep")), "legacy/deep");
        assertEquals("high", castMap(tiers.get("deep")).get("reasoning"));
        assertPersistedModelId(castMap(tiers.get("coding")), "legacy/coding");
        assertEquals("xhigh", castMap(tiers.get("coding")).get("reasoning"));
    }

    @Test
    void shouldPreserveCanonicalModelRouterBindingsWhenPersisting() throws Exception {
        Map<String, Object> canonicalRouter = new LinkedHashMap<>();
        canonicalRouter.put("dynamicTierEnabled", false);
        canonicalRouter.put("routing", Map.of("model", "router/model", "reasoning", "none"));
        Map<String, Object> tiers = new LinkedHashMap<>();
        tiers.put("balanced", Map.of("model", "model/balanced", "reasoning", "low"));
        tiers.put("smart", Map.of("model", "model/smart", "reasoning", "medium"));
        tiers.put("deep", Map.of("model", "model/deep", "reasoning", "high"));
        tiers.put("coding", Map.of("model", "model/coding", "reasoning", "xhigh"));
        tiers.put("special1", Map.of("model", "model/special1", "reasoning", "none"));
        tiers.put("special2", Map.of("model", "model/special2", "reasoning", "none"));
        tiers.put("special3", Map.of("model", "model/special3", "reasoning", "none"));
        tiers.put("special4", Map.of("model", "model/special4", "reasoning", "none"));
        tiers.put("special5", Map.of("model", "model/special5", "reasoning", "none"));
        canonicalRouter.put("tiers", tiers);
        persistedSections.put("model-router.json", objectMapper.writeValueAsString(canonicalRouter));

        RuntimeConfig config = service.getRuntimeConfig();
        service.updateRuntimeConfig(config);

        Map<?, ?> persistedModelRouter = readPersistedJsonMap("model-router.json");
        Map<?, ?> persistedTiers = castMap(persistedModelRouter.get("tiers"));
        assertEquals(List.copyOf(tiers.keySet()), List.copyOf(persistedTiers.keySet()));
        assertPersistedModelId(castMap(persistedModelRouter.get("routing")), "router/model");
        assertPersistedModelId(castMap(persistedTiers.get("special5")), "model/special5");
        assertEquals("none", castMap(persistedTiers.get("special5")).get("reasoning"));
    }

    @Test
    void shouldLoadStructuredModelRouterReferenceAndExposeCanonicalModelSpec() throws Exception {
        Map<String, Object> structuredRouter = new LinkedHashMap<>();
        structuredRouter.put("routing", Map.of(
                "model", Map.of(
                        "provider", "openrouter",
                        "id", "qwen/model-name:version"),
                "reasoning", "none"));
        persistedSections.put("model-router.json", objectMapper.writeValueAsString(structuredRouter));

        RuntimeConfig config = service.getRuntimeConfig();

        assertEquals("openrouter/qwen/model-name:version", config.getModelRouter().getRouting().getModel());

        service.updateRuntimeConfig(config);

        Map<?, ?> persistedModelRouter = readPersistedJsonMap("model-router.json");
        Map<?, ?> routing = castMap(persistedModelRouter.get("routing"));
        Map<?, ?> model = castMap(routing.get("model"));
        assertEquals("openrouter", model.get("provider"));
        assertEquals("qwen/model-name:version", model.get("id"));
    }

    @Test
    void shouldMigrateLegacyStringModelRouterReferenceToStructuredStorage() throws Exception {
        Map<String, Object> legacyRouter = new LinkedHashMap<>();
        legacyRouter.put("routing", Map.of(
                "model", "openrouter/qwen/model-name:version",
                "reasoning", "none"));
        persistedSections.put("model-router.json", objectMapper.writeValueAsString(legacyRouter));

        RuntimeConfig config = service.getRuntimeConfig();

        assertEquals("openrouter/qwen/model-name:version", config.getModelRouter().getRouting().getModel());

        service.updateRuntimeConfig(config);

        Map<?, ?> persistedModelRouter = readPersistedJsonMap("model-router.json");
        Map<?, ?> routing = castMap(persistedModelRouter.get("routing"));
        Map<?, ?> model = castMap(routing.get("model"));
        assertEquals("openrouter/qwen/model-name:version", model.get("id"));
        assertFalse(model.containsKey("provider"));
    }

    @Test
    void shouldCacheConfigAfterFirstLoad() {
        RuntimeConfig first = service.getRuntimeConfig();
        RuntimeConfig second = service.getRuntimeConfig();

        assertEquals(first, second);
        verify(storagePort, atLeast(RuntimeConfig.ConfigSection.values().length)).getText(anyString(), anyString());
    }

    @Test
    void shouldFallbackToDefaultOnCorruptedJson() {
        // Put corrupted JSON for a section file
        persistedSections.put("telegram.json", "{invalid json!!!}");

        RuntimeConfig config = service.getRuntimeConfig();

        // Should still load with defaults for corrupted section
        assertNotNull(config);
        assertNotNull(config.getTelegram());
    }

    @Test
    void shouldFallbackToDefaultOnStorageException() {
        // Make getText fail for one section
        when(storagePort.getText("preferences", "telegram.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk error")));

        RuntimeConfig config = service.getRuntimeConfig();

        // Should still load with defaults for failed section
        assertNotNull(config);
        assertNotNull(config.getTelegram());
    }

    // ==================== Default Values ====================

    @Test
    void shouldReturnEmptyModelDefaultsWhenNotConfigured() {
        assertNull(service.getBalancedModel());
        assertNull(service.getSmartModel());
        assertNull(service.getCodingModel());
        assertNull(service.getDeepModel());
        assertNull(service.getRoutingModel());
        assertTrue(service.getConfiguredLlmProviders().isEmpty());
    }

    @Test
    void shouldReturnDefaultReasoningWhenNotConfigured() {
        assertEquals("none", service.getBalancedModelReasoning());
        assertEquals("none", service.getSmartModelReasoning());
        assertEquals("none", service.getCodingModelReasoning());
        assertEquals("none", service.getDeepModelReasoning());
        assertEquals("none", service.getRoutingModelReasoning());
    }

    @Test
    void shouldReturnDefaultTemperature() {
        assertEquals(0.7, service.getTemperatureForModel("balanced", service.getBalancedModel()), 0.001);
    }

    @Test
    void shouldReturnPrimaryTemperatureWhenModelMatchesTierBinding() throws Exception {
        RuntimeConfig config = service.getRuntimeConfig();
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .temperature(0.3)
                .build();
        config.getModelRouter().setTierBinding("balanced", binding);
        setCachedConfig(config);

        assertEquals(0.3, service.getTemperatureForModel("balanced", "openai/gpt-5"), 0.001);
    }

    @Test
    void shouldReturnFallbackTemperatureWhenModelMatchesFallbackEntry() throws Exception {
        RuntimeConfig config = service.getRuntimeConfig();
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .temperature(0.3)
                .fallbacks(new java.util.ArrayList<>(List.of(
                        RuntimeConfig.TierFallback.builder()
                                .model("openai/gpt-5-mini")
                                .temperature(1.2)
                                .build())))
                .build();
        config.getModelRouter().setTierBinding("balanced", binding);
        setCachedConfig(config);

        assertEquals(1.2, service.getTemperatureForModel("balanced", "openai/gpt-5-mini"), 0.001);
    }

    @Test
    void shouldReturnDefaultTemperatureForUnknownModel() throws Exception {
        RuntimeConfig config = service.getRuntimeConfig();
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .temperature(0.3)
                .build();
        config.getModelRouter().setTierBinding("balanced", binding);
        setCachedConfig(config);

        assertEquals(0.7, service.getTemperatureForModel("balanced", "openai/other"), 0.001);
    }

    @Test
    void shouldReturnDefaultTemperatureWhenTierIsNull() {
        assertEquals(0.7, service.getTemperatureForModel(null, "openai/gpt-5"), 0.001);
    }

    @Test
    void shouldReturnBindingTemperatureWhenModelArgumentIsBlank() throws Exception {
        RuntimeConfig config = service.getRuntimeConfig();
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .temperature(0.5)
                .build();
        config.getModelRouter().setTierBinding("balanced", binding);
        setCachedConfig(config);

        assertEquals(0.5, service.getTemperatureForModel("balanced", null), 0.001);
        assertEquals(0.5, service.getTemperatureForModel("balanced", "   "), 0.001);
    }

    @Test
    void shouldNormalizeTierBindingTruncatingFallbacksAboveFive() throws Exception {
        List<RuntimeConfig.TierFallback> fallbacks = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            fallbacks.add(RuntimeConfig.TierFallback.builder().model("openai/fallback-" + i).build());
        }
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbacks(fallbacks)
                .build();
        RuntimeConfig config = service.getRuntimeConfig();
        config.getModelRouter().setTierBinding("balanced", binding);
        setCachedConfig(config);

        service.updateRuntimeConfig(config);

        RuntimeConfig.TierBinding stored = service.getRuntimeConfig().getModelRouter().getTierBinding("balanced");
        assertEquals(5, stored.getFallbacks().size());
        assertEquals("openai/fallback-0", stored.getFallbacks().get(0).getModel());
        assertEquals("openai/fallback-4", stored.getFallbacks().get(4).getModel());
    }

    @Test
    void shouldDropFallbacksWithoutModelReferenceDuringNormalization() throws Exception {
        List<RuntimeConfig.TierFallback> fallbacks = new java.util.ArrayList<>();
        fallbacks.add(RuntimeConfig.TierFallback.builder().model("openai/keep").build());
        fallbacks.add(RuntimeConfig.TierFallback.builder().build());
        fallbacks.add(null);
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbacks(fallbacks)
                .build();
        RuntimeConfig config = service.getRuntimeConfig();
        config.getModelRouter().setTierBinding("balanced", binding);
        setCachedConfig(config);

        service.updateRuntimeConfig(config);

        RuntimeConfig.TierBinding stored = service.getRuntimeConfig().getModelRouter().getTierBinding("balanced");
        assertEquals(1, stored.getFallbacks().size());
        assertEquals("openai/keep", stored.getFallbacks().get(0).getModel());
    }

    @Test
    void shouldNormalizeFallbackModeDefaultingToSequential() throws Exception {
        RuntimeConfig config = service.getRuntimeConfig();
        config.getModelRouter().setTierBinding("balanced", RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("garbage")
                .build());
        config.getModelRouter().setTierBinding("smart", RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode("WEIGHTED")
                .build());
        config.getModelRouter().setTierBinding("deep", RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .fallbackMode(null)
                .build());
        setCachedConfig(config);

        service.updateRuntimeConfig(config);

        RuntimeConfig.ModelRouterConfig router = service.getRuntimeConfig().getModelRouter();
        assertEquals("sequential", router.getTierBinding("balanced").getFallbackMode());
        assertEquals("weighted", router.getTierBinding("smart").getFallbackMode());
        assertEquals("sequential", router.getTierBinding("deep").getFallbackMode());
    }

    @Test
    void shouldClampOutOfRangeTemperatureDuringNormalization() throws Exception {
        RuntimeConfig config = service.getRuntimeConfig();
        config.getModelRouter().setTierBinding("balanced", RuntimeConfig.TierBinding.builder()
                .model("openai/gpt-5")
                .temperature(5.0)
                .fallbacks(new java.util.ArrayList<>(List.of(
                        RuntimeConfig.TierFallback.builder()
                                .model("openai/gpt-5-mini")
                                .temperature(-2.0)
                                .build())))
                .build());
        setCachedConfig(config);

        service.updateRuntimeConfig(config);

        RuntimeConfig.TierBinding stored = service.getRuntimeConfig().getModelRouter().getTierBinding("balanced");
        assertEquals(2.0, stored.getTemperature(), 0.001);
        assertEquals(0.0, stored.getFallbacks().get(0).getTemperature(), 0.001);
    }

    @Test
    void shouldReturnDefaultRateLimits() {
        assertEquals(20, service.getUserRequestsPerMinute());
        assertEquals(100, service.getUserRequestsPerHour());
        assertEquals(500, service.getUserRequestsPerDay());
        assertEquals(30, service.getChannelMessagesPerSecond());
        assertEquals(60, service.getLlmRequestsPerMinute());
    }

    @Test
    void shouldReturnDefaultTracingSettingsWhenTracingConfigMissing() throws Exception {
        RuntimeConfig config = service.getRuntimeConfig();
        config.setTracing(null);
        setCachedConfig(config);

        assertTrue(service.isTracingEnabled());
        assertFalse(service.isPayloadSnapshotsEnabled());
        assertEquals(128, service.getSessionTraceBudgetMb());
        assertEquals(256, service.getTraceMaxSnapshotSizeKb());
        assertEquals(10, service.getTraceMaxSnapshotsPerSpan());
        assertEquals(100, service.getTraceMaxTracesPerSession());
        assertFalse(service.isTraceInboundPayloadCaptureEnabled());
        assertFalse(service.isTraceOutboundPayloadCaptureEnabled());
        assertFalse(service.isTraceToolPayloadCaptureEnabled());
        assertFalse(service.isTraceLlmPayloadCaptureEnabled());
    }

    @Test
    void shouldReturnDefaultTracingSettingsWhenTracingFieldsAreNull() throws Exception {
        RuntimeConfig config = service.getRuntimeConfig();
        RuntimeConfig.TracingConfig tracing = RuntimeConfig.TracingConfig.builder().build();
        tracing.setEnabled(null);
        tracing.setPayloadSnapshotsEnabled(null);
        tracing.setSessionTraceBudgetMb(null);
        tracing.setMaxSnapshotSizeKb(null);
        tracing.setMaxSnapshotsPerSpan(null);
        tracing.setMaxTracesPerSession(null);
        tracing.setCaptureInboundPayloads(null);
        tracing.setCaptureOutboundPayloads(null);
        tracing.setCaptureToolPayloads(null);
        tracing.setCaptureLlmPayloads(null);
        config.setTracing(tracing);
        setCachedConfig(config);

        assertTrue(service.isTracingEnabled());
        assertFalse(service.isPayloadSnapshotsEnabled());
        assertEquals(128, service.getSessionTraceBudgetMb());
        assertEquals(256, service.getTraceMaxSnapshotSizeKb());
        assertEquals(10, service.getTraceMaxSnapshotsPerSpan());
        assertEquals(100, service.getTraceMaxTracesPerSession());
        assertFalse(service.isTraceInboundPayloadCaptureEnabled());
        assertFalse(service.isTraceOutboundPayloadCaptureEnabled());
        assertFalse(service.isTraceToolPayloadCaptureEnabled());
        assertFalse(service.isTraceLlmPayloadCaptureEnabled());
    }

    @Test
    void shouldReturnConfiguredTracingSettings() throws Exception {
        RuntimeConfig config = service.getRuntimeConfig();
        RuntimeConfig.TracingConfig tracing = RuntimeConfig.TracingConfig.builder().build();
        tracing.setEnabled(false);
        tracing.setPayloadSnapshotsEnabled(true);
        tracing.setSessionTraceBudgetMb(64);
        tracing.setMaxSnapshotSizeKb(512);
        tracing.setMaxSnapshotsPerSpan(4);
        tracing.setMaxTracesPerSession(3);
        tracing.setCaptureInboundPayloads(true);
        tracing.setCaptureOutboundPayloads(true);
        tracing.setCaptureToolPayloads(true);
        tracing.setCaptureLlmPayloads(true);
        config.setTracing(tracing);
        setCachedConfig(config);

        assertFalse(service.isTracingEnabled());
        assertTrue(service.isPayloadSnapshotsEnabled());
        assertEquals(64, service.getSessionTraceBudgetMb());
        assertEquals(512, service.getTraceMaxSnapshotSizeKb());
        assertEquals(4, service.getTraceMaxSnapshotsPerSpan());
        assertEquals(3, service.getTraceMaxTracesPerSession());
        assertTrue(service.isTraceInboundPayloadCaptureEnabled());
        assertTrue(service.isTraceOutboundPayloadCaptureEnabled());
        assertTrue(service.isTraceToolPayloadCaptureEnabled());
        assertTrue(service.isTraceLlmPayloadCaptureEnabled());
    }

    @Test
    void shouldReturnDefaultSecuritySettings() {
        assertTrue(service.isSanitizeInputEnabled());
        assertTrue(service.isPromptInjectionDetectionEnabled());
        assertTrue(service.isCommandInjectionDetectionEnabled());
        assertEquals(10000, service.getMaxInputLength());
        assertTrue(service.isAllowlistEnabled());
        assertFalse(service.isToolConfirmationEnabled());
        assertEquals(60, service.getToolConfirmationTimeoutSeconds());
    }

    @Test
    void shouldReturnDefaultAutoModeSettings() {
        assertTrue(service.isAutoModeEnabled());
        assertEquals(30, service.getAutoTickIntervalSeconds());
        assertEquals(10, service.getAutoTaskTimeLimitMinutes());
        assertEquals(3, service.getAutoMaxGoals());
        assertEquals("default", service.getAutoModelTier());
    }

    @Test
    void shouldReturnDefaultUpdateSettings() {
        assertTrue(service.isAutoUpdateEnabled());
        assertEquals(60, service.getUpdateCheckIntervalMinutes());
        assertFalse(service.isUpdateMaintenanceWindowEnabled());
        assertEquals("00:00", service.getUpdateMaintenanceWindowStartUtc());
        assertEquals("00:00", service.getUpdateMaintenanceWindowEndUtc());
    }

    @Test
    void shouldReturnConfiguredUpdateSettings() throws Exception {
        RuntimeConfig.UpdateConfig update = RuntimeConfig.UpdateConfig.builder()
                .autoEnabled(false)
                .checkIntervalMinutes(180)
                .maintenanceWindowEnabled(true)
                .maintenanceWindowStartUtc("01:15")
                .maintenanceWindowEndUtc("03:45")
                .build();
        persistedSections.put("update.json", objectMapper.writeValueAsString(update));

        assertFalse(service.isAutoUpdateEnabled());
        assertEquals(180, service.getUpdateCheckIntervalMinutes());
        assertTrue(service.isUpdateMaintenanceWindowEnabled());
        assertEquals("01:15", service.getUpdateMaintenanceWindowStartUtc());
        assertEquals("03:45", service.getUpdateMaintenanceWindowEndUtc());
    }

    @Test
    void shouldReturnDefaultUpdateSettingsWhenStoredSectionIsNull() {
        persistedSections.put("update.json", "null");

        assertTrue(service.isAutoUpdateEnabled());
        assertEquals(60, service.getUpdateCheckIntervalMinutes());
        assertFalse(service.isUpdateMaintenanceWindowEnabled());
        assertEquals("00:00", service.getUpdateMaintenanceWindowStartUtc());
        assertEquals("00:00", service.getUpdateMaintenanceWindowEndUtc());
    }

    @Test
    void shouldNormalizeInvalidUpdateSettings() throws Exception {
        RuntimeConfig.UpdateConfig update = RuntimeConfig.UpdateConfig.builder()
                .autoEnabled(null)
                .checkIntervalMinutes(0)
                .maintenanceWindowEnabled(null)
                .maintenanceWindowStartUtc("25:99")
                .maintenanceWindowEndUtc("")
                .build();
        persistedSections.put("update.json", objectMapper.writeValueAsString(update));

        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config.getUpdate());
        assertTrue(config.getUpdate().getAutoEnabled());
        assertEquals(60, config.getUpdate().getCheckIntervalMinutes());
        assertFalse(config.getUpdate().getMaintenanceWindowEnabled());
        assertEquals("00:00", config.getUpdate().getMaintenanceWindowStartUtc());
        assertEquals("00:00", config.getUpdate().getMaintenanceWindowEndUtc());
    }

    @Test
    void shouldExposeUpdateConfigSectionMetadata() {
        assertTrue(RuntimeConfig.ConfigSection.isValidSection("update"));
        assertEquals(RuntimeConfig.ConfigSection.UPDATE,
                RuntimeConfig.ConfigSection.fromFileId("update").orElseThrow());
        assertEquals("update.json", RuntimeConfig.ConfigSection.UPDATE.getFileName());
    }

    @Test
    void shouldInitializeUpdateDefaultsWhenNullDuringRuntimeConfigUpdate() {
        RuntimeConfig newConfig = RuntimeConfig.builder().build();
        newConfig.setUpdate(null);

        service.updateRuntimeConfig(newConfig);

        RuntimeConfig updated = service.getRuntimeConfig();
        assertNotNull(updated.getUpdate());
        assertTrue(updated.getUpdate().getAutoEnabled());
        assertEquals(60, updated.getUpdate().getCheckIntervalMinutes());
        assertFalse(updated.getUpdate().getMaintenanceWindowEnabled());
        assertEquals("00:00", updated.getUpdate().getMaintenanceWindowStartUtc());
        assertEquals("00:00", updated.getUpdate().getMaintenanceWindowEndUtc());
    }

    @Test
    void shouldDisableRateLimitByDefault() {
        assertFalse(service.isRateLimitEnabled());
    }

    @Test
    void shouldReturnDefaultCompactionSettings() {
        assertTrue(service.isCompactionEnabled());
        assertEquals(50000, service.getCompactionMaxContextTokens());
        assertEquals(20, service.getCompactionKeepLastMessages());
        assertEquals("model_ratio", service.getCompactionTriggerMode());
        assertEquals(0.95d, service.getCompactionModelThresholdRatio(), 0.0001d);
        assertTrue(service.isCompactionPreserveTurnBoundariesEnabled());
        assertTrue(service.isCompactionDetailsEnabled());
        assertEquals(50, service.getCompactionDetailsMaxItemsPerCategory());
        assertEquals(15000, service.getCompactionSummaryTimeoutMs());
    }

    @Test
    void shouldReturnConfiguredAdvancedCompactionSettings() throws Exception {
        RuntimeConfig.CompactionConfig compaction = RuntimeConfig.CompactionConfig.builder()
                .enabled(true)
                .triggerMode("token_threshold")
                .modelThresholdRatio(0.9d)
                .maxContextTokens(12345)
                .keepLastMessages(7)
                .preserveTurnBoundaries(false)
                .detailsEnabled(false)
                .detailsMaxItemsPerCategory(12)
                .summaryTimeoutMs(3000)
                .build();
        persistedSections.put("compaction.json", objectMapper.writeValueAsString(compaction));

        assertEquals("token_threshold", service.getCompactionTriggerMode());
        assertEquals(0.9d, service.getCompactionModelThresholdRatio(), 0.0001d);
        assertFalse(service.isCompactionPreserveTurnBoundariesEnabled());
        assertFalse(service.isCompactionDetailsEnabled());
        assertEquals(12, service.getCompactionDetailsMaxItemsPerCategory());
        assertEquals(3000, service.getCompactionSummaryTimeoutMs());
    }

    @Test
    void shouldNormalizeAdvancedCompactionDefaultsWhenStoredSectionMissingFields() {
        persistedSections.put("compaction.json", "{}");

        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config.getCompaction());
        assertEquals("model_ratio", config.getCompaction().getTriggerMode());
        assertEquals(0.95d, config.getCompaction().getModelThresholdRatio(), 0.0001d);
        assertTrue(config.getCompaction().getPreserveTurnBoundaries());
        assertTrue(config.getCompaction().getDetailsEnabled());
        assertEquals(50, config.getCompaction().getDetailsMaxItemsPerCategory());
        assertEquals(15000, config.getCompaction().getSummaryTimeoutMs());
    }

    @Test
    void shouldInitializeCompactionAdvancedDefaultsWhenNullDuringRuntimeConfigUpdate() {
        RuntimeConfig newConfig = RuntimeConfig.builder().build();
        newConfig.setCompaction(new RuntimeConfig.CompactionConfig());
        newConfig.getCompaction().setTriggerMode(null);
        newConfig.getCompaction().setModelThresholdRatio(null);
        newConfig.getCompaction().setPreserveTurnBoundaries(null);
        newConfig.getCompaction().setDetailsEnabled(null);
        newConfig.getCompaction().setDetailsMaxItemsPerCategory(null);
        newConfig.getCompaction().setSummaryTimeoutMs(null);

        service.updateRuntimeConfig(newConfig);

        RuntimeConfig updated = service.getRuntimeConfig();
        assertEquals("model_ratio", updated.getCompaction().getTriggerMode());
        assertEquals(0.95d, updated.getCompaction().getModelThresholdRatio(), 0.0001d);
        assertTrue(updated.getCompaction().getPreserveTurnBoundaries());
        assertTrue(updated.getCompaction().getDetailsEnabled());
        assertEquals(50, updated.getCompaction().getDetailsMaxItemsPerCategory());
        assertEquals(15000, updated.getCompaction().getSummaryTimeoutMs());
    }

    @Test
    void shouldNormalizeInvalidStoredCompactionTriggerSettings() throws Exception {
        RuntimeConfig.CompactionConfig compaction = RuntimeConfig.CompactionConfig.builder()
                .triggerMode("not-a-real-mode")
                .modelThresholdRatio(0.0d)
                .build();
        persistedSections.put("compaction.json", objectMapper.writeValueAsString(compaction));

        RuntimeConfig config = service.getRuntimeConfig();

        assertEquals("model_ratio", config.getCompaction().getTriggerMode());
        assertEquals(0.95d, config.getCompaction().getModelThresholdRatio(), 0.0001d);
    }

    @Test
    void shouldNormalizeMixedCaseCompactionTriggerModeDuringUpdate() {
        RuntimeConfig newConfig = RuntimeConfig.builder().build();
        newConfig.setCompaction(RuntimeConfig.CompactionConfig.builder()
                .triggerMode(" Token_Threshold ")
                .modelThresholdRatio(0.8d)
                .build());

        service.updateRuntimeConfig(newConfig);

        RuntimeConfig updated = service.getRuntimeConfig();
        assertEquals("token_threshold", updated.getCompaction().getTriggerMode());
        assertEquals(0.8d, updated.getCompaction().getModelThresholdRatio(), 0.0001d);
    }

    @Test
    void shouldReturnDefaultVoiceSettings() {
        assertFalse(service.isVoiceEnabled());
        assertEquals("21m00Tcm4TlvDq8ikWAM", service.getVoiceId());
        assertEquals("eleven_multilingual_v2", service.getTtsModelId());
        assertEquals("scribe_v1", service.getSttModelId());
        assertEquals(1.0f, service.getVoiceSpeed(), 0.01);
    }

    @Test
    void shouldReturnUnsetVoiceProvidersWhenNotConfigured() {
        assertNull(service.getSttProvider());
        assertNull(service.getTtsProvider());
        assertEquals("", service.getWhisperSttUrl());
        assertEquals("", service.getWhisperSttApiKey());
        assertFalse(service.isWhisperSttConfigured());
    }

    @Test
    void shouldReturnDefaultMcpSettings() {
        assertTrue(service.isMcpEnabled());
        assertEquals(30, service.getMcpDefaultStartupTimeout());
        assertEquals(5, service.getMcpDefaultIdleTimeout());
    }

    @Test
    void shouldReturnDefaultTurnBudget() {
        assertEquals(200, service.getTurnMaxLlmCalls());
        assertEquals(500, service.getTurnMaxToolExecutions());
        assertEquals(java.time.Duration.ofHours(1), service.getTurnDeadline());
    }

    @Test
    void shouldReturnDefaultTurnAutoRetrySettings() {
        assertTrue(service.isTurnAutoRetryEnabled());
        assertEquals(2, service.getTurnAutoRetryMaxAttempts());
        assertEquals(600L, service.getTurnAutoRetryBaseDelayMs());
        assertTrue(service.isTurnQueueSteeringEnabled());
        assertEquals("one-at-a-time", service.getTurnQueueSteeringMode());
        assertEquals("one-at-a-time", service.getTurnQueueFollowUpMode());
        assertTrue(service.isTurnProgressUpdatesEnabled());
        assertTrue(service.isTurnProgressIntentEnabled());
        assertEquals(8, service.getTurnProgressBatchSize());
        assertEquals(java.time.Duration.ofSeconds(10), service.getTurnProgressMaxSilence());
        assertEquals(8000, service.getTurnProgressSummaryTimeoutMs());
    }

    @Test
    void shouldReturnDefaultDelayedActionSettingsWhenCachedSectionMissing() throws Exception {
        RuntimeConfig config = RuntimeConfig.builder().build();
        config.setDelayedActions(null);
        setCachedConfig(config);

        assertTrue(service.isDelayedActionsEnabled());
        assertEquals(1, service.getDelayedActionsTickSeconds());
        assertEquals(3, service.getDelayedActionsMaxPendingPerSession());
        assertEquals(java.time.Duration.ofDays(30), service.getDelayedActionsMaxDelay());
        assertEquals(4, service.getDelayedActionsDefaultMaxAttempts());
        assertEquals(java.time.Duration.ofMinutes(2), service.getDelayedActionsLeaseDuration());
        assertEquals(java.time.Duration.ofDays(7), service.getDelayedActionsRetentionAfterCompletion());
        assertTrue(service.isDelayedActionsRunLaterEnabled());
    }

    @Test
    void shouldReturnConfiguredDelayedActionSettings() throws Exception {
        RuntimeConfig.DelayedActionsConfig delayedActions = RuntimeConfig.DelayedActionsConfig.builder()
                .enabled(false)
                .tickSeconds(9)
                .maxPendingPerSession(12)
                .maxDelay("PT6H")
                .defaultMaxAttempts(7)
                .leaseDuration("PT5M")
                .retentionAfterCompletion("P10D")
                .allowRunLater(false)
                .build();
        persistedSections.put("delayed-actions.json", objectMapper.writeValueAsString(delayedActions));

        assertFalse(service.isDelayedActionsEnabled());
        assertEquals(9, service.getDelayedActionsTickSeconds());
        assertEquals(3, service.getDelayedActionsMaxPendingPerSession());
        assertEquals(java.time.Duration.ofHours(6), service.getDelayedActionsMaxDelay());
        assertEquals(7, service.getDelayedActionsDefaultMaxAttempts());
        assertEquals(java.time.Duration.ofMinutes(5), service.getDelayedActionsLeaseDuration());
        assertEquals(java.time.Duration.ofDays(10), service.getDelayedActionsRetentionAfterCompletion());
        assertFalse(service.isDelayedActionsRunLaterEnabled());
    }

    @Test
    void shouldFallbackInvalidDelayedActionSettingsToDefaults() throws Exception {
        RuntimeConfig.DelayedActionsConfig delayedActions = RuntimeConfig.DelayedActionsConfig.builder()
                .enabled(true)
                .tickSeconds(0)
                .maxPendingPerSession(-1)
                .maxDelay("not-a-duration")
                .defaultMaxAttempts(0)
                .leaseDuration("nope")
                .retentionAfterCompletion("bad")
                .allowRunLater(null)
                .build();
        persistedSections.put("delayed-actions.json", objectMapper.writeValueAsString(delayedActions));

        assertTrue(service.isDelayedActionsEnabled());
        assertEquals(1, service.getDelayedActionsTickSeconds());
        assertEquals(3, service.getDelayedActionsMaxPendingPerSession());
        assertEquals(java.time.Duration.ofDays(30), service.getDelayedActionsMaxDelay());
        assertEquals(4, service.getDelayedActionsDefaultMaxAttempts());
        assertEquals(java.time.Duration.ofMinutes(2), service.getDelayedActionsLeaseDuration());
        assertEquals(java.time.Duration.ofDays(7), service.getDelayedActionsRetentionAfterCompletion());
        assertTrue(service.isDelayedActionsRunLaterEnabled());
    }

    @Test
    void shouldNormalizeInvalidDelayedActionSettingsDuringRuntimeConfigUpdate() {
        RuntimeConfig newConfig = RuntimeConfig.builder().build();
        RuntimeConfig.DelayedActionsConfig delayedActions = new RuntimeConfig.DelayedActionsConfig();
        delayedActions.setEnabled(null);
        delayedActions.setTickSeconds(0);
        delayedActions.setMaxPendingPerSession(0);
        delayedActions.setMaxDelay("not-a-duration");
        delayedActions.setDefaultMaxAttempts(0);
        delayedActions.setLeaseDuration("bad");
        delayedActions.setRetentionAfterCompletion("bad");
        delayedActions.setAllowRunLater(null);
        newConfig.setDelayedActions(delayedActions);

        service.updateRuntimeConfig(newConfig);

        RuntimeConfig updated = service.getRuntimeConfig();
        assertTrue(updated.getDelayedActions().getEnabled());
        assertEquals(1, updated.getDelayedActions().getTickSeconds());
        assertEquals(3, updated.getDelayedActions().getMaxPendingPerSession());
        assertEquals("PT720H", updated.getDelayedActions().getMaxDelay());
        assertEquals(4, updated.getDelayedActions().getDefaultMaxAttempts());
        assertEquals("PT2M", updated.getDelayedActions().getLeaseDuration());
        assertEquals("PT168H", updated.getDelayedActions().getRetentionAfterCompletion());
        assertTrue(updated.getDelayedActions().getAllowRunLater());
    }

    @Test
    void shouldReturnConfiguredTurnAutoRetrySettings() throws Exception {
        RuntimeConfig.TurnConfig turn = RuntimeConfig.TurnConfig.builder()
                .autoRetryEnabled(false)
                .autoRetryMaxAttempts(5)
                .autoRetryBaseDelayMs(1500L)
                .queueSteeringEnabled(false)
                .queueSteeringMode("all")
                .queueFollowUpMode("single")
                .progressUpdatesEnabled(false)
                .progressIntentEnabled(false)
                .progressBatchSize(6)
                .progressMaxSilenceSeconds(25)
                .progressSummaryTimeoutMs(12000)
                .build();
        persistedSections.put("turn.json", objectMapper.writeValueAsString(turn));

        assertFalse(service.isTurnAutoRetryEnabled());
        assertEquals(5, service.getTurnAutoRetryMaxAttempts());
        assertEquals(1500L, service.getTurnAutoRetryBaseDelayMs());
        assertFalse(service.isTurnQueueSteeringEnabled());
        assertEquals("all", service.getTurnQueueSteeringMode());
        assertEquals("one-at-a-time", service.getTurnQueueFollowUpMode());
        assertFalse(service.isTurnProgressUpdatesEnabled());
        assertFalse(service.isTurnProgressIntentEnabled());
        assertEquals(6, service.getTurnProgressBatchSize());
        assertEquals(java.time.Duration.ofSeconds(25), service.getTurnProgressMaxSilence());
        assertEquals(12000, service.getTurnProgressSummaryTimeoutMs());
    }

    @Test
    void shouldNormalizeTurnAutoRetryDefaultsWhenStoredSectionMissingFields() {
        persistedSections.put("turn.json", "{}");

        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config.getTurn());
        assertTrue(config.getTurn().getAutoRetryEnabled());
        assertEquals(2, config.getTurn().getAutoRetryMaxAttempts());
        assertEquals(600L, config.getTurn().getAutoRetryBaseDelayMs());
        assertTrue(config.getTurn().getQueueSteeringEnabled());
        assertEquals("one-at-a-time", config.getTurn().getQueueSteeringMode());
        assertEquals("one-at-a-time", config.getTurn().getQueueFollowUpMode());
        assertTrue(config.getTurn().getProgressUpdatesEnabled());
        assertTrue(config.getTurn().getProgressIntentEnabled());
        assertEquals(8, config.getTurn().getProgressBatchSize());
        assertEquals(10, config.getTurn().getProgressMaxSilenceSeconds());
        assertEquals(8000, config.getTurn().getProgressSummaryTimeoutMs());
    }

    @Test
    void shouldInitializeTurnWhenNullDuringRuntimeConfigUpdate() {
        RuntimeConfig newConfig = RuntimeConfig.builder().build();
        newConfig.setTurn(null);

        service.updateRuntimeConfig(newConfig);

        RuntimeConfig updated = service.getRuntimeConfig();
        assertNotNull(updated.getTurn());
        assertTrue(updated.getTurn().getAutoRetryEnabled());
        assertEquals(2, updated.getTurn().getAutoRetryMaxAttempts());
        assertEquals(600L, updated.getTurn().getAutoRetryBaseDelayMs());
        assertTrue(updated.getTurn().getQueueSteeringEnabled());
        assertEquals("one-at-a-time", updated.getTurn().getQueueSteeringMode());
        assertEquals("one-at-a-time", updated.getTurn().getQueueFollowUpMode());
        assertTrue(updated.getTurn().getProgressUpdatesEnabled());
        assertTrue(updated.getTurn().getProgressIntentEnabled());
        assertEquals(8, updated.getTurn().getProgressBatchSize());
        assertEquals(10, updated.getTurn().getProgressMaxSilenceSeconds());
        assertEquals(8000, updated.getTurn().getProgressSummaryTimeoutMs());
    }

    @Test
    void shouldNormalizeInvalidTurnProgressSettingsToDefaults() throws Exception {
        RuntimeConfig.TurnConfig turn = RuntimeConfig.TurnConfig.builder()
                .progressBatchSize(0)
                .progressMaxSilenceSeconds(-1)
                .progressSummaryTimeoutMs(100)
                .build();
        persistedSections.put("turn.json", objectMapper.writeValueAsString(turn));

        RuntimeConfig runtimeConfig = service.getRuntimeConfig();

        assertEquals(8, runtimeConfig.getTurn().getProgressBatchSize());
        assertEquals(10, runtimeConfig.getTurn().getProgressMaxSilenceSeconds());
        assertEquals(8000, runtimeConfig.getTurn().getProgressSummaryTimeoutMs());
    }

    @Test
    void shouldNormalizeTurnQueueModeAliasesAndFallbackToDefault() throws Exception {
        RuntimeConfig.TurnConfig turn = RuntimeConfig.TurnConfig.builder()
                .queueSteeringMode("one_at_a_time")
                .queueFollowUpMode("unexpected")
                .build();
        persistedSections.put("turn.json", objectMapper.writeValueAsString(turn));

        assertEquals("one-at-a-time", service.getTurnQueueSteeringMode());
        assertEquals("one-at-a-time", service.getTurnQueueFollowUpMode());
        assertEquals("one-at-a-time", service.getRuntimeConfig().getTurn().getQueueSteeringMode());
        assertEquals("one-at-a-time", service.getRuntimeConfig().getTurn().getQueueFollowUpMode());
    }

    @Test
    void shouldReturnDefaultTurnDeadlineOnInvalidFormat() throws Exception {
        RuntimeConfig.TurnConfig turn = RuntimeConfig.TurnConfig.builder()
                .deadline("not-a-duration")
                .build();
        String json = objectMapper.writeValueAsString(turn);
        persistedSections.put("turn.json", json);

        assertEquals(java.time.Duration.ofHours(1), service.getTurnDeadline());
    }

    @Test
    void shouldReturnDefaultMemorySettings() {
        assertTrue(service.isMemoryEnabled());
        assertEquals(2, service.getMemoryVersion());
        assertEquals(1800, service.getMemorySoftPromptBudgetTokens());
        assertEquals(3500, service.getMemoryMaxPromptBudgetTokens());
        assertEquals(6, service.getMemoryWorkingTopK());
        assertEquals(8, service.getMemoryEpisodicTopK());
        assertEquals(6, service.getMemorySemanticTopK());
        assertEquals(4, service.getMemoryProceduralTopK());
        assertEquals(21, service.getMemoryRetrievalLookbackDays());
        assertTrue(service.isMemoryRerankingEnabled());
        assertEquals("balanced", service.getMemoryRerankingProfile());
    }

    @Test
    void shouldReturnDefaultSkillsSettings() {
        assertTrue(service.isSkillsEnabled());
        assertTrue(service.isSkillsProgressiveLoadingEnabled());
    }

    @Test
    void shouldReturnDefaultToolEnablement() {
        assertTrue(service.isFilesystemEnabled());
        assertTrue(service.isShellEnabled());
        assertTrue(service.isSkillManagementEnabled());
        assertTrue(service.isSkillTransitionEnabled());
        assertTrue(service.isTierToolEnabled());
        assertTrue(service.isGoalManagementEnabled());
        assertTrue(service.isDynamicTierEnabled());
    }

    @Test
    void shouldReturnEmptyShellEnvironmentVariablesByDefault() {
        assertTrue(service.getShellEnvironmentVariables().isEmpty());
    }

    @Test
    void shouldReturnConfiguredShellEnvironmentVariables() throws Exception {
        RuntimeConfig.ToolsConfig tools = RuntimeConfig.ToolsConfig.builder()
                .shellEnvironmentVariables(List.of(
                        RuntimeConfig.ShellEnvironmentVariable.builder()
                                .name("TEST_API_KEY")
                                .value("value-1")
                                .build(),
                        RuntimeConfig.ShellEnvironmentVariable.builder()
                                .name("ANOTHER_VAR")
                                .value("value-2")
                                .build()))
                .build();
        persistedSections.put("tools.json", objectMapper.writeValueAsString(tools));

        Map<String, String> environmentVariables = service.getShellEnvironmentVariables();

        assertEquals(2, environmentVariables.size());
        assertEquals("value-1", environmentVariables.get("TEST_API_KEY"));
        assertEquals("value-2", environmentVariables.get("ANOTHER_VAR"));
    }

    @Test
    void shouldNormalizeNullShellEnvironmentVariablesList() throws Exception {
        persistedSections.put("tools.json", "{\"shellEnvironmentVariables\":null}");

        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config.getTools().getShellEnvironmentVariables());
        assertTrue(config.getTools().getShellEnvironmentVariables().isEmpty());
    }

    @Test
    void shouldIgnoreLegacyPluginOwnedToolFieldsWhenLoadingToolsSection() {
        persistedSections.put("tools.json", """
                {
                  "browserEnabled": true,
                  "braveSearchEnabled": true,
                  "imap": { "enabled": true, "host": "imap.example.com" },
                  "shellEnvironmentVariables": [
                    { "name": "API_TOKEN", "value": "secret" }
                  ]
                }
                """);

        RuntimeConfig config = service.getRuntimeConfig();

        assertEquals(1, config.getTools().getShellEnvironmentVariables().size());
        assertEquals("API_TOKEN", config.getTools().getShellEnvironmentVariables().getFirst().getName());
        assertEquals("secret", config.getTools().getShellEnvironmentVariables().getFirst().getValue());
    }

    @Test
    void shouldNormalizeShellEnvironmentVariablesByTrimmingAndDroppingInvalidEntries() throws Exception {
        persistedSections.put("tools.json", """
                {
                  "shellEnvironmentVariables": [
                    { "name": "  API_TOKEN  ", "value": "value-1" },
                    { "name": "   ", "value": "ignored" },
                    null,
                    { "name": "SECOND_VAR", "value": null }
                  ]
                }
                """);

        RuntimeConfig config = service.getRuntimeConfig();
        Map<String, String> environmentVariables = service.getShellEnvironmentVariables();

        assertEquals(2, config.getTools().getShellEnvironmentVariables().size());
        assertEquals("API_TOKEN", config.getTools().getShellEnvironmentVariables().get(0).getName());
        assertEquals("SECOND_VAR", config.getTools().getShellEnvironmentVariables().get(1).getName());
        assertEquals("value-1", environmentVariables.get("API_TOKEN"));
        assertEquals("", environmentVariables.get("SECOND_VAR"));
    }

    @Test
    void shouldUseLastValueForDuplicateShellEnvironmentVariablesAfterNormalization() throws Exception {
        persistedSections.put("tools.json", """
                {
                  "shellEnvironmentVariables": [
                    { "name": "API_TOKEN", "value": "old" },
                    { "name": " API_TOKEN ", "value": "new" }
                  ]
                }
                """);

        RuntimeConfig config = service.getRuntimeConfig();
        Map<String, String> environmentVariables = service.getShellEnvironmentVariables();

        assertEquals(1, config.getTools().getShellEnvironmentVariables().size());
        assertEquals("API_TOKEN", config.getTools().getShellEnvironmentVariables().get(0).getName());
        assertEquals("new", config.getTools().getShellEnvironmentVariables().get(0).getValue());
        assertEquals("new", environmentVariables.get("API_TOKEN"));
    }

    @Test
    void shouldInitializeToolsWhenNullDuringRuntimeConfigUpdate() {
        RuntimeConfig newConfig = RuntimeConfig.builder().build();
        newConfig.setTools(null);

        service.updateRuntimeConfig(newConfig);

        RuntimeConfig updated = service.getRuntimeConfig();
        assertNotNull(updated.getTools());
        assertNotNull(updated.getTools().getShellEnvironmentVariables());
        assertTrue(updated.getTools().getShellEnvironmentVariables().isEmpty());
    }

    // ==================== Telegram ====================

    @Test
    void shouldReturnFalseWhenTelegramDisabled() {
        assertFalse(service.isTelegramEnabled());
    }

    @Test
    void shouldReturnEmptyTokenWhenNotConfigured() {
        assertEquals("", service.getTelegramToken());
    }

    @Test
    void shouldReturnEmptyAllowedUsersWhenNull() {
        assertNotNull(service.getTelegramAllowedUsers());
        assertTrue(service.getTelegramAllowedUsers().isEmpty());
    }

    // ==================== LLM Providers ====================

    @Test
    void shouldReturnEmptyProviderConfigWhenNotConfigured() {
        RuntimeConfig.LlmProviderConfig config = service.getLlmProviderConfig("unknown");

        assertNotNull(config);
        assertFalse(service.hasLlmProviderApiKey("unknown"));
    }

    @Test
    void shouldDetectConfiguredLlmProviderApiKey() throws Exception {
        Map<String, RuntimeConfig.LlmProviderConfig> providers = new LinkedHashMap<>();
        providers.put("openai", RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("sk-test-key"))
                .build());
        RuntimeConfig.LlmConfig llm = RuntimeConfig.LlmConfig.builder()
                .providers(providers)
                .build();
        String json = objectMapper.writeValueAsString(llm);
        persistedSections.put("llm.json", json);

        assertTrue(service.hasLlmProviderApiKey("openai"));
        assertFalse(service.hasLlmProviderApiKey("anthropic"));
        assertEquals(List.of("openai"), service.getConfiguredLlmProviders());
    }

    // ==================== Secret Redaction ====================

    @Test
    void shouldRedactSecretsInApiResponse() throws Exception {
        // Setup individual section files with secrets
        RuntimeConfig.TelegramConfig telegram = RuntimeConfig.TelegramConfig.builder()
                .token(Secret.of("bot-token-secret"))
                .build();
        persistedSections.put("telegram.json", objectMapper.writeValueAsString(telegram));

        RuntimeConfig.VoiceConfig voice = RuntimeConfig.VoiceConfig.builder()
                .apiKey(Secret.of("voice-key-secret"))
                .whisperSttApiKey(Secret.of("whisper-key-secret"))
                .build();
        persistedSections.put("voice.json", objectMapper.writeValueAsString(voice));

        Map<String, RuntimeConfig.LlmProviderConfig> providers = new LinkedHashMap<>();
        providers.put("openai", RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("openai-secret"))
                .build());
        RuntimeConfig.LlmConfig llm = RuntimeConfig.LlmConfig.builder()
                .providers(providers)
                .build();
        persistedSections.put("llm.json", objectMapper.writeValueAsString(llm));

        RuntimeConfig redacted = service.getRuntimeConfigForApi();

        assertNull(redacted.getTelegram().getToken().getValue());
        assertTrue(redacted.getTelegram().getToken().getPresent());
        assertNull(redacted.getVoice().getApiKey().getValue());
        assertTrue(redacted.getVoice().getApiKey().getPresent());
        assertNull(redacted.getVoice().getWhisperSttApiKey().getValue());
        assertTrue(redacted.getVoice().getWhisperSttApiKey().getPresent());
        assertNull(redacted.getLlm().getProviders().get("openai").getApiKey().getValue());
        assertTrue(redacted.getLlm().getProviders().get("openai").getApiKey().getPresent());
    }

    @Test
    void shouldNotLeakSecretsInOriginalAfterRedaction() throws Exception {
        RuntimeConfig.TelegramConfig telegram = RuntimeConfig.TelegramConfig.builder()
                .token(Secret.of("my-secret-token"))
                .build();
        persistedSections.put("telegram.json", objectMapper.writeValueAsString(telegram));

        service.getRuntimeConfigForApi();
        String token = service.getTelegramToken();

        assertEquals("my-secret-token", token);
    }

    // ==================== Persistence ====================

    @Test
    void shouldPersistOnUpdate() {
        RuntimeConfig newConfig = RuntimeConfig.builder().build();
        newConfig.getModelRouter().setBalancedModel("custom/model");

        service.updateRuntimeConfig(newConfig);

        verify(storagePort, times(RuntimeConfig.ConfigSection.values().length))
                .putTextAtomic(anyString(), anyString(), anyString(), anyBoolean());

        RuntimeConfig updated = service.getRuntimeConfig();
        assertEquals("custom/model", updated.getModelRouter().getBalancedModel());
    }

    @Test
    void shouldThrowAndRollbackOnPersistFailure() throws Exception {
        // Setup initial config section
        RuntimeConfig.TelegramConfig telegram = RuntimeConfig.TelegramConfig.builder()
                .enabled(false)
                .build();
        persistedSections.put("telegram.json", objectMapper.writeValueAsString(telegram));

        // Load initial config
        service.getRuntimeConfig();

        // Setup persist failure for next update
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk full")));

        // Try to update with different value - should throw and rollback
        RuntimeConfig newConfig = RuntimeConfig.builder().build();
        newConfig.getTelegram().setEnabled(true);

        assertThrows(IllegalStateException.class, () -> service.updateRuntimeConfig(newConfig));

        // Verify rollback - should still have original config value
        assertFalse(service.getRuntimeConfig().getTelegram().getEnabled());
    }

    // ==================== Normalization ====================

    @Test
    void shouldNormalizeLlmConfigWhenNull() throws Exception {
        // Put empty JSON for llm section
        persistedSections.put("llm.json", "{}");

        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config.getLlm());
        assertNotNull(config.getLlm().getProviders());
    }

    @Test
    void shouldNormalizeSecretPresenceFlags() throws Exception {
        Secret secretWithValue = Secret.builder().value("test-key").present(null).encrypted(null).build();
        RuntimeConfig.TelegramConfig telegram = RuntimeConfig.TelegramConfig.builder()
                .token(secretWithValue)
                .build();
        persistedSections.put("telegram.json", objectMapper.writeValueAsString(telegram));

        RuntimeConfig loaded = service.getRuntimeConfig();

        assertTrue(loaded.getTelegram().getToken().getPresent());
        assertFalse(loaded.getTelegram().getToken().getEncrypted());
    }

    @Test
    void shouldNormalizeMemoryVersionToTwoWhenMissingInStoredSection() throws Exception {
        persistedSections.put("memory.json", "{\"version\":null,\"enabled\":true}");

        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config.getMemory());
        assertEquals(2, config.getMemory().getVersion());
        assertEquals(2, service.getMemoryVersion());
    }

    @Test
    void shouldNormalizeMissingMemoryRerankingConfigToDefaults() throws Exception {
        persistedSections.put(
                "memory.json",
                "{\"version\":2,\"enabled\":true,\"reranking\":{\"enabled\":null,\"profile\":\"  \"}}");

        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config.getMemory().getReranking());
        assertTrue(config.getMemory().getReranking().getEnabled());
        assertEquals("balanced", config.getMemory().getReranking().getProfile());
        assertTrue(service.isMemoryRerankingEnabled());
        assertEquals("balanced", service.getMemoryRerankingProfile());
    }

    @Test
    void shouldForceMemoryVersionTwoWhenUpdatingRuntimeConfig() {
        RuntimeConfig newConfig = RuntimeConfig.builder().build();
        newConfig.getMemory().setVersion(1);
        newConfig.getMemory().setEnabled(true);

        service.updateRuntimeConfig(newConfig);

        RuntimeConfig updated = service.getRuntimeConfig();
        assertEquals(2, updated.getMemory().getVersion());
        assertEquals(2, service.getMemoryVersion());
    }

    // ==================== Invite Codes ====================

    @Test
    void shouldGenerateInviteCode() {
        RuntimeConfig.InviteCode code = service.generateInviteCode();

        assertNotNull(code);
        assertNotNull(code.getCode());
        assertEquals(20, code.getCode().length());
        assertFalse(code.isUsed());
        assertNotNull(code.getCreatedAt());
        verify(storagePort, atLeast(RuntimeConfig.ConfigSection.values().length))
                .putTextAtomic(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void shouldGenerateUniqueInviteCodes() {
        RuntimeConfig.InviteCode code1 = service.generateInviteCode();
        RuntimeConfig.InviteCode code2 = service.generateInviteCode();

        assertFalse(code1.getCode().equals(code2.getCode()));
    }

    @Test
    void shouldRedeemInviteCode() {
        RuntimeConfig.InviteCode generated = service.generateInviteCode();

        boolean redeemed = service.redeemInviteCode(generated.getCode(), "user123");

        assertTrue(redeemed);
        assertTrue(service.getTelegramAllowedUsers().contains("user123"));
    }

    @Test
    void shouldNotRedeemUsedInviteCode() {
        RuntimeConfig.InviteCode generated = service.generateInviteCode();
        service.redeemInviteCode(generated.getCode(), "user1");

        boolean secondRedeem = service.redeemInviteCode(generated.getCode(), "user2");

        assertFalse(secondRedeem);
        assertFalse(service.getTelegramAllowedUsers().contains("user2"));
    }

    @Test
    void shouldNotRedeemNonexistentCode() {
        boolean result = service.redeemInviteCode("NONEXISTENT", "user1");

        assertFalse(result);
    }

    @Test
    void shouldNotDuplicateUserOnRedeem() {
        RuntimeConfig.InviteCode code1 = service.generateInviteCode();
        RuntimeConfig.InviteCode code2 = service.generateInviteCode();
        service.redeemInviteCode(code1.getCode(), "user1");
        service.redeemInviteCode(code2.getCode(), "user1");

        long count = service.getTelegramAllowedUsers().stream()
                .filter("user1"::equals)
                .count();
        assertEquals(1, count);
    }

    @Test
    void shouldNotRedeemInviteCodeForAnotherUserWhenUserAlreadyRegistered() {
        RuntimeConfig.InviteCode firstCode = service.generateInviteCode();
        RuntimeConfig.InviteCode secondCode = service.generateInviteCode();
        service.redeemInviteCode(firstCode.getCode(), "user1");

        boolean redeemed = service.redeemInviteCode(secondCode.getCode(), "user2");

        assertFalse(redeemed);
        assertEquals(List.of("user1"), service.getTelegramAllowedUsers());
        RuntimeConfig.InviteCode secondInvite = service.getRuntimeConfig().getTelegram().getInviteCodes().stream()
                .filter(code -> secondCode.getCode().equals(code.getCode()))
                .findFirst()
                .orElseThrow();
        assertFalse(secondInvite.isUsed());
    }

    @Test
    void shouldRedeemInviteCodeWhenAllowedUsersListIsImmutable() {
        RuntimeConfig.InviteCode inviteCode = service.generateInviteCode();
        service.getRuntimeConfig().getTelegram().setAllowedUsers(List.of());

        boolean redeemed = service.redeemInviteCode(inviteCode.getCode(), "user1");

        assertTrue(redeemed);
        assertEquals(List.of("user1"), service.getTelegramAllowedUsers());
    }

    @Test
    void shouldGenerateInviteCodeWhenInviteCodesListIsImmutable() {
        service.getRuntimeConfig().getTelegram().setInviteCodes(List.of(RuntimeConfig.InviteCode.builder()
                .code("EXISTINGINVITECODE01")
                .used(false)
                .createdAt(java.time.Instant.now())
                .build()));

        RuntimeConfig.InviteCode generated = service.generateInviteCode();

        assertNotNull(generated);
        assertEquals(2, service.getRuntimeConfig().getTelegram().getInviteCodes().size());
        assertTrue(service.getRuntimeConfig().getTelegram().getInviteCodes().stream()
                .anyMatch(inviteCode -> generated.getCode().equals(inviteCode.getCode())));
    }

    @Test
    void shouldRemoveTelegramAllowedUser() {
        RuntimeConfig.InviteCode code = service.generateInviteCode();
        service.redeemInviteCode(code.getCode(), "user1");

        boolean removed = service.removeTelegramAllowedUser("user1");

        assertTrue(removed);
        assertFalse(service.getTelegramAllowedUsers().contains("user1"));
        assertTrue(service.getTelegramAllowedUsers().isEmpty());
    }

    @Test
    void shouldRevokeActiveInviteCodesWhenRemovingTelegramAllowedUser() {
        RuntimeConfig.InviteCode redeemedCode = service.generateInviteCode();
        RuntimeConfig.InviteCode activeCode = service.generateInviteCode();
        service.redeemInviteCode(redeemedCode.getCode(), "user1");

        boolean removed = service.removeTelegramAllowedUser("user1");

        assertTrue(removed);
        List<RuntimeConfig.InviteCode> remainingCodes = service.getRuntimeConfig().getTelegram().getInviteCodes();
        assertTrue(remainingCodes.stream()
                .anyMatch(code -> redeemedCode.getCode().equals(code.getCode()) && code.isUsed()));
        assertFalse(remainingCodes.stream()
                .anyMatch(code -> activeCode.getCode().equals(code.getCode())));
    }

    @Test
    void shouldReturnFalseWhenRemovingUnknownTelegramAllowedUser() {
        RuntimeConfig.InviteCode code = service.generateInviteCode();
        service.redeemInviteCode(code.getCode(), "user1");

        boolean removed = service.removeTelegramAllowedUser("missing");

        assertFalse(removed);
        assertTrue(service.getTelegramAllowedUsers().contains("user1"));
    }

    @Test
    void shouldRevokeInviteCode() {
        RuntimeConfig.InviteCode generated = service.generateInviteCode();

        boolean revoked = service.revokeInviteCode(generated.getCode());

        assertTrue(revoked);
    }

    @Test
    void shouldReturnFalseWhenRevokingNonexistentCode() {
        boolean revoked = service.revokeInviteCode("NONEXISTENT");

        assertFalse(revoked);
    }

    @Test
    void shouldNotRedeemAfterRevocation() {
        RuntimeConfig.InviteCode generated = service.generateInviteCode();
        service.revokeInviteCode(generated.getCode());

        boolean redeemed = service.redeemInviteCode(generated.getCode(), "user1");

        assertFalse(redeemed);
    }

    // ==================== Telegram Auth Mode ====================

    @Test
    void shouldNormalizeTelegramAuthModeToInviteOnly() throws Exception {
        RuntimeConfig.TelegramConfig telegram = RuntimeConfig.TelegramConfig.builder()
                .authMode("user")
                .build();
        persistedSections.put("telegram.json", objectMapper.writeValueAsString(telegram));

        RuntimeConfig config = service.getRuntimeConfig();

        assertEquals("invite_only", config.getTelegram().getAuthMode());
    }

    // ==================== Voice Telegram Options ====================

    @Test
    void shouldReturnFalseForVoiceTelegramOptions() {
        assertFalse(service.isTelegramRespondWithVoiceEnabled());
        assertFalse(service.isTelegramTranscribeIncomingEnabled());
    }

    @Test
    void shouldDetectWhisperProviderWhenConfigured() throws Exception {
        RuntimeConfig.VoiceConfig voice = RuntimeConfig.VoiceConfig.builder()
                .sttProvider("whisper")
                .ttsProvider("elevenlabs")
                .whisperSttUrl("http://localhost:5092")
                .whisperSttApiKey(Secret.of("whisper-secret"))
                .build();
        persistedSections.put("voice.json", objectMapper.writeValueAsString(voice));

        assertEquals("golemcore/whisper", service.getSttProvider());
        assertEquals("golemcore/elevenlabs", service.getTtsProvider());
        assertEquals("http://localhost:5092", service.getWhisperSttUrl());
        assertEquals("whisper-secret", service.getWhisperSttApiKey());
        assertTrue(service.isWhisperSttConfigured());
    }

    @Test
    void shouldNotConsiderWhisperConfiguredWhenUrlBlank() throws Exception {
        RuntimeConfig.VoiceConfig voice = RuntimeConfig.VoiceConfig.builder()
                .sttProvider("whisper")
                .whisperSttUrl("")
                .build();
        persistedSections.put("voice.json", objectMapper.writeValueAsString(voice));

        assertFalse(service.isWhisperSttConfigured());
    }

    // ==================== Usage ====================

    @Test
    void shouldReturnUsageEnabledByDefault() {
        assertTrue(service.isUsageEnabled());
    }

    @Test
    void shouldReturnDefaultPlanSettings() {
        assertFalse(service.isPlanEnabled());
        assertEquals(5, service.getPlanMaxPlans());
        assertEquals(50, service.getPlanMaxStepsPerPlan());
        assertTrue(service.isPlanStopOnFailure());
    }

    @Test
    void shouldReturnConfiguredPlanSettings() throws Exception {
        RuntimeConfig.PlanConfig plan = RuntimeConfig.PlanConfig.builder()
                .enabled(true)
                .maxPlans(8)
                .maxStepsPerPlan(120)
                .stopOnFailure(false)
                .build();
        persistedSections.put("plan.json", objectMapper.writeValueAsString(plan));

        assertTrue(service.isPlanEnabled());
        assertEquals(8, service.getPlanMaxPlans());
        assertEquals(120, service.getPlanMaxStepsPerPlan());
        assertFalse(service.isPlanStopOnFailure());
    }

    @Test
    void shouldReturnConfiguredHiveSettings() throws Exception {
        RuntimeConfig.HiveConfig hive = RuntimeConfig.HiveConfig.builder()
                .enabled(true)
                .serverUrl("https://hive.example.com")
                .autoConnect(true)
                .managedByProperties(true)
                .build();
        persistedSections.put("hive.json", objectMapper.writeValueAsString(hive));

        assertTrue(service.getHiveConfig().getEnabled());
        assertEquals("https://hive.example.com", service.getHiveConfig().getServerUrl());
        assertTrue(service.isHiveManagedByProperties());
        assertTrue(service.isHiveSdlcCurrentContextEnabled());
        assertTrue(service.isHiveSdlcCardReadEnabled());
        assertTrue(service.isHiveSdlcCardSearchEnabled());
        assertTrue(service.isHiveSdlcThreadMessageEnabled());
        assertTrue(service.isHiveSdlcReviewRequestEnabled());
        assertTrue(service.isHiveSdlcFollowupCardCreateEnabled());
        assertTrue(service.isHiveSdlcLifecycleSignalEnabled());
    }

    @Test
    void shouldDisableHiveSdlcFunctionsWhenHiveIsDisabled() {
        RuntimeConfig config = service.getRuntimeConfig();
        config.getHive().setEnabled(false);
        config.getHive().setSdlc(RuntimeConfig.HiveSdlcConfig.builder()
                .currentContextEnabled(true)
                .cardReadEnabled(true)
                .cardSearchEnabled(true)
                .threadMessageEnabled(true)
                .reviewRequestEnabled(true)
                .followupCardCreateEnabled(true)
                .lifecycleSignalEnabled(true)
                .build());
        service.updateRuntimeConfig(config);

        assertFalse(service.isHiveSdlcCurrentContextEnabled());
        assertFalse(service.isHiveSdlcCardReadEnabled());
        assertFalse(service.isHiveSdlcCardSearchEnabled());
        assertFalse(service.isHiveSdlcThreadMessageEnabled());
        assertFalse(service.isHiveSdlcReviewRequestEnabled());
        assertFalse(service.isHiveSdlcFollowupCardCreateEnabled());
        assertFalse(service.isHiveSdlcLifecycleSignalEnabled());
    }

    @Test
    void shouldRespectDisabledHiveSdlcFunctionTogglesWhenHiveIsEnabled() {
        RuntimeConfig config = service.getRuntimeConfig();
        config.getHive().setEnabled(true);
        config.getHive().setSdlc(RuntimeConfig.HiveSdlcConfig.builder()
                .currentContextEnabled(false)
                .cardReadEnabled(false)
                .cardSearchEnabled(false)
                .threadMessageEnabled(false)
                .reviewRequestEnabled(false)
                .followupCardCreateEnabled(false)
                .lifecycleSignalEnabled(false)
                .build());
        service.updateRuntimeConfig(config);

        assertFalse(service.isHiveSdlcCurrentContextEnabled());
        assertFalse(service.isHiveSdlcCardReadEnabled());
        assertFalse(service.isHiveSdlcCardSearchEnabled());
        assertFalse(service.isHiveSdlcThreadMessageEnabled());
        assertFalse(service.isHiveSdlcReviewRequestEnabled());
        assertFalse(service.isHiveSdlcFollowupCardCreateEnabled());
        assertFalse(service.isHiveSdlcLifecycleSignalEnabled());
    }

    // ==================== Section Validation ====================

    @Test
    void shouldValidateKnownConfigSections() {
        assertTrue(RuntimeConfigService.isValidConfigSection("telegram"));
        assertTrue(RuntimeConfigService.isValidConfigSection("model-router"));
        assertTrue(RuntimeConfigService.isValidConfigSection("llm"));
        assertTrue(RuntimeConfigService.isValidConfigSection("tools"));
        assertTrue(RuntimeConfigService.isValidConfigSection("voice"));
        assertTrue(RuntimeConfigService.isValidConfigSection("auto-mode"));
        assertTrue(RuntimeConfigService.isValidConfigSection("rate-limit"));
        assertTrue(RuntimeConfigService.isValidConfigSection("security"));
        assertTrue(RuntimeConfigService.isValidConfigSection("compaction"));
        assertTrue(RuntimeConfigService.isValidConfigSection("turn"));
        assertTrue(RuntimeConfigService.isValidConfigSection("memory"));
        assertTrue(RuntimeConfigService.isValidConfigSection("skills"));
        assertTrue(RuntimeConfigService.isValidConfigSection("usage"));
        assertTrue(RuntimeConfigService.isValidConfigSection("mcp"));
        assertTrue(RuntimeConfigService.isValidConfigSection("plan"));
    }

    @Test
    void shouldRejectUnknownConfigSections() {
        assertFalse(RuntimeConfigService.isValidConfigSection("unknown"));
        assertFalse(RuntimeConfigService.isValidConfigSection("brave"));
        assertFalse(RuntimeConfigService.isValidConfigSection("runtime-config"));
        assertFalse(RuntimeConfigService.isValidConfigSection(""));
        assertFalse(RuntimeConfigService.isValidConfigSection(null));
    }

    @Test
    void shouldValidateSectionCaseInsensitively() {
        assertTrue(RuntimeConfigService.isValidConfigSection("TELEGRAM"));
        assertTrue(RuntimeConfigService.isValidConfigSection("Model-Router"));
        assertTrue(RuntimeConfigService.isValidConfigSection("AUTO-MODE"));
        assertTrue(RuntimeConfigService.isValidConfigSection("PLAN"));
    }

    // ==================== ConfigSection Enum ====================

    @Test
    void shouldReturnCorrectFileNameForSection() {
        assertEquals("telegram.json", RuntimeConfig.ConfigSection.TELEGRAM.getFileName());
        assertEquals("model-router.json", RuntimeConfig.ConfigSection.MODEL_ROUTER.getFileName());
        assertEquals("auto-mode.json", RuntimeConfig.ConfigSection.AUTO_MODE.getFileName());
        assertEquals("plan.json", RuntimeConfig.ConfigSection.PLAN.getFileName());
        assertEquals("hive.json", RuntimeConfig.ConfigSection.HIVE.getFileName());
    }

    @Test
    void shouldFindSectionByFileId() {
        assertTrue(RuntimeConfig.ConfigSection.fromFileId("telegram").isPresent());
        assertEquals(RuntimeConfig.ConfigSection.TELEGRAM, RuntimeConfig.ConfigSection.fromFileId("telegram").get());
        assertTrue(RuntimeConfig.ConfigSection.fromFileId("model-router").isPresent());
        assertEquals(RuntimeConfig.ConfigSection.MODEL_ROUTER,
                RuntimeConfig.ConfigSection.fromFileId("model-router").get());
        assertTrue(RuntimeConfig.ConfigSection.fromFileId("plan").isPresent());
        assertEquals(RuntimeConfig.ConfigSection.PLAN, RuntimeConfig.ConfigSection.fromFileId("plan").get());
        assertTrue(RuntimeConfig.ConfigSection.fromFileId("hive").isPresent());
        assertEquals(RuntimeConfig.ConfigSection.HIVE, RuntimeConfig.ConfigSection.fromFileId("hive").get());
    }

    @Test
    void shouldReturnEmptyForUnknownFileId() {
        assertFalse(RuntimeConfig.ConfigSection.fromFileId("unknown").isPresent());
        assertFalse(RuntimeConfig.ConfigSection.fromFileId("").isPresent());
        assertFalse(RuntimeConfig.ConfigSection.fromFileId(null).isPresent());
    }

    // ==================== Modular Storage ====================

    @Test
    void shouldLoadAllSectionsIndependently() throws Exception {
        // Setup different values in different section files
        RuntimeConfig.TelegramConfig telegram = RuntimeConfig.TelegramConfig.builder()
                .enabled(true)
                .build();
        persistedSections.put("telegram.json", objectMapper.writeValueAsString(telegram));

        RuntimeConfig.ModelRouterConfig modelRouter = RuntimeConfig.ModelRouterConfig.builder()
                .balancedModel("custom/balanced")
                .build();
        persistedSections.put("model-router.json", objectMapper.writeValueAsString(modelRouter));

        RuntimeConfig.VoiceConfig voice = RuntimeConfig.VoiceConfig.builder()
                .enabled(true)
                .voiceId("custom-voice")
                .build();
        persistedSections.put("voice.json", objectMapper.writeValueAsString(voice));

        RuntimeConfig.HiveConfig hive = RuntimeConfig.HiveConfig.builder()
                .enabled(true)
                .serverUrl("https://hive.example.com")
                .managedByProperties(true)
                .build();
        persistedSections.put("hive.json", objectMapper.writeValueAsString(hive));

        RuntimeConfig config = service.getRuntimeConfig();

        // Verify each section loaded correctly
        assertTrue(config.getTelegram().getEnabled());
        assertEquals("custom/balanced", config.getModelRouter().getBalancedModel());
        assertTrue(config.getVoice().getEnabled());
        assertEquals("custom-voice", config.getVoice().getVoiceId());
        assertTrue(config.getHive().getEnabled());
        assertEquals("https://hive.example.com", config.getHive().getServerUrl());
        assertTrue(config.getHive().getManagedByProperties());
    }

    @Test
    void shouldPersistToIndividualSectionFiles() throws Exception {
        RuntimeConfig newConfig = RuntimeConfig.builder().build();
        newConfig.getTelegram().setEnabled(true);
        newConfig.getModelRouter().setBalancedModel("updated/model");

        service.updateRuntimeConfig(newConfig);

        // Verify sections were persisted to individual files
        assertTrue(persistedSections.containsKey("telegram.json"));
        assertTrue(persistedSections.containsKey("model-router.json"));
        assertTrue(persistedSections.containsKey("llm.json"));
        assertTrue(persistedSections.containsKey("tools.json"));
        assertTrue(persistedSections.containsKey("tracing.json"));
        assertTrue(persistedSections.containsKey("plan.json"));
        assertTrue(persistedSections.containsKey("hive.json"));
    }

    // ==================== MCP Catalog CRUD ====================

    @Test
    void shouldReturnEmptyCatalogWhenMcpConfigIsNull() {
        List<RuntimeConfig.McpCatalogEntry> catalog = service.getMcpCatalog();
        assertNotNull(catalog);
        assertTrue(catalog.isEmpty());
    }

    @Test
    void shouldReturnEmptyCatalogWhenCatalogListIsNull() throws Exception {
        RuntimeConfig config = service.getRuntimeConfig();
        RuntimeConfig.McpConfig mcp = new RuntimeConfig.McpConfig();
        mcp.setCatalog(null);
        config.setMcp(mcp);
        setCachedConfig(config);

        List<RuntimeConfig.McpCatalogEntry> catalog = service.getMcpCatalog();
        assertNotNull(catalog);
        assertTrue(catalog.isEmpty());
    }

    @Test
    void shouldAddMcpCatalogEntry() {
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .description("GitHub API")
                .command("npx github-mcp")
                .enabled(true)
                .build();

        service.addMcpCatalogEntry(entry);

        List<RuntimeConfig.McpCatalogEntry> catalog = service.getMcpCatalog();
        assertEquals(1, catalog.size());
        assertEquals("github", catalog.get(0).getName());
        assertEquals("npx github-mcp", catalog.get(0).getCommand());
        verify(storagePort, atLeast(1)).putTextAtomic(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void shouldAddMcpCatalogEntryWhenMcpConfigIsNull() {
        RuntimeConfig config = service.getRuntimeConfig();
        config.setMcp(null);

        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("slack")
                .command("npx slack-mcp")
                .build();

        service.addMcpCatalogEntry(entry);

        List<RuntimeConfig.McpCatalogEntry> catalog = service.getMcpCatalog();
        assertEquals(1, catalog.size());
        assertEquals("slack", catalog.get(0).getName());
    }

    @Test
    void shouldAddMultipleMcpCatalogEntries() {
        service.addMcpCatalogEntry(RuntimeConfig.McpCatalogEntry.builder()
                .name("github").command("npx github").build());
        service.addMcpCatalogEntry(RuntimeConfig.McpCatalogEntry.builder()
                .name("slack").command("npx slack").build());

        List<RuntimeConfig.McpCatalogEntry> catalog = service.getMcpCatalog();
        assertEquals(2, catalog.size());
    }

    @Test
    void shouldUpdateExistingMcpCatalogEntry() {
        service.addMcpCatalogEntry(RuntimeConfig.McpCatalogEntry.builder()
                .name("github").command("npx old-github").build());

        RuntimeConfig.McpCatalogEntry updated = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .command("npx new-github")
                .description("Updated GitHub")
                .build();

        boolean result = service.updateMcpCatalogEntry("github", updated);

        assertTrue(result);
        List<RuntimeConfig.McpCatalogEntry> catalog = service.getMcpCatalog();
        assertEquals(1, catalog.size());
        assertEquals("npx new-github", catalog.get(0).getCommand());
        assertEquals("github", catalog.get(0).getName());
    }

    @Test
    void shouldPreserveOriginalNameOnUpdate() {
        service.addMcpCatalogEntry(RuntimeConfig.McpCatalogEntry.builder()
                .name("github").command("npx github").build());

        RuntimeConfig.McpCatalogEntry updated = RuntimeConfig.McpCatalogEntry.builder()
                .name("different-name")
                .command("npx updated")
                .build();

        boolean result = service.updateMcpCatalogEntry("github", updated);

        assertTrue(result);
        assertEquals("github", service.getMcpCatalog().get(0).getName());
    }

    @Test
    void shouldReturnFalseWhenUpdatingNonexistentEntry() {
        boolean result = service.updateMcpCatalogEntry("nonexistent",
                RuntimeConfig.McpCatalogEntry.builder().name("nonexistent").command("npx test").build());
        assertFalse(result);
    }

    @Test
    void shouldRemoveMcpCatalogEntry() {
        service.addMcpCatalogEntry(RuntimeConfig.McpCatalogEntry.builder()
                .name("github").command("npx github").build());
        service.addMcpCatalogEntry(RuntimeConfig.McpCatalogEntry.builder()
                .name("slack").command("npx slack").build());

        boolean result = service.removeMcpCatalogEntry("github");

        assertTrue(result);
        List<RuntimeConfig.McpCatalogEntry> catalog = service.getMcpCatalog();
        assertEquals(1, catalog.size());
        assertEquals("slack", catalog.get(0).getName());
    }

    @Test
    void shouldReturnFalseWhenRemovingNonexistentEntry() {
        boolean result = service.removeMcpCatalogEntry("nonexistent");
        assertFalse(result);
    }

    @Test
    void shouldNotCallUpdateWhenRemoveFindsNoMatch() {
        // Trigger initial load to establish baseline
        service.getRuntimeConfig();
        int sectionCountBefore = persistedSections.size();

        boolean result = service.removeMcpCatalogEntry("nonexistent");

        assertFalse(result);
        // No additional persistence should happen when nothing was removed
        assertEquals(sectionCountBefore, persistedSections.size());
    }

    @Test
    void shouldEnsureMcpConfigCreatesNewConfigWhenNull() {
        RuntimeConfig config = service.getRuntimeConfig();
        config.setMcp(null);

        service.addMcpCatalogEntry(RuntimeConfig.McpCatalogEntry.builder()
                .name("test").command("npx test").build());

        assertNotNull(config.getMcp());
        assertNotNull(config.getMcp().getCatalog());
        assertEquals(1, config.getMcp().getCatalog().size());
    }

    @Test
    void shouldEnsureMcpCatalogCreatesNewListWhenNull() throws Exception {
        RuntimeConfig config = service.getRuntimeConfig();
        RuntimeConfig.McpConfig mcp = new RuntimeConfig.McpConfig();
        mcp.setCatalog(null);
        config.setMcp(mcp);
        setCachedConfig(config);

        service.addMcpCatalogEntry(RuntimeConfig.McpCatalogEntry.builder()
                .name("test").command("npx test").build());

        assertNotNull(mcp.getCatalog());
        assertEquals(1, mcp.getCatalog().size());
    }

    @Test
    void shouldDefaultResilienceFallbackTierToBalanced() {
        RuntimeConfig defaults = service.getRuntimeConfig();

        assertEquals("balanced", defaults.getResilience().getDegradationFallbackModelTier());
    }

    @Test
    void shouldNormalizeInvalidResilienceFallbackTierToBalanced() {
        RuntimeConfig config = service.snapshotRuntimeConfig();
        config.getResilience().setDegradationFallbackModelTier("fast");

        service.updateRuntimeConfig(config);

        assertEquals("balanced", service.getResilienceConfig().getDegradationFallbackModelTier());
    }

    @Test
    void shouldReadResilienceDefaultsAndDisabledFlag() {
        RuntimeConfig defaults = service.getRuntimeConfig();

        assertTrue(service.isResilienceEnabled());
        assertTrue(defaults.getResilience().getColdRetryEnabled());
        assertEquals(5, defaults.getResilience().getHotRetryMaxAttempts());

        RuntimeConfig disabled = service.snapshotRuntimeConfig();
        disabled.getResilience().setEnabled(false);
        service.updateRuntimeConfig(disabled);

        assertFalse(service.isResilienceEnabled());
        assertFalse(service.getResilienceConfig().getEnabled());
    }

    @SuppressWarnings({ "PMD.AvoidAccessibilityAlteration", "unchecked" })
    private void setCachedConfig(RuntimeConfig config) throws Exception {
        java.lang.reflect.Field field = RuntimeConfigService.class.getDeclaredField("configRef");
        field.setAccessible(true);
        java.util.concurrent.atomic.AtomicReference<RuntimeConfig> ref = (java.util.concurrent.atomic.AtomicReference<RuntimeConfig>) field
                .get(service);
        ref.set(config);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> readPersistedJsonMap(String fileName) throws Exception {
        return objectMapper.readValue(persistedSections.get(fileName), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> castMap(Object value) {
        assertNotNull(value);
        assertTrue(value instanceof Map<?, ?>);
        return (Map<?, ?>) value;
    }

    private void assertPersistedModelId(Map<?, ?> binding, String expectedId) {
        Map<?, ?> model = castMap(binding.get("model"));
        assertEquals(expectedId, model.get("id"));
        assertFalse(model.containsKey("provider"));
    }

    private void assertPersistedModelMissing(Map<?, ?> binding) {
        assertTrue(!binding.containsKey("model") || binding.get("model") == null);
    }
}
