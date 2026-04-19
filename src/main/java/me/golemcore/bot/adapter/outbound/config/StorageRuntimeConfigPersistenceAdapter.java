package me.golemcore.bot.adapter.outbound.config;

import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.port.outbound.RuntimeConfigPersistencePort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class StorageRuntimeConfigPersistenceAdapter implements RuntimeConfigPersistencePort {

    private static final String PREFERENCES_DIR = "preferences";

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    public StorageRuntimeConfigPersistenceAdapter(StoragePort storagePort) {
        this.storagePort = storagePort;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public RuntimeConfig loadOrCreate() {
        boolean hasPersistedRuntimeSection = hasPersistedRuntimeSectionsExcludingTelemetry();
        RuntimeConfig.TelegramConfig telegram = loadSection(RuntimeConfig.ConfigSection.TELEGRAM,
                RuntimeConfig.TelegramConfig.class, RuntimeConfig.TelegramConfig::new, true);
        RuntimeConfig.ModelRouterConfig modelRouter = loadSection(RuntimeConfig.ConfigSection.MODEL_ROUTER,
                RuntimeConfig.ModelRouterConfig.class, RuntimeConfig.ModelRouterConfig::new, true);
        RuntimeConfig.LlmConfig llm = loadSection(RuntimeConfig.ConfigSection.LLM,
                RuntimeConfig.LlmConfig.class, RuntimeConfig.LlmConfig::new, true);
        RuntimeConfig.ToolsConfig tools = loadSection(RuntimeConfig.ConfigSection.TOOLS,
                RuntimeConfig.ToolsConfig.class, RuntimeConfig.ToolsConfig::new, true);
        RuntimeConfig.VoiceConfig voice = loadSection(RuntimeConfig.ConfigSection.VOICE,
                RuntimeConfig.VoiceConfig.class, RuntimeConfig.VoiceConfig::new, true);
        RuntimeConfig.AutoModeConfig autoMode = loadSection(RuntimeConfig.ConfigSection.AUTO_MODE,
                RuntimeConfig.AutoModeConfig.class, RuntimeConfig.AutoModeConfig::new, true);
        RuntimeConfig.UpdateConfig update = loadSection(RuntimeConfig.ConfigSection.UPDATE,
                RuntimeConfig.UpdateConfig.class, RuntimeConfig.UpdateConfig::new, true);
        RuntimeConfig.TracingConfig tracing = loadSection(RuntimeConfig.ConfigSection.TRACING,
                RuntimeConfig.TracingConfig.class, RuntimeConfig.TracingConfig::new, true);
        RuntimeConfig.RateLimitConfig rateLimit = loadSection(RuntimeConfig.ConfigSection.RATE_LIMIT,
                RuntimeConfig.RateLimitConfig.class, RuntimeConfig.RateLimitConfig::new, true);
        RuntimeConfig.SecurityConfig security = loadSection(RuntimeConfig.ConfigSection.SECURITY,
                RuntimeConfig.SecurityConfig.class, RuntimeConfig.SecurityConfig::new, true);
        RuntimeConfig.CompactionConfig compaction = loadSection(RuntimeConfig.ConfigSection.COMPACTION,
                RuntimeConfig.CompactionConfig.class, RuntimeConfig.CompactionConfig::new, true);
        RuntimeConfig.TurnConfig turn = loadSection(RuntimeConfig.ConfigSection.TURN,
                RuntimeConfig.TurnConfig.class, RuntimeConfig.TurnConfig::new, true);
        RuntimeConfig.SessionRetentionConfig sessionRetention = loadSection(
                RuntimeConfig.ConfigSection.SESSION_RETENTION,
                RuntimeConfig.SessionRetentionConfig.class, RuntimeConfig.SessionRetentionConfig::new, true);
        RuntimeConfig.MemoryConfig memory = loadSection(RuntimeConfig.ConfigSection.MEMORY,
                RuntimeConfig.MemoryConfig.class, RuntimeConfig.MemoryConfig::new, true);
        RuntimeConfig.SkillsConfig skills = loadSection(RuntimeConfig.ConfigSection.SKILLS,
                RuntimeConfig.SkillsConfig.class, RuntimeConfig.SkillsConfig::new, true);
        RuntimeConfig.ModelRegistryConfig modelRegistry = loadSection(RuntimeConfig.ConfigSection.MODEL_REGISTRY,
                RuntimeConfig.ModelRegistryConfig.class, RuntimeConfig.ModelRegistryConfig::new, true);
        RuntimeConfig.UsageConfig usage = loadSection(RuntimeConfig.ConfigSection.USAGE,
                RuntimeConfig.UsageConfig.class, RuntimeConfig.UsageConfig::new, true);
        RuntimeConfig.TelemetryConfig telemetry = loadSection(
                RuntimeConfig.ConfigSection.TELEMETRY,
                RuntimeConfig.TelemetryConfig.class,
                () -> RuntimeConfig.TelemetryConfig.builder().enabled(!hasPersistedRuntimeSection).build(),
                true);
        RuntimeConfig.McpConfig mcp = loadSection(RuntimeConfig.ConfigSection.MCP,
                RuntimeConfig.McpConfig.class, RuntimeConfig.McpConfig::new, true);
        RuntimeConfig.PlanConfig plan = loadSection(RuntimeConfig.ConfigSection.PLAN,
                RuntimeConfig.PlanConfig.class, RuntimeConfig.PlanConfig::new, true);
        RuntimeConfig.DelayedActionsConfig delayedActions = loadSection(RuntimeConfig.ConfigSection.DELAYED_ACTIONS,
                RuntimeConfig.DelayedActionsConfig.class, RuntimeConfig.DelayedActionsConfig::new, true);
        RuntimeConfig.HiveConfig hive = loadSection(RuntimeConfig.ConfigSection.HIVE,
                RuntimeConfig.HiveConfig.class, RuntimeConfig.HiveConfig::new, true);
        RuntimeConfig.SelfEvolvingConfig selfEvolving = loadSection(RuntimeConfig.ConfigSection.SELF_EVOLVING,
                RuntimeConfig.SelfEvolvingConfig.class, RuntimeConfig.SelfEvolvingConfig::new, true);
        RuntimeConfig.ResilienceConfig resilience = loadSection(RuntimeConfig.ConfigSection.RESILIENCE,
                RuntimeConfig.ResilienceConfig.class, RuntimeConfig.ResilienceConfig::new, true);

        return RuntimeConfig.builder()
                .telegram(telegram)
                .modelRouter(modelRouter)
                .llm(llm)
                .tools(tools)
                .voice(voice)
                .autoMode(autoMode)
                .update(update)
                .tracing(tracing)
                .rateLimit(rateLimit)
                .security(security)
                .compaction(compaction)
                .turn(turn)
                .sessionRetention(sessionRetention)
                .memory(memory)
                .skills(skills)
                .modelRegistry(modelRegistry)
                .usage(usage)
                .telemetry(telemetry)
                .mcp(mcp)
                .plan(plan)
                .delayedActions(delayedActions)
                .hive(hive)
                .selfEvolving(selfEvolving)
                .resilience(resilience)
                .build();
    }

    @Override
    public void persist(RuntimeConfig runtimeConfig) {
        persistSection(RuntimeConfig.ConfigSection.TELEGRAM, runtimeConfig.getTelegram(),
                RuntimeConfig.TelegramConfig::new);
        persistSection(RuntimeConfig.ConfigSection.MODEL_ROUTER, runtimeConfig.getModelRouter(),
                RuntimeConfig.ModelRouterConfig::new);
        persistSection(RuntimeConfig.ConfigSection.LLM, runtimeConfig.getLlm(),
                RuntimeConfig.LlmConfig::new);
        persistSection(RuntimeConfig.ConfigSection.TOOLS, runtimeConfig.getTools(),
                RuntimeConfig.ToolsConfig::new);
        persistSection(RuntimeConfig.ConfigSection.VOICE, runtimeConfig.getVoice(),
                RuntimeConfig.VoiceConfig::new);
        persistSection(RuntimeConfig.ConfigSection.AUTO_MODE, runtimeConfig.getAutoMode(),
                RuntimeConfig.AutoModeConfig::new);
        persistSection(RuntimeConfig.ConfigSection.UPDATE, runtimeConfig.getUpdate(),
                RuntimeConfig.UpdateConfig::new);
        persistSection(RuntimeConfig.ConfigSection.TRACING, runtimeConfig.getTracing(),
                RuntimeConfig.TracingConfig::new);
        persistSection(RuntimeConfig.ConfigSection.RATE_LIMIT, runtimeConfig.getRateLimit(),
                RuntimeConfig.RateLimitConfig::new);
        persistSection(RuntimeConfig.ConfigSection.SECURITY, runtimeConfig.getSecurity(),
                RuntimeConfig.SecurityConfig::new);
        persistSection(RuntimeConfig.ConfigSection.COMPACTION, runtimeConfig.getCompaction(),
                RuntimeConfig.CompactionConfig::new);
        persistSection(RuntimeConfig.ConfigSection.TURN, runtimeConfig.getTurn(),
                RuntimeConfig.TurnConfig::new);
        persistSection(RuntimeConfig.ConfigSection.SESSION_RETENTION, runtimeConfig.getSessionRetention(),
                RuntimeConfig.SessionRetentionConfig::new);
        persistSection(RuntimeConfig.ConfigSection.MEMORY, runtimeConfig.getMemory(),
                RuntimeConfig.MemoryConfig::new);
        persistSection(RuntimeConfig.ConfigSection.SKILLS, runtimeConfig.getSkills(),
                RuntimeConfig.SkillsConfig::new);
        persistSection(RuntimeConfig.ConfigSection.MODEL_REGISTRY, runtimeConfig.getModelRegistry(),
                RuntimeConfig.ModelRegistryConfig::new);
        persistSection(RuntimeConfig.ConfigSection.USAGE, runtimeConfig.getUsage(),
                RuntimeConfig.UsageConfig::new);
        persistSection(RuntimeConfig.ConfigSection.TELEMETRY, runtimeConfig.getTelemetry(),
                RuntimeConfig.TelemetryConfig::new);
        persistSection(RuntimeConfig.ConfigSection.MCP, runtimeConfig.getMcp(),
                RuntimeConfig.McpConfig::new);
        persistSection(RuntimeConfig.ConfigSection.PLAN, runtimeConfig.getPlan(),
                RuntimeConfig.PlanConfig::new);
        persistSection(RuntimeConfig.ConfigSection.DELAYED_ACTIONS, runtimeConfig.getDelayedActions(),
                RuntimeConfig.DelayedActionsConfig::new);
        persistSection(RuntimeConfig.ConfigSection.HIVE, runtimeConfig.getHive(),
                RuntimeConfig.HiveConfig::new);
        persistSection(RuntimeConfig.ConfigSection.SELF_EVOLVING, runtimeConfig.getSelfEvolving(),
                RuntimeConfig.SelfEvolvingConfig::new);
        persistSection(RuntimeConfig.ConfigSection.RESILIENCE, runtimeConfig.getResilience(),
                RuntimeConfig.ResilienceConfig::new);
    }

    @Override
    public RuntimeConfig copy(RuntimeConfig runtimeConfig) {
        if (runtimeConfig == null) {
            return RuntimeConfig.builder().build();
        }
        try {
            String json = objectMapper.writeValueAsString(runtimeConfig);
            return objectMapper.readValue(json, RuntimeConfig.class);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Failed to copy runtime config", exception);
        }
    }

    @Override
    public <T> T loadSection(
            RuntimeConfig.ConfigSection section,
            Class<T> configClass,
            Supplier<T> defaultSupplier,
            boolean persistDefault) {
        String fileName = section.getFileName();
        try {
            String json = storagePort.getText(PREFERENCES_DIR, fileName).join();
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, configClass);
            }
        } catch (IOException | RuntimeException exception) {
            log.debug("[RuntimeConfig] No saved {} config, using default: {}", section.getFileId(),
                    exception.getMessage());
        }

        T defaultConfig = defaultSupplier.get();
        if (persistDefault) {
            try {
                String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(defaultConfig);
                storagePort.putTextAtomic(PREFERENCES_DIR, fileName, json, true).join();
            } catch (IOException | RuntimeException exception) {
                log.warn("[RuntimeConfig] Failed to persist default {}: {}", section.getFileId(),
                        exception.getMessage());
            }
        }
        return defaultConfig;
    }

    private <T> void persistSection(
            RuntimeConfig.ConfigSection section,
            T config,
            Supplier<T> defaultSupplier) {
        T toSave = config != null ? config : defaultSupplier.get();
        String fileName = section.getFileName();
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(toSave);
            storagePort.putTextAtomic(PREFERENCES_DIR, fileName, json, true).join();
            String persisted = storagePort.getText(PREFERENCES_DIR, fileName).join();
            if (persisted == null || persisted.isBlank()) {
                throw new IllegalStateException("Persisted " + section.getFileId() + " is empty after write");
            }
            Object validated = objectMapper.readValue(persisted, section.getConfigClass());
            if (validated == null) {
                throw new IllegalStateException("Persisted " + section.getFileId() + " failed validation");
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Failed to persist config section: " + section.getFileId(), exception);
        }
    }

    private boolean hasPersistedRuntimeSectionsExcludingTelemetry() {
        for (RuntimeConfig.ConfigSection section : RuntimeConfig.ConfigSection.values()) {
            if (section == RuntimeConfig.ConfigSection.TELEMETRY) {
                continue;
            }
            try {
                String persisted = storagePort.getText(PREFERENCES_DIR, section.getFileName()).join();
                if (persisted != null && !persisted.isBlank()) {
                    return true;
                }
            } catch (RuntimeException exception) {
                log.debug("[RuntimeConfig] Failed to inspect persisted {} config: {}", section.getFileId(),
                        exception.getMessage());
            }
        }
        return false;
    }
}
