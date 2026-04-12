package me.golemcore.bot.domain.selfevolving;

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
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.port.outbound.SelfEvolvingBootstrapSettingsPort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies startup-only SelfEvolving overrides from {@code bot.*} properties
 * onto the effective runtime config without changing persisted preferences.
 */
@Service
@RequiredArgsConstructor
public class SelfEvolvingBootstrapOverrideService {

    private static final String PATH_ENABLED = "enabled";
    private static final String PATH_TACTICS_ENABLED = "tactics.enabled";
    private static final String PATH_TACTICS_SEARCH_MODE = "tactics.search.mode";
    private static final String PATH_TACTICS_SEARCH_EMBEDDINGS_PROVIDER = "tactics.search.embeddings.provider";
    private static final String PATH_TACTICS_SEARCH_EMBEDDINGS_BASE_URL = "tactics.search.embeddings.baseUrl";
    private static final String PATH_TACTICS_SEARCH_EMBEDDINGS_API_KEY = "tactics.search.embeddings.apiKey";
    private static final String PATH_TACTICS_SEARCH_EMBEDDINGS_MODEL = "tactics.search.embeddings.model";
    private static final String PATH_TACTICS_SEARCH_EMBEDDINGS_DIMENSIONS = "tactics.search.embeddings.dimensions";
    private static final String PATH_TACTICS_SEARCH_EMBEDDINGS_BATCH_SIZE = "tactics.search.embeddings.batchSize";
    private static final String PATH_TACTICS_SEARCH_EMBEDDINGS_TIMEOUT_MS = "tactics.search.embeddings.timeoutMs";
    private static final String PATH_TACTICS_SEARCH_EMBEDDINGS_LOCAL_AUTO_INSTALL = "tactics.search.embeddings.local.autoInstall";
    private static final String PATH_TACTICS_SEARCH_EMBEDDINGS_LOCAL_PULL_ON_START = "tactics.search.embeddings.local.pullOnStart";
    private static final String PATH_TACTICS_SEARCH_EMBEDDINGS_LOCAL_REQUIRE_HEALTHY_RUNTIME = "tactics.search.embeddings.local.requireHealthyRuntime";
    private static final String PATH_TACTICS_SEARCH_EMBEDDINGS_LOCAL_FAIL_OPEN = "tactics.search.embeddings.local.failOpen";
    private static final String PATH_TACTICS_SEARCH_EMBEDDINGS_LOCAL_INITIAL_RESTART_BACKOFF_MS = "tactics.search.embeddings.local.initialRestartBackoffMs";
    private static final String PATH_TACTICS_SEARCH_EMBEDDINGS_LOCAL_MINIMUM_RUNTIME_VERSION = "tactics.search.embeddings.local.minimumRuntimeVersion";
    private static final String PATH_TACTICS_SEARCH_PERSONALIZATION_ENABLED = "tactics.search.personalization.enabled";
    private static final String PATH_TACTICS_SEARCH_NEGATIVE_MEMORY_ENABLED = "tactics.search.negativeMemory.enabled";

    private final SelfEvolvingBootstrapSettingsPort settingsPort;

    public void apply(RuntimeConfig runtimeConfig) {
        if (runtimeConfig == null) {
            return;
        }

        RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig = ensureSelfEvolvingConfig(runtimeConfig);
        SelfEvolvingBootstrapSettingsPort.SelfEvolvingBootstrapSettings bootstrap = settingsPort
                .selfEvolvingBootstrap();
        overrideEnabled(selfEvolvingConfig, bootstrap);
        overrideTactics(selfEvolvingConfig, bootstrap.tactics());
    }

    public void restorePersistedValues(RuntimeConfig candidateConfig, RuntimeConfig persistedConfig) {
        if (candidateConfig == null) {
            return;
        }

        RuntimeConfig.SelfEvolvingConfig candidate = ensureSelfEvolvingConfig(candidateConfig);
        SelfEvolvingBootstrapSettingsPort.SelfEvolvingBootstrapSettings bootstrap = settingsPort
                .selfEvolvingBootstrap();
        RuntimeConfig.SelfEvolvingConfig persisted = persistedConfig != null
                && persistedConfig.getSelfEvolving() != null
                        ? persistedConfig.getSelfEvolving()
                        : new RuntimeConfig.SelfEvolvingConfig();
        restoreEnabled(candidate, persisted, bootstrap);
        restoreTactics(candidate, persisted, bootstrap.tactics());
    }

    public boolean hasManagedOverrides() {
        return !getOverriddenPaths().isEmpty();
    }

