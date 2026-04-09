package me.golemcore.bot.port.outbound;

import java.time.Duration;
import java.util.Map;

/**
 * Domain-facing access to static application settings.
 */
public interface BotSettingsPort {

    MemorySettings memory();

    SkillSettings skills();

    PromptSettings prompts();

    ToolExecutionSettings toolExecution();

    UpdateSettings update();

    WorkspaceSettings workspace();

    TurnSettings turn();

    ToolLoopSettings toolLoop();

    SelfEvolvingBootstrapSettings selfEvolvingBootstrap();

    static TurnSettings defaultTurnSettings() {
        return new TurnSettings(200, 500, Duration.ofHours(1));
    }

    static ToolLoopSettings defaultToolLoopSettings() {
        return new ToolLoopSettings(false, true, false);
    }

    record MemorySettings(String directory) {
    }

    record SkillSettings(String directory, MarketplaceSettings marketplace) {
        public SkillSettings {
            marketplace = marketplace != null ? marketplace : MarketplaceSettings.disabled();
        }
    }

    record MarketplaceSettings(
            boolean enabled,
            String repositoryDirectory,
            String sandboxPath,
            String repositoryUrl,
            String branch,
            String apiBaseUrl,
            String rawBaseUrl,
            Duration remoteCacheTtl) {

        public static MarketplaceSettings disabled() {
            return new MarketplaceSettings(false, null, null, null, null, null, null, Duration.ofMinutes(5));
        }
    }

    record PromptSettings(boolean enabled, String botName, Map<String, String> customVars) {
        public PromptSettings {
            customVars = customVars != null ? Map.copyOf(customVars) : Map.of();
        }
    }

    record ToolExecutionSettings(int maxToolResultChars) {
    }

    record UpdateSettings(boolean enabled, String updatesPath, int maxKeptVersions, Duration checkInterval) {
        public UpdateSettings {
            checkInterval = checkInterval != null ? checkInterval : Duration.ofHours(1);
        }
    }

    record WorkspaceSettings(String filesystemWorkspace, String shellWorkspace) {
    }

    record TurnSettings(int maxLlmCalls, int maxToolExecutions, Duration deadline) {
        public TurnSettings {
            deadline = deadline != null ? deadline : Duration.ofHours(1);
        }
    }

    record ToolLoopSettings(
            boolean stopOnToolFailure,
            boolean stopOnConfirmationDenied,
            boolean stopOnToolPolicyDenied) {
    }

    record SelfEvolvingBootstrapSettings(Boolean enabled, TacticsSettings tactics) {
        public SelfEvolvingBootstrapSettings {
            tactics = tactics != null ? tactics : new TacticsSettings(null, null);
        }
    }

    record TacticsSettings(Boolean enabled, SearchSettings search) {
        public TacticsSettings {
            search = search != null ? search : new SearchSettings(null, null, null, null);
        }
    }

    record SearchSettings(
            String mode,
            EmbeddingsSettings embeddings,
            ToggleSettings personalization,
            ToggleSettings negativeMemory) {
        public SearchSettings {
            embeddings = embeddings != null ? embeddings : new EmbeddingsSettings(
                    null, null, null, null, null, null, null, null);
            personalization = personalization != null ? personalization : new ToggleSettings(null);
            negativeMemory = negativeMemory != null ? negativeMemory : new ToggleSettings(null);
        }
    }

    record EmbeddingsSettings(
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            Integer dimensions,
            Integer batchSize,
            Integer timeoutMs,
            LocalEmbeddingsSettings local) {
        public EmbeddingsSettings {
            local = local != null ? local : new LocalEmbeddingsSettings(null, null, null, null, null, null, null);
        }
    }

    record LocalEmbeddingsSettings(
            Boolean autoInstall,
            Boolean pullOnStart,
            Boolean requireHealthyRuntime,
            Boolean failOpen,
            Integer startupTimeoutMs,
            Integer initialRestartBackoffMs,
            String minimumRuntimeVersion) {
    }

    record ToggleSettings(Boolean enabled) {
    }
}
