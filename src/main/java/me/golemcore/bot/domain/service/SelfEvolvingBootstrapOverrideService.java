package me.golemcore.bot.domain.service;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.springframework.stereotype.Service;

/**
 * Applies startup-only SelfEvolving overrides from {@code bot.*} properties
 * onto the effective runtime config without changing persisted preferences.
 */
@Service
@RequiredArgsConstructor
public class SelfEvolvingBootstrapOverrideService {

    private final BotProperties botProperties;

    public void apply(RuntimeConfig runtimeConfig) {
        if (runtimeConfig == null) {
            return;
        }

        BotProperties.SelfEvolvingBootstrapProperties bootstrap = botProperties.getSelfEvolving().getBootstrap();
        if (bootstrap == null) {
            return;
        }

        RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig = ensureSelfEvolvingConfig(runtimeConfig);
        overrideEnabled(selfEvolvingConfig, bootstrap);
        overrideTactics(selfEvolvingConfig, bootstrap.getTactics());
    }

    public void restorePersistedValues(RuntimeConfig candidateConfig, RuntimeConfig persistedConfig) {
        if (candidateConfig == null) {
            return;
        }

        BotProperties.SelfEvolvingBootstrapProperties bootstrap = botProperties.getSelfEvolving().getBootstrap();
        if (bootstrap == null) {
            return;
        }

        RuntimeConfig.SelfEvolvingConfig candidate = ensureSelfEvolvingConfig(candidateConfig);
        RuntimeConfig.SelfEvolvingConfig persisted = persistedConfig != null
                && persistedConfig.getSelfEvolving() != null
                        ? persistedConfig.getSelfEvolving()
                        : new RuntimeConfig.SelfEvolvingConfig();
        restoreEnabled(candidate, persisted, bootstrap);
        restoreTactics(candidate, persisted, bootstrap.getTactics());
    }

    private void overrideEnabled(RuntimeConfig.SelfEvolvingConfig target,
            BotProperties.SelfEvolvingBootstrapProperties source) {
        if (source.getEnabled() != null) {
            target.setEnabled(source.getEnabled());
        }
        if (source.getTracePayloadOverride() != null) {
            target.setTracePayloadOverride(source.getTracePayloadOverride());
        }
    }

    private void restoreEnabled(RuntimeConfig.SelfEvolvingConfig target,
            RuntimeConfig.SelfEvolvingConfig persisted,
            BotProperties.SelfEvolvingBootstrapProperties source) {
        if (source.getEnabled() != null) {
            target.setEnabled(persisted.getEnabled());
        }
        if (source.getTracePayloadOverride() != null) {
            target.setTracePayloadOverride(persisted.getTracePayloadOverride());
        }
    }

    private void overrideTactics(RuntimeConfig.SelfEvolvingConfig target,
            BotProperties.SelfEvolvingBootstrapTacticsProperties source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticsConfig tacticsConfig = ensureTacticsConfig(target);
        if (source.getEnabled() != null) {
            tacticsConfig.setEnabled(source.getEnabled());
        }
        overrideSearch(tacticsConfig, source.getSearch());
    }

    private void restoreTactics(RuntimeConfig.SelfEvolvingConfig target,
            RuntimeConfig.SelfEvolvingConfig persisted,
            BotProperties.SelfEvolvingBootstrapTacticsProperties source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticsConfig candidateTactics = ensureTacticsConfig(target);
        RuntimeConfig.SelfEvolvingTacticsConfig persistedTactics = persisted.getTactics() != null
                ? persisted.getTactics()
                : new RuntimeConfig.SelfEvolvingTacticsConfig();
        if (source.getEnabled() != null) {
            candidateTactics.setEnabled(persistedTactics.getEnabled());
        }
        restoreSearch(candidateTactics, persistedTactics, source.getSearch());
    }

    private void overrideSearch(RuntimeConfig.SelfEvolvingTacticsConfig target,
            BotProperties.SelfEvolvingBootstrapTacticSearchProperties source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig = ensureSearchConfig(target);
        if (source.getMode() != null && !source.getMode().isBlank()) {
            searchConfig.setMode(source.getMode().trim());
        }
        overrideEmbeddings(searchConfig, source.getEmbeddings());
        overrideRerank(searchConfig, source.getRerank());
        overrideToggle(searchConfig.getPersonalization(), source.getPersonalization());
        overrideToggle(searchConfig.getNegativeMemory(), source.getNegativeMemory());
    }

    private void restoreSearch(RuntimeConfig.SelfEvolvingTacticsConfig target,
            RuntimeConfig.SelfEvolvingTacticsConfig persisted,
            BotProperties.SelfEvolvingBootstrapTacticSearchProperties source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticSearchConfig candidateSearch = ensureSearchConfig(target);
        RuntimeConfig.SelfEvolvingTacticSearchConfig persistedSearch = persisted.getSearch() != null
                ? persisted.getSearch()
                : new RuntimeConfig.SelfEvolvingTacticSearchConfig();
        if (source.getMode() != null) {
            candidateSearch.setMode(persistedSearch.getMode());
        }
        restoreEmbeddings(candidateSearch, persistedSearch, source.getEmbeddings());
        restoreRerank(candidateSearch, persistedSearch, source.getRerank());
        restoreToggle(candidateSearch.getPersonalization(), persistedSearch.getPersonalization(),
                source.getPersonalization());
        restoreToggle(candidateSearch.getNegativeMemory(), persistedSearch.getNegativeMemory(),
                source.getNegativeMemory());
    }

