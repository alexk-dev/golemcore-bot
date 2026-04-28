package me.golemcore.bot.infrastructure.telemetry;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.catalog.ModelCatalogEntry;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.plugin.runtime.PluginManager;
import me.golemcore.bot.plugin.runtime.PluginRuntimeInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryHeartbeatSchedulerTest {

    private RuntimeConfigService runtimeConfigService;
    private ModelConfigService modelConfigService;
    private PluginManager pluginManager;
    private TelemetryEventPublisher publisher;
    private TelemetryHeartbeatScheduler heartbeatScheduler;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        modelConfigService = mock(ModelConfigService.class);
        pluginManager = mock(PluginManager.class);
        publisher = mock(TelemetryEventPublisher.class);

        RuntimeConfig runtimeConfig = new RuntimeConfig();
        RuntimeConfig.ModelRouterConfig routerConfig = RuntimeConfig.ModelRouterConfig.builder()
                .tiers(new LinkedHashMap<>(Map.of(
                        "balanced", RuntimeConfig.TierBinding.builder().build(),
                        "smart", RuntimeConfig.TierBinding.builder().build())))
                .build();
        runtimeConfig.setModelRouter(routerConfig);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.isTelegramEnabled()).thenReturn(true);
        when(runtimeConfigService.isVoiceEnabled()).thenReturn(false);
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigService.isTelemetryEnabled()).thenReturn(true);

        ModelCatalogEntry modelSettings = new ModelCatalogEntry();
        when(modelConfigService.getAllModels()).thenReturn(
                new LinkedHashMap<>(Map.of("gpt-4o", modelSettings, "claude-sonnet", modelSettings)));

        PluginRuntimeInfo plugin = PluginRuntimeInfo.builder().id("weather").name("Weather").build();
        when(pluginManager.listPlugins()).thenReturn(List.of(plugin));

        heartbeatScheduler = new TelemetryHeartbeatScheduler(publisher, runtimeConfigService,
                modelConfigService, pluginManager);
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldPublishHeartbeatAndCapabilityEvents() {
        heartbeatScheduler.sendHeartbeatNow();

        ArgumentCaptor<Map<String, Object>> heartbeatCaptor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publishEvent(eq("heartbeat"), heartbeatCaptor.capture());
        Map<String, Object> heartbeatParams = heartbeatCaptor.getValue();
        assertEquals("system", heartbeatParams.get("feature_area"));
        assertNotNull(heartbeatParams.get("os_name"));
        assertEquals(2, heartbeatParams.get("model_count"));
        assertEquals(1, heartbeatParams.get("plugin_count"));

        // capability events: 3 enabled features + 1 plugin + 2 models + 2 tiers = 8
        verify(publisher, atLeastOnce()).publishEvent(eq("capability"), anyMap());

        ArgumentCaptor<Map<String, Object>> capCaptor = ArgumentCaptor.forClass(Map.class);
        verify(publisher, atLeastOnce()).publishEvent(eq("capability"), capCaptor.capture());
        List<Map<String, Object>> allCaps = capCaptor.getAllValues();

        long featureCount = allCaps.stream()
                .filter(p -> "feature".equals(p.get("capability_type")))
                .count();
        assertEquals(3, featureCount); // telegram, selfevolving, telemetry (voice is false)

        long pluginCount = allCaps.stream()
                .filter(p -> "plugin".equals(p.get("capability_type")))
                .count();
        assertEquals(1, pluginCount);

        long modelCount = allCaps.stream()
                .filter(p -> "model".equals(p.get("capability_type")))
                .count();
        assertEquals(2, modelCount);

        long tierCount = allCaps.stream()
                .filter(p -> "tier".equals(p.get("capability_type")))
                .count();
        assertEquals(2, tierCount);
    }
}
