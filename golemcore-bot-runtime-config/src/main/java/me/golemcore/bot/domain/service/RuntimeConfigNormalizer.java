package me.golemcore.bot.domain.service;

import java.util.List;
import java.util.Objects;
import me.golemcore.bot.domain.model.RuntimeConfig;

public final class RuntimeConfigNormalizer {

    private final List<RuntimeConfigSectionService> sectionServices;

    public RuntimeConfigNormalizer() {
        this.sectionServices = List.of(
                new TelegramConfigService(),
                new ToolConfigService(),
                new LlmConfigService(),
                new VoiceConfigService(),
                new RateLimitConfigService(),
                new SecurityConfigService(),
                new AutoModeConfigService(),
                new PlanConfigService(),
                new UpdateConfigService(),
                new ObservabilityConfigService(),
                new SessionRuntimeConfigService(),
                new DelayedActionsConfigService(),
                new MemoryConfigService(),
                new ResilienceConfigService(),
                new HiveConfigService(),
                new SelfEvolvingConfigService(),
                new SkillConfigService(),
                new SecretConfigService());
    }

    public void normalize(RuntimeConfig config) {
        if (config == null) {
            return;
        }
        sectionServices.forEach(service -> service.normalize(config));
    }

    boolean hasSectionService(String simpleClassName) {
        Objects.requireNonNull(simpleClassName, "simpleClassName must not be null");
        return sectionServices.stream()
                .map(service -> service.getClass().getSimpleName())
                .anyMatch(simpleClassName::equals);
    }
}
