package me.golemcore.bot.infrastructure.telemetry;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.plugin.runtime.PluginManager;
import me.golemcore.bot.plugin.runtime.PluginRuntimeInfo;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class TelemetryHeartbeatScheduler {

    private static final long HEARTBEAT_INTERVAL_MINUTES = 60;

    private final TelemetryRollupStore telemetryRollupStore;
    private final TelemetryEventPublisher telemetryEventPublisher;
    private final RuntimeConfigService runtimeConfigService;
    private final ModelConfigService modelConfigService;
    private final PluginManager pluginManager;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "telemetry-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public TelemetryHeartbeatScheduler(TelemetryRollupStore telemetryRollupStore,
            TelemetryEventPublisher telemetryEventPublisher,
            RuntimeConfigService runtimeConfigService,
            ModelConfigService modelConfigService,
            PluginManager pluginManager) {
        this.telemetryRollupStore = telemetryRollupStore;
        this.telemetryEventPublisher = telemetryEventPublisher;
        this.runtimeConfigService = runtimeConfigService;
        this.modelConfigService = modelConfigService;
        this.pluginManager = pluginManager;
    }

    @PostConstruct
    void start() {
        executor.scheduleAtFixedRate(this::sendHeartbeatSafely, 5, HEARTBEAT_INTERVAL_MINUTES * 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    public void sendHeartbeatNow() {
        String distinctId = "backend:" + telemetryRollupStore.getAnonymousInstanceId();
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("os_name", System.getProperty("os.name"));
        properties.put("os_arch", System.getProperty("os.arch"));
        properties.put("java_version", System.getProperty("java.version"));

        List<String> pluginIds = pluginManager.listPlugins().stream()
                .map(PluginRuntimeInfo::getId)
                .toList();
        properties.put("enabled_plugins", pluginIds);

        List<String> modelIds = List.copyOf(modelConfigService.getAllModels().keySet());
        properties.put("model_ids", modelIds);

        List<String> tiers = List.of();
        if (runtimeConfigService.getRuntimeConfig().getModelRouter() != null
                && runtimeConfigService.getRuntimeConfig().getModelRouter().getTiers() != null) {
            tiers = List.copyOf(runtimeConfigService.getRuntimeConfig().getModelRouter().getTiers().keySet());
        }
        properties.put("tiers", tiers);

        properties.put("feature_telegram_enabled", runtimeConfigService.isTelegramEnabled());
        properties.put("feature_voice_enabled", runtimeConfigService.isVoiceEnabled());
        properties.put("feature_selfevolving_enabled", runtimeConfigService.isSelfEvolvingEnabled());
        properties.put("feature_telemetry_enabled", runtimeConfigService.isTelemetryEnabled());

        telemetryEventPublisher.publishAnonymousEvent("instance_heartbeat", distinctId, properties);
    }

    private void sendHeartbeatSafely() {
        try {
            sendHeartbeatNow();
        } catch (Exception exception) {
            log.warn("Telemetry heartbeat failed: {}", exception.getMessage()); // NOSONAR
        }
    }
}