    private void overrideEmbeddings(RuntimeConfig.SelfEvolvingTacticSearchConfig target,
            BotProperties.SelfEvolvingBootstrapTacticEmbeddingsProperties source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddingsConfig = ensureEmbeddingsConfig(target);
        if (source.getEnabled() != null) {
            embeddingsConfig.setEnabled(source.getEnabled());
        }
        if (isNonBlank(source.getProvider())) {
            embeddingsConfig.setProvider(source.getProvider().trim());
        }
        if (isNonBlank(source.getBaseUrl())) {
            embeddingsConfig.setBaseUrl(source.getBaseUrl().trim());
        }
        if (isNonBlank(source.getApiKey())) {
            embeddingsConfig.setApiKey(source.getApiKey().trim());
        }
        if (isNonBlank(source.getModel())) {
            embeddingsConfig.setModel(source.getModel().trim());
        }
        if (source.getDimensions() != null) {
            embeddingsConfig.setDimensions(source.getDimensions());
        }
        if (source.getBatchSize() != null) {
            embeddingsConfig.setBatchSize(source.getBatchSize());
        }
        if (source.getTimeoutMs() != null) {
            embeddingsConfig.setTimeoutMs(source.getTimeoutMs());
        }
        if (source.getAutoFallbackToBm25() != null) {
            embeddingsConfig.setAutoFallbackToBm25(source.getAutoFallbackToBm25());
        }
        overrideEmbeddingsLocal(embeddingsConfig, source.getLocal());
    }

    private void restoreEmbeddings(RuntimeConfig.SelfEvolvingTacticSearchConfig target,
            RuntimeConfig.SelfEvolvingTacticSearchConfig persisted,
            BotProperties.SelfEvolvingBootstrapTacticEmbeddingsProperties source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig candidateEmbeddings = ensureEmbeddingsConfig(target);
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig persistedEmbeddings = persisted.getEmbeddings() != null
                ? persisted.getEmbeddings()
                : new RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig();
        if (source.getEnabled() != null) {
            candidateEmbeddings.setEnabled(persistedEmbeddings.getEnabled());
        }
        if (source.getProvider() != null) {
            candidateEmbeddings.setProvider(persistedEmbeddings.getProvider());
        }
        if (source.getBaseUrl() != null) {
            candidateEmbeddings.setBaseUrl(persistedEmbeddings.getBaseUrl());
        }
        if (source.getApiKey() != null) {
            candidateEmbeddings.setApiKey(persistedEmbeddings.getApiKey());
        }
        if (source.getModel() != null) {
            candidateEmbeddings.setModel(persistedEmbeddings.getModel());
        }
        if (source.getDimensions() != null) {
            candidateEmbeddings.setDimensions(persistedEmbeddings.getDimensions());
        }
        if (source.getBatchSize() != null) {
            candidateEmbeddings.setBatchSize(persistedEmbeddings.getBatchSize());
        }
        if (source.getTimeoutMs() != null) {
            candidateEmbeddings.setTimeoutMs(persistedEmbeddings.getTimeoutMs());
        }
        if (source.getAutoFallbackToBm25() != null) {
            candidateEmbeddings.setAutoFallbackToBm25(persistedEmbeddings.getAutoFallbackToBm25());
        }
        restoreEmbeddingsLocal(candidateEmbeddings, persistedEmbeddings, source.getLocal());
    }

    private void overrideEmbeddingsLocal(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig target,
            BotProperties.SelfEvolvingBootstrapTacticEmbeddingsLocalProperties source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig localConfig = ensureEmbeddingsLocalConfig(target);
        if (source.getAutoInstall() != null) {
            localConfig.setAutoInstall(source.getAutoInstall());
        }
        if (source.getPullOnStart() != null) {
            localConfig.setPullOnStart(source.getPullOnStart());
        }
        if (source.getRequireHealthyRuntime() != null) {
            localConfig.setRequireHealthyRuntime(source.getRequireHealthyRuntime());
        }
        if (source.getFailOpen() != null) {
            localConfig.setFailOpen(source.getFailOpen());
        }
        if (source.getStartupTimeoutMs() != null) {
            localConfig.setStartupTimeoutMs(source.getStartupTimeoutMs());
        }
        if (source.getInitialRestartBackoffMs() != null) {
            localConfig.setInitialRestartBackoffMs(source.getInitialRestartBackoffMs());
        }
        if (isNonBlank(source.getMinimumRuntimeVersion())) {
            localConfig.setMinimumRuntimeVersion(source.getMinimumRuntimeVersion().trim());
        }
    }

