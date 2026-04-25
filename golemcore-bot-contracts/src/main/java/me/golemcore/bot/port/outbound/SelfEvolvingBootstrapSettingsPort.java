package me.golemcore.bot.port.outbound;

/**
 * Domain-facing access to startup self-evolving bootstrap overrides.
 */
public interface SelfEvolvingBootstrapSettingsPort {

    SelfEvolvingBootstrapSettings selfEvolvingBootstrap();

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
