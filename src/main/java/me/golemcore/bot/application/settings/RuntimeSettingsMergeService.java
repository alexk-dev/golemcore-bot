package me.golemcore.bot.application.settings;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.UserPreferences;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public class RuntimeSettingsMergeService {

    public RuntimeConfig mergeRuntimeConfigSections(RuntimeConfig current, RuntimeConfig incoming) {
        RuntimeConfig baseline = current != null ? current : RuntimeConfig.builder().build();
        RuntimeConfig patch = incoming != null ? incoming : RuntimeConfig.builder().build();
        return RuntimeConfig.builder()
                .telegram(mergeSection(patch.getTelegram(), baseline.getTelegram(), RuntimeConfig.TelegramConfig::new))
                .modelRouter(mergeSection(patch.getModelRouter(), baseline.getModelRouter(),
                        RuntimeConfig.ModelRouterConfig::new))
                .llm(mergeSection(patch.getLlm(), baseline.getLlm(), RuntimeConfig.LlmConfig::new))
                .tools(mergeSection(patch.getTools(), baseline.getTools(), RuntimeConfig.ToolsConfig::new))
                .voice(mergeSection(patch.getVoice(), baseline.getVoice(), RuntimeConfig.VoiceConfig::new))
                .autoMode(mergeSection(patch.getAutoMode(), baseline.getAutoMode(), RuntimeConfig.AutoModeConfig::new))
                .tracing(mergeSection(patch.getTracing(), baseline.getTracing(), RuntimeConfig.TracingConfig::new))
                .rateLimit(mergeSection(patch.getRateLimit(), baseline.getRateLimit(),
                        RuntimeConfig.RateLimitConfig::new))
                .security(mergeSection(patch.getSecurity(), baseline.getSecurity(), RuntimeConfig.SecurityConfig::new))
                .compaction(mergeSection(patch.getCompaction(), baseline.getCompaction(),
                        RuntimeConfig.CompactionConfig::new))
                .turn(mergeSection(patch.getTurn(), baseline.getTurn(), RuntimeConfig.TurnConfig::new))
                .sessionRetention(mergeSection(patch.getSessionRetention(), baseline.getSessionRetention(),
                        RuntimeConfig.SessionRetentionConfig::new))
                .memory(mergeSection(patch.getMemory(), baseline.getMemory(), RuntimeConfig.MemoryConfig::new))
                .skills(mergeSection(patch.getSkills(), baseline.getSkills(), RuntimeConfig.SkillsConfig::new))
                .modelRegistry(mergeSection(patch.getModelRegistry(), baseline.getModelRegistry(),
                        RuntimeConfig.ModelRegistryConfig::new))
                .usage(mergeSection(patch.getUsage(), baseline.getUsage(), RuntimeConfig.UsageConfig::new))
                .telemetry(mergeSection(patch.getTelemetry(), baseline.getTelemetry(),
                        RuntimeConfig.TelemetryConfig::new))
                .mcp(mergeSection(patch.getMcp(), baseline.getMcp(), RuntimeConfig.McpConfig::new))
                .plan(mergeSection(patch.getPlan(), baseline.getPlan(), RuntimeConfig.PlanConfig::new))
                .delayedActions(mergeSection(patch.getDelayedActions(), baseline.getDelayedActions(),
                        RuntimeConfig.DelayedActionsConfig::new))
                .hive(mergeSection(patch.getHive(), baseline.getHive(), RuntimeConfig.HiveConfig::new))
                .selfEvolving(mergeSelfEvolvingSection(patch.getSelfEvolving(), baseline.getSelfEvolving()))
                .build();
    }

    public void mergeRuntimeSecrets(RuntimeConfig current, RuntimeConfig incoming) {
        if (current == null || incoming == null) {
            return;
        }
        if (incoming.getTelegram() != null && current.getTelegram() != null) {
            incoming.getTelegram()
                    .setToken(mergeSecret(current.getTelegram().getToken(), incoming.getTelegram().getToken()));
        }
        if (incoming.getVoice() != null && current.getVoice() != null) {
            incoming.getVoice().setApiKey(mergeSecret(current.getVoice().getApiKey(), incoming.getVoice().getApiKey()));
            incoming.getVoice().setWhisperSttApiKey(
                    mergeSecret(current.getVoice().getWhisperSttApiKey(), incoming.getVoice().getWhisperSttApiKey()));
        }
        mergeLlmSecrets(current.getLlm(), incoming.getLlm());
    }

    public void mergeLlmSecrets(RuntimeConfig.LlmConfig current, RuntimeConfig.LlmConfig incoming) {
        if (current == null || incoming == null || incoming.getProviders() == null) {
            return;
        }
        Map<String, RuntimeConfig.LlmProviderConfig> currentProviders = current.getProviders();
        for (Map.Entry<String, RuntimeConfig.LlmProviderConfig> entry : incoming.getProviders().entrySet()) {
            RuntimeConfig.LlmProviderConfig incomingProvider = entry.getValue();
            RuntimeConfig.LlmProviderConfig currentProvider = currentProviders != null
                    ? currentProviders.get(entry.getKey())
                    : null;
            if (incomingProvider != null && currentProvider != null) {
                incomingProvider.setApiKey(mergeSecret(currentProvider.getApiKey(), incomingProvider.getApiKey()));
            }
        }
    }

    public void mergeWebhookSecrets(UserPreferences.WebhookConfig current, UserPreferences.WebhookConfig incoming) {
        if (incoming == null) {
            return;
        }
        if (current != null) {
            incoming.setToken(mergeSecret(current.getToken(), incoming.getToken()));
        }
        if (incoming.getMappings() == null || incoming.getMappings().isEmpty() || current == null
                || current.getMappings() == null) {
            return;
        }

        Map<String, UserPreferences.HookMapping> currentByName = new LinkedHashMap<>();
        for (UserPreferences.HookMapping mapping : current.getMappings()) {
            if (mapping != null && mapping.getName() != null) {
                currentByName.put(mapping.getName(), mapping);
            }
        }

        for (UserPreferences.HookMapping mapping : incoming.getMappings()) {
            if (mapping == null || mapping.getName() == null) {
                continue;
            }
            UserPreferences.HookMapping existing = currentByName.get(mapping.getName());
            if (existing != null) {
                mapping.setHmacSecret(mergeSecret(existing.getHmacSecret(), mapping.getHmacSecret()));
            }
        }
    }

    public Secret mergeSecret(Secret current, Secret incoming) {
        if (incoming == null) {
            return current;
        }
        if (!Secret.hasValue(incoming)) {
            Secret retained = current != null ? current : Secret.builder().build();
            if (incoming.getEncrypted() != null) {
                retained.setEncrypted(incoming.getEncrypted());
            }
            retained.setPresent(Secret.hasValue(retained));
            return retained;
        }
        return Secret.builder()
                .value(incoming.getValue())
                .encrypted(Boolean.TRUE.equals(incoming.getEncrypted()))
                .present(true)
                .build();
    }

    private RuntimeConfig.SelfEvolvingConfig mergeSelfEvolvingSection(
            RuntimeConfig.SelfEvolvingConfig patch,
            RuntimeConfig.SelfEvolvingConfig baseline) {
        if (patch == null) {
            return baseline;
        }
        preserveSelfEvolvingEmbeddingsApiKey(patch, baseline);
        return patch;
    }

    private void preserveSelfEvolvingEmbeddingsApiKey(
            RuntimeConfig.SelfEvolvingConfig patch,
            RuntimeConfig.SelfEvolvingConfig baseline) {
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig patchEmbeddings = extractEmbeddings(patch);
        if (patchEmbeddings == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig baselineEmbeddings = extractEmbeddings(baseline);
        Secret baselineKey = baselineEmbeddings != null ? baselineEmbeddings.getApiKey() : null;
        patchEmbeddings.setApiKey(mergeSecret(baselineKey, patchEmbeddings.getApiKey()));
    }

    private RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig extractEmbeddings(
            RuntimeConfig.SelfEvolvingConfig config) {
        if (config == null || config.getTactics() == null || config.getTactics().getSearch() == null) {
            return null;
        }
        return config.getTactics().getSearch().getEmbeddings();
    }

    private <T> T mergeSection(T incoming, T current, Supplier<T> emptySupplier) {
        if (incoming == null) {
            return current;
        }
        if (current == null) {
            return incoming;
        }
        return incoming.equals(emptySupplier.get()) ? current : incoming;
    }
}