    private void restoreEmbeddingsLocal(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig target,
            RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig persisted,
            BotProperties.SelfEvolvingBootstrapTacticEmbeddingsLocalProperties source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig candidateLocal = ensureEmbeddingsLocalConfig(target);
        RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig persistedLocal = persisted.getLocal() != null
                ? persisted.getLocal()
                : new RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig();
        if (source.getAutoInstall() != null) {
            candidateLocal.setAutoInstall(persistedLocal.getAutoInstall());
        }
        if (source.getPullOnStart() != null) {
            candidateLocal.setPullOnStart(persistedLocal.getPullOnStart());
        }
        if (source.getRequireHealthyRuntime() != null) {
            candidateLocal.setRequireHealthyRuntime(persistedLocal.getRequireHealthyRuntime());
        }
        if (source.getFailOpen() != null) {
            candidateLocal.setFailOpen(persistedLocal.getFailOpen());
        }
        if (source.getStartupTimeoutMs() != null) {
            candidateLocal.setStartupTimeoutMs(persistedLocal.getStartupTimeoutMs());
        }
        if (source.getInitialRestartBackoffMs() != null) {
            candidateLocal.setInitialRestartBackoffMs(persistedLocal.getInitialRestartBackoffMs());
        }
        if (source.getMinimumRuntimeVersion() != null) {
            candidateLocal.setMinimumRuntimeVersion(persistedLocal.getMinimumRuntimeVersion());
        }
    }

    private void overrideRerank(RuntimeConfig.SelfEvolvingTacticSearchConfig target,
            BotProperties.SelfEvolvingBootstrapTacticRerankProperties source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticRerankConfig rerankConfig = ensureRerankConfig(target);
        if (source.getCrossEncoder() != null) {
            rerankConfig.setCrossEncoder(source.getCrossEncoder());
        }
        if (isNonBlank(source.getTier())) {
            rerankConfig.setTier(source.getTier().trim());
        }
    }

    private void restoreRerank(RuntimeConfig.SelfEvolvingTacticSearchConfig target,
            RuntimeConfig.SelfEvolvingTacticSearchConfig persisted,
            BotProperties.SelfEvolvingBootstrapTacticRerankProperties source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticRerankConfig candidateRerank = ensureRerankConfig(target);
        RuntimeConfig.SelfEvolvingTacticRerankConfig persistedRerank = persisted.getRerank() != null
                ? persisted.getRerank()
                : new RuntimeConfig.SelfEvolvingTacticRerankConfig();
        if (source.getCrossEncoder() != null) {
            candidateRerank.setCrossEncoder(persistedRerank.getCrossEncoder());
        }
        if (source.getTier() != null) {
            candidateRerank.setTier(persistedRerank.getTier());
        }
    }

    private void overrideToggle(RuntimeConfig.SelfEvolvingToggleConfig target,
            BotProperties.SelfEvolvingBootstrapToggleProperties source) {
        if (target == null || source == null) {
            return;
        }
        if (source.getEnabled() != null) {
            target.setEnabled(source.getEnabled());
        }
    }

    private void restoreToggle(RuntimeConfig.SelfEvolvingToggleConfig target,
            RuntimeConfig.SelfEvolvingToggleConfig persisted,
            BotProperties.SelfEvolvingBootstrapToggleProperties source) {
        if (target == null || source == null) {
            return;
        }
        if (source.getEnabled() != null) {
            target.setEnabled(persisted != null ? persisted.getEnabled() : null);
        }
    }

    private RuntimeConfig.SelfEvolvingConfig ensureSelfEvolvingConfig(RuntimeConfig runtimeConfig) {
        if (runtimeConfig.getSelfEvolving() == null) {
            runtimeConfig.setSelfEvolving(new RuntimeConfig.SelfEvolvingConfig());
        }
        return runtimeConfig.getSelfEvolving();
    }

    private RuntimeConfig.SelfEvolvingTacticsConfig ensureTacticsConfig(RuntimeConfig.SelfEvolvingConfig config) {
        if (config.getTactics() == null) {
            config.setTactics(new RuntimeConfig.SelfEvolvingTacticsConfig());
        }
        return config.getTactics();
    }

    private RuntimeConfig.SelfEvolvingTacticSearchConfig ensureSearchConfig(
            RuntimeConfig.SelfEvolvingTacticsConfig config) {
        if (config.getSearch() == null) {
            config.setSearch(new RuntimeConfig.SelfEvolvingTacticSearchConfig());
        }
        return config.getSearch();
    }

    private RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig ensureEmbeddingsConfig(
            RuntimeConfig.SelfEvolvingTacticSearchConfig config) {
        if (config.getEmbeddings() == null) {
            config.setEmbeddings(new RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig());
        }
        return config.getEmbeddings();
    }

    private RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig ensureEmbeddingsLocalConfig(
            RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig config) {
        if (config.getLocal() == null) {
            config.setLocal(new RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig());
        }
        return config.getLocal();
    }

    private RuntimeConfig.SelfEvolvingTacticRerankConfig ensureRerankConfig(
            RuntimeConfig.SelfEvolvingTacticSearchConfig config) {
        if (config.getRerank() == null) {
            config.setRerank(new RuntimeConfig.SelfEvolvingTacticRerankConfig());
        }
        return config.getRerank();
    }

    private boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