    public List<String> getOverriddenPaths() {
        List<String> overriddenPaths = new ArrayList<>();
        SelfEvolvingBootstrapSettingsPort.SelfEvolvingBootstrapSettings bootstrap = settingsPort
                .selfEvolvingBootstrap();

        addOverride(overriddenPaths, bootstrap.enabled() != null, PATH_ENABLED);
        SelfEvolvingBootstrapSettingsPort.TacticsSettings tactics = bootstrap.tactics();
        if (tactics == null) {
            return overriddenPaths;
        }
        addOverride(overriddenPaths, tactics.enabled() != null, PATH_TACTICS_ENABLED);

        SelfEvolvingBootstrapSettingsPort.SearchSettings search = tactics.search();
        if (search == null) {
            return overriddenPaths;
        }

        addOverride(overriddenPaths, isNonBlank(search.mode()), PATH_TACTICS_SEARCH_MODE);
        describeEmbeddingsOverrides(overriddenPaths, search.embeddings());
        describeToggleOverride(overriddenPaths, search.personalization(),
                PATH_TACTICS_SEARCH_PERSONALIZATION_ENABLED);
        describeToggleOverride(overriddenPaths, search.negativeMemory(),
                PATH_TACTICS_SEARCH_NEGATIVE_MEMORY_ENABLED);
        return overriddenPaths;
    }

    private void overrideEnabled(RuntimeConfig.SelfEvolvingConfig target,
            SelfEvolvingBootstrapSettingsPort.SelfEvolvingBootstrapSettings source) {
        if (source.enabled() != null) {
            target.setEnabled(source.enabled());
        }
        target.setTracePayloadOverride(true);
    }

    private void restoreEnabled(RuntimeConfig.SelfEvolvingConfig target,
            RuntimeConfig.SelfEvolvingConfig persisted,
            SelfEvolvingBootstrapSettingsPort.SelfEvolvingBootstrapSettings source) {
        if (source.enabled() != null) {
            target.setEnabled(persisted.getEnabled());
        }
        target.setTracePayloadOverride(persisted.getTracePayloadOverride());
    }

    private void overrideTactics(RuntimeConfig.SelfEvolvingConfig target,
            SelfEvolvingBootstrapSettingsPort.TacticsSettings source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticsConfig tacticsConfig = ensureTacticsConfig(target);
        if (source.enabled() != null) {
            tacticsConfig.setEnabled(source.enabled());
        }
        overrideSearch(tacticsConfig, source.search());
    }

    private void restoreTactics(RuntimeConfig.SelfEvolvingConfig target,
            RuntimeConfig.SelfEvolvingConfig persisted,
            SelfEvolvingBootstrapSettingsPort.TacticsSettings source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticsConfig candidateTactics = ensureTacticsConfig(target);
        RuntimeConfig.SelfEvolvingTacticsConfig persistedTactics = persisted.getTactics() != null
                ? persisted.getTactics()
                : new RuntimeConfig.SelfEvolvingTacticsConfig();
        if (source.enabled() != null) {
            candidateTactics.setEnabled(persistedTactics.getEnabled());
        }
        restoreSearch(candidateTactics, persistedTactics, source.search());
    }

    private void describeEmbeddingsOverrides(List<String> overriddenPaths,
            SelfEvolvingBootstrapSettingsPort.EmbeddingsSettings embeddings) {
        if (embeddings == null) {
            return;
        }
        addOverride(overriddenPaths, isNonBlank(embeddings.provider()), PATH_TACTICS_SEARCH_EMBEDDINGS_PROVIDER);
        addOverride(overriddenPaths, isNonBlank(embeddings.baseUrl()), PATH_TACTICS_SEARCH_EMBEDDINGS_BASE_URL);
        addOverride(overriddenPaths, isNonBlank(embeddings.apiKey()), PATH_TACTICS_SEARCH_EMBEDDINGS_API_KEY);
        addOverride(overriddenPaths, isNonBlank(embeddings.model()), PATH_TACTICS_SEARCH_EMBEDDINGS_MODEL);
        addOverride(overriddenPaths, embeddings.dimensions() != null, PATH_TACTICS_SEARCH_EMBEDDINGS_DIMENSIONS);
        addOverride(overriddenPaths, embeddings.batchSize() != null, PATH_TACTICS_SEARCH_EMBEDDINGS_BATCH_SIZE);
        addOverride(overriddenPaths, embeddings.timeoutMs() != null, PATH_TACTICS_SEARCH_EMBEDDINGS_TIMEOUT_MS);
        SelfEvolvingBootstrapSettingsPort.LocalEmbeddingsSettings local = embeddings.local();
        if (local == null) {
            return;
        }
        addOverride(overriddenPaths, local.autoInstall() != null,
                PATH_TACTICS_SEARCH_EMBEDDINGS_LOCAL_AUTO_INSTALL);
        addOverride(overriddenPaths, local.pullOnStart() != null,
                PATH_TACTICS_SEARCH_EMBEDDINGS_LOCAL_PULL_ON_START);
        addOverride(overriddenPaths, local.requireHealthyRuntime() != null,
                PATH_TACTICS_SEARCH_EMBEDDINGS_LOCAL_REQUIRE_HEALTHY_RUNTIME);
        addOverride(overriddenPaths, local.failOpen() != null,
                PATH_TACTICS_SEARCH_EMBEDDINGS_LOCAL_FAIL_OPEN);
        addOverride(overriddenPaths, local.initialRestartBackoffMs() != null,
                PATH_TACTICS_SEARCH_EMBEDDINGS_LOCAL_INITIAL_RESTART_BACKOFF_MS);
        addOverride(overriddenPaths, isNonBlank(local.minimumRuntimeVersion()),
                PATH_TACTICS_SEARCH_EMBEDDINGS_LOCAL_MINIMUM_RUNTIME_VERSION);
    }

