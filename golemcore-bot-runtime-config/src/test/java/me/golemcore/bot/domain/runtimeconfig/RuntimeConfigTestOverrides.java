package me.golemcore.bot.domain.runtimeconfig;

import java.util.ArrayList;
import java.util.List;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.port.outbound.SelfEvolvingBootstrapOverridePort;

final class RuntimeConfigTestOverrides {

    private RuntimeConfigTestOverrides() {
    }

    static SelfEvolvingBootstrapOverridePort noop() {
        return new MutableSelfEvolvingBootstrapOverridePort();
    }

    static MutableSelfEvolvingBootstrapOverridePort mutable() {
        return new MutableSelfEvolvingBootstrapOverridePort();
    }

    static final class MutableSelfEvolvingBootstrapOverridePort implements SelfEvolvingBootstrapOverridePort {

        private Boolean enabled;
        private Boolean tacticsEnabled;
        private String searchMode;
        private String embeddingsProvider;

        void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        void setTacticsEnabled(Boolean tacticsEnabled) {
            this.tacticsEnabled = tacticsEnabled;
        }

        void setSearchMode(String searchMode) {
            this.searchMode = searchMode;
        }

        void setEmbeddingsProvider(String embeddingsProvider) {
            this.embeddingsProvider = embeddingsProvider;
        }

        @Override
        public void apply(RuntimeConfig runtimeConfig) {
            if (runtimeConfig == null) {
                return;
            }
            RuntimeConfig.SelfEvolvingConfig selfEvolving = runtimeConfig.getSelfEvolving();
            if (selfEvolving == null) {
                selfEvolving = new RuntimeConfig.SelfEvolvingConfig();
                runtimeConfig.setSelfEvolving(selfEvolving);
            }
            if (enabled != null) {
                selfEvolving.setEnabled(enabled);
            }
            selfEvolving.setTracePayloadOverride(true);
            if (tacticsEnabled != null || searchMode != null || embeddingsProvider != null) {
                RuntimeConfig.SelfEvolvingTacticsConfig tactics = selfEvolving.getTactics();
                if (tactics == null) {
                    tactics = new RuntimeConfig.SelfEvolvingTacticsConfig();
                    selfEvolving.setTactics(tactics);
                }
                if (tacticsEnabled != null) {
                    tactics.setEnabled(tacticsEnabled);
                }
                if (searchMode != null || embeddingsProvider != null) {
                    RuntimeConfig.SelfEvolvingTacticSearchConfig search = tactics.getSearch();
                    if (search == null) {
                        search = new RuntimeConfig.SelfEvolvingTacticSearchConfig();
                        tactics.setSearch(search);
                    }
                    if (searchMode != null) {
                        search.setMode(searchMode);
                    }
                    if (embeddingsProvider != null) {
                        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddings = search.getEmbeddings();
                        if (embeddings == null) {
                            embeddings = new RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig();
                            search.setEmbeddings(embeddings);
                        }
                        embeddings.setProvider(embeddingsProvider);
                    }
                }
            }
        }

        @Override
        public void restorePersistedValues(RuntimeConfig candidateConfig, RuntimeConfig persistedConfig) {
            if (candidateConfig == null || candidateConfig.getSelfEvolving() == null) {
                return;
            }
            RuntimeConfig.SelfEvolvingConfig candidate = candidateConfig.getSelfEvolving();
            RuntimeConfig.SelfEvolvingConfig persisted = persistedConfig != null
                    && persistedConfig.getSelfEvolving() != null ? persistedConfig.getSelfEvolving()
                            : new RuntimeConfig.SelfEvolvingConfig();
            if (enabled != null) {
                candidate.setEnabled(persisted.getEnabled());
            }
            candidate.setTracePayloadOverride(persisted.getTracePayloadOverride());
            if (tacticsEnabled != null && candidate.getTactics() != null) {
                RuntimeConfig.SelfEvolvingTacticsConfig persistedTactics = persisted.getTactics() != null
                        ? persisted.getTactics()
                        : new RuntimeConfig.SelfEvolvingTacticsConfig();
                candidate.getTactics().setEnabled(persistedTactics.getEnabled());
            }
        }

        @Override
        public boolean hasManagedOverrides() {
            return !getOverriddenPaths().isEmpty();
        }

        @Override
        public List<String> getOverriddenPaths() {
            List<String> paths = new ArrayList<>();
            if (enabled != null) {
                paths.add("enabled");
            }
            if (tacticsEnabled != null) {
                paths.add("tactics.enabled");
            }
            if (searchMode != null && !searchMode.isBlank()) {
                paths.add("tactics.search.mode");
            }
            if (embeddingsProvider != null && !embeddingsProvider.isBlank()) {
                paths.add("tactics.search.embeddings.provider");
            }
            return paths;
        }
    }
}