    private void describeToggleOverride(List<String> overriddenPaths,
            SelfEvolvingBootstrapSettingsPort.ToggleSettings toggle,
            String path) {
        addOverride(overriddenPaths, toggle != null && toggle.enabled() != null, path);
    }

    private void addOverride(List<String> overriddenPaths, boolean overridden, String path) {
        if (overridden) {
            overriddenPaths.add(path);
        }
    }

    private void overrideSearch(RuntimeConfig.SelfEvolvingTacticsConfig target,
            SelfEvolvingBootstrapSettingsPort.SearchSettings source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig = ensureSearchConfig(target);
        if (source.mode() != null && !source.mode().isBlank()) {
            searchConfig.setMode(source.mode().trim());
        }
        overrideEmbeddings(searchConfig, source.embeddings());
        ensureEmbeddingsConfig(searchConfig).setEnabled("hybrid".equalsIgnoreCase(searchConfig.getMode()));
        overrideToggle(searchConfig.getPersonalization(), source.personalization());
        overrideToggle(searchConfig.getNegativeMemory(), source.negativeMemory());
    }

    private void restoreSearch(RuntimeConfig.SelfEvolvingTacticsConfig target,
            RuntimeConfig.SelfEvolvingTacticsConfig persisted,
            SelfEvolvingBootstrapSettingsPort.SearchSettings source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticSearchConfig candidateSearch = ensureSearchConfig(target);
        RuntimeConfig.SelfEvolvingTacticSearchConfig persistedSearch = persisted.getSearch() != null
                ? persisted.getSearch()
                : new RuntimeConfig.SelfEvolvingTacticSearchConfig();
        if (source.mode() != null) {
            candidateSearch.setMode(persistedSearch.getMode());
        }
        restoreEmbeddings(candidateSearch, persistedSearch, source.embeddings());
        ensureEmbeddingsConfig(candidateSearch).setEnabled("hybrid".equalsIgnoreCase(candidateSearch.getMode()));
        restoreToggle(candidateSearch.getPersonalization(), persistedSearch.getPersonalization(),
                source.personalization());
        restoreToggle(candidateSearch.getNegativeMemory(), persistedSearch.getNegativeMemory(),
                source.negativeMemory());
    }

    private void overrideEmbeddings(RuntimeConfig.SelfEvolvingTacticSearchConfig target,
            SelfEvolvingBootstrapSettingsPort.EmbeddingsSettings source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddingsConfig = ensureEmbeddingsConfig(target);
        if (isNonBlank(source.provider())) {
            embeddingsConfig.setProvider(source.provider().trim());
        }
        if (isNonBlank(source.baseUrl())) {
            embeddingsConfig.setBaseUrl(source.baseUrl().trim());
        }
        if (isNonBlank(source.apiKey())) {
            embeddingsConfig.setApiKey(Secret.of(source.apiKey().trim()));
        }
        if (isNonBlank(source.model())) {
            embeddingsConfig.setModel(source.model().trim());
        }
        if (source.dimensions() != null) {
            embeddingsConfig.setDimensions(source.dimensions());
        }
        if (source.batchSize() != null) {
            embeddingsConfig.setBatchSize(source.batchSize());
        }
        if (source.timeoutMs() != null) {
            embeddingsConfig.setTimeoutMs(source.timeoutMs());
        }
        embeddingsConfig.setAutoFallbackToBm25(true);
        overrideEmbeddingsLocal(embeddingsConfig, source.local());
    }

    private void restoreEmbeddings(RuntimeConfig.SelfEvolvingTacticSearchConfig target,
            RuntimeConfig.SelfEvolvingTacticSearchConfig persisted,
            SelfEvolvingBootstrapSettingsPort.EmbeddingsSettings source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig candidateEmbeddings = ensureEmbeddingsConfig(target);
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig persistedEmbeddings = persisted.getEmbeddings() != null
                ? persisted.getEmbeddings()
                : new RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig();
        if (source.provider() != null) {
            candidateEmbeddings.setProvider(persistedEmbeddings.getProvider());
        }
        if (source.baseUrl() != null) {
            candidateEmbeddings.setBaseUrl(persistedEmbeddings.getBaseUrl());
        }
        if (source.apiKey() != null) {
            candidateEmbeddings.setApiKey(persistedEmbeddings.getApiKey());
        }
        if (source.model() != null) {
            candidateEmbeddings.setModel(persistedEmbeddings.getModel());
        }
        if (source.dimensions() != null) {
            candidateEmbeddings.setDimensions(persistedEmbeddings.getDimensions());
        }
        if (source.batchSize() != null) {
            candidateEmbeddings.setBatchSize(persistedEmbeddings.getBatchSize());
        }
        if (source.timeoutMs() != null) {
            candidateEmbeddings.setTimeoutMs(persistedEmbeddings.getTimeoutMs());
        }
        candidateEmbeddings.setAutoFallbackToBm25(persistedEmbeddings.getAutoFallbackToBm25());
        restoreEmbeddingsLocal(candidateEmbeddings, persistedEmbeddings, source.local());
    }

    private void overrideEmbeddingsLocal(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig target,
            SelfEvolvingBootstrapSettingsPort.LocalEmbeddingsSettings source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig localConfig = ensureEmbeddingsLocalConfig(target);
        if (source.autoInstall() != null) {
            localConfig.setAutoInstall(source.autoInstall());
        }
        if (source.pullOnStart() != null) {
            localConfig.setPullOnStart(source.pullOnStart());
        }
        if (source.requireHealthyRuntime() != null) {
            localConfig.setRequireHealthyRuntime(source.requireHealthyRuntime());
        }
        if (source.failOpen() != null) {
            localConfig.setFailOpen(source.failOpen());
        }
        if (source.startupTimeoutMs() != null) {
            localConfig.setStartupTimeoutMs(source.startupTimeoutMs());
        }
        if (source.initialRestartBackoffMs() != null) {
            localConfig.setInitialRestartBackoffMs(source.initialRestartBackoffMs());
        }
        if (isNonBlank(source.minimumRuntimeVersion())) {
            localConfig.setMinimumRuntimeVersion(source.minimumRuntimeVersion().trim());
        }
    }

    private void restoreEmbeddingsLocal(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig target,
            RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig persisted,
            SelfEvolvingBootstrapSettingsPort.LocalEmbeddingsSettings source) {
        if (source == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig candidateLocal = ensureEmbeddingsLocalConfig(target);
        RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig persistedLocal = persisted.getLocal() != null
                ? persisted.getLocal()
                : new RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig();
        if (source.autoInstall() != null) {
            candidateLocal.setAutoInstall(persistedLocal.getAutoInstall());
        }
        if (source.pullOnStart() != null) {
            candidateLocal.setPullOnStart(persistedLocal.getPullOnStart());
        }
        if (source.requireHealthyRuntime() != null) {
            candidateLocal.setRequireHealthyRuntime(persistedLocal.getRequireHealthyRuntime());
        }
        if (source.failOpen() != null) {
            candidateLocal.setFailOpen(persistedLocal.getFailOpen());
        }
        if (source.startupTimeoutMs() != null) {
            candidateLocal.setStartupTimeoutMs(persistedLocal.getStartupTimeoutMs());
        }
        if (source.initialRestartBackoffMs() != null) {
            candidateLocal.setInitialRestartBackoffMs(persistedLocal.getInitialRestartBackoffMs());
        }
        if (source.minimumRuntimeVersion() != null) {
            candidateLocal.setMinimumRuntimeVersion(persistedLocal.getMinimumRuntimeVersion());
        }
    }

    private void overrideToggle(RuntimeConfig.SelfEvolvingToggleConfig target,
            SelfEvolvingBootstrapSettingsPort.ToggleSettings source) {
        if (target == null || source == null) {
            return;
        }
        if (source.enabled() != null) {
            target.setEnabled(source.enabled());
        }
    }

    private void restoreToggle(RuntimeConfig.SelfEvolvingToggleConfig target,
            RuntimeConfig.SelfEvolvingToggleConfig persisted,
            SelfEvolvingBootstrapSettingsPort.ToggleSettings source) {
        if (target == null || source == null) {
            return;
        }
        if (source.enabled() != null) {
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

    private boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
