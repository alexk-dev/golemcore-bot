package me.golemcore.bot.infrastructure.config;

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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for loading and managing model configurations from workspace storage.
 *
 * <p>
 * On first run, copies bundled {@code classpath:models.json} to workspace
 * ({@code models/models.json} via StoragePort). Subsequent loads read from
 * workspace, allowing UI edits to persist.
 *
 * @since 1.0
 */
@Service
@Slf4j
public class ModelConfigService {

    private static final String MODELS_DIR = "models";
    private static final String CONFIG_FILE = "models.json";
    private static final String PROVIDER_OPENAI = "openai";

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ModelsConfig config;

    public ModelConfigService(StoragePort storagePort) {
        this.storagePort = storagePort;
    }

    @PostConstruct
    public void init() {
        loadConfig();
    }

    private void loadConfig() {
        try {
            // Try workspace first
            Boolean exists = storagePort.exists(MODELS_DIR, CONFIG_FILE).join();
            if (Boolean.TRUE.equals(exists)) {
                String json = storagePort.getText(MODELS_DIR, CONFIG_FILE).join();
                if (json != null && !json.isBlank()) {
                    config = objectMapper.readValue(json, ModelsConfig.class);
                    log.info("[ModelConfig] Loaded from workspace: {} models", config.getModels().size());
                    return;
                }
            }
        } catch (IOException | RuntimeException e) { // NOSONAR
            log.warn("[ModelConfig] Failed to load from workspace: {}", e.getMessage());
        }

        // Fall back to classpath and copy to workspace
        loadFromClasspathAndCopy();
    }

    private void loadFromClasspathAndCopy() {
        try {
            ClassPathResource resource = new ClassPathResource(CONFIG_FILE);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    config = objectMapper.readValue(json, ModelsConfig.class);
                    log.info("[ModelConfig] Loaded from classpath: {} models", config.getModels().size());
                    // Copy to workspace for future edits
                    saveConfig();
                    return;
                }
            }
        } catch (IOException e) {
            log.warn("[ModelConfig] Failed to load from classpath: {}", e.getMessage());
        }

        log.warn("[ModelConfig] No models.json found, using empty config");
        config = new ModelsConfig();
    }

    /**
     * Reload config from workspace.
     */
    public void reload() {
        loadConfig();
    }

    /**
     * Get the full models config (for API).
     */
    public ModelsConfig getConfig() {
        return config;
    }

    /**
     * Save the full models config to workspace.
     */
    public void saveConfig() {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            storagePort.putText(MODELS_DIR, CONFIG_FILE, json).join();
            log.info("[ModelConfig] Saved to workspace: {} models", config.getModels().size());
        } catch (Exception e) {
            log.error("[ModelConfig] Failed to save: {}", e.getMessage());
        }
    }

    /**
     * Add or update a single model definition.
     */
    public void saveModel(String id, ModelSettings settings) {
        config.getModels().put(id, settings);
        saveConfig();
    }

    /**
     * Delete a model definition.
     */
    public boolean deleteModel(String id) {
        ModelSettings removed = config.getModels().remove(id);
        if (removed != null) {
            saveConfig();
            return true;
        }
        return false;
    }

    /**
     * Replace the entire models config.
     */
    public void replaceConfig(ModelsConfig newConfig) {
        this.config = newConfig;
        saveConfig();
    }

    /**
     * Get settings for a model. Tries exact match first, then prefix match, then
     * defaults.
     */
    public ModelSettings getModelSettings(String modelName) {
        if (modelName == null) {
            return config.getDefaults();
        }

        // Try full name first (e.g., "provider/gpt-oss-120b" as-is)
        if (config.getModels().containsKey(modelName)) {
            return config.getModels().get(modelName);
        }

        // Strip provider prefix (e.g., "qwen/qwen2.5-32b-instruct" →
        // "qwen2.5-32b-instruct")
        String name = modelName.contains("/") ? modelName.substring(modelName.indexOf('/') + 1) : modelName;

        // Exact match on stripped name
        if (config.getModels().containsKey(name)) {
            return config.getModels().get(name);
        }

        // Prefix match (e.g., "gpt-5.1-preview" matches "gpt-5.1")
        // Sort by key length descending so longer (more specific) keys match first
        return config.getModels().entrySet().stream()
                .filter(entry -> name.startsWith(entry.getKey()))
                .max(java.util.Comparator.comparingInt(e -> e.getKey().length()))
                .map(Map.Entry::getValue)
                .orElse(config.getDefaults());
    }

    /**
     * Get all model configurations.
     */
    public Map<String, ModelSettings> getAllModels() {
        return config.getModels();
    }

    /**
     * Get provider for a model.
     */
    public String getProvider(String modelName) {
        return getModelSettings(modelName).getProvider();
    }

    /**
     * Check if model requires reasoning parameter (has reasoning levels
     * configured).
     */
    public boolean isReasoningRequired(String modelName) {
        ModelSettings settings = getModelSettings(modelName);
        return settings.getReasoning() != null
                && settings.getReasoning().getLevels() != null
                && !settings.getReasoning().getLevels().isEmpty();
    }

    /**
     * Check if model supports temperature.
     */
    public boolean supportsTemperature(String modelName) {
        return getModelSettings(modelName).isSupportsTemperature();
    }

    /**
     * Get maximum input tokens for a model. For reasoning models, returns the
     * maximum across all reasoning levels as a safe fallback.
     */
    public int getMaxInputTokens(String modelName) {
        ModelSettings settings = getModelSettings(modelName);
        if (settings.getReasoning() != null && settings.getReasoning().getLevels() != null
                && !settings.getReasoning().getLevels().isEmpty()) {
            return settings.getReasoning().getLevels().values().stream()
                    .mapToInt(ReasoningLevelConfig::getMaxInputTokens)
                    .max()
                    .orElse(settings.getMaxInputTokens());
        }
        return settings.getMaxInputTokens();
    }

    /**
     * Get maximum input tokens for a model at a specific reasoning level. Falls
     * back to model-level maxInputTokens if the level is not found.
     */
    public int getMaxInputTokens(String modelName, String reasoningLevel) {
        if (reasoningLevel == null) {
            return getMaxInputTokens(modelName);
        }
        ModelSettings settings = getModelSettings(modelName);
        if (settings.getReasoning() != null && settings.getReasoning().getLevels() != null) {
            ReasoningLevelConfig levelConfig = settings.getReasoning().getLevels().get(reasoningLevel);
            if (levelConfig != null) {
                return levelConfig.getMaxInputTokens();
            }
        }
        return getMaxInputTokens(modelName);
    }

    /**
     * Get the default reasoning level for a model.
     */
    public String getDefaultReasoningLevel(String modelName) {
        ModelSettings settings = getModelSettings(modelName);
        if (settings.getReasoning() != null) {
            return settings.getReasoning().getDefaultLevel();
        }
        return null;
    }

    /**
     * Get available reasoning levels for a model.
     */
    public List<String> getAvailableReasoningLevels(String modelName) {
        ModelSettings settings = getModelSettings(modelName);
        if (settings.getReasoning() != null && settings.getReasoning().getLevels() != null) {
            return List.copyOf(settings.getReasoning().getLevels().keySet());
        }
        return Collections.emptyList();
    }

    /**
     * Get models filtered by provider names.
     */
    public Map<String, ModelSettings> getModelsForProviders(List<String> providers) {
        return config.getModels().entrySet().stream()
                .filter(entry -> providers.contains(entry.getValue().getProvider()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelsConfig {
        private Map<String, ModelSettings> models = new HashMap<>();
        private ModelSettings defaults = new ModelSettings();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelSettings {
        private String provider = PROVIDER_OPENAI;
        private String displayName;
        private boolean supportsTemperature = true;
        /**
         * Maximum input tokens the model accepts. Used for non-reasoning models. For
         * reasoning models, per-level limits are in {@link ReasoningConfig}.
         */
        private int maxInputTokens = 128000;
        /** Reasoning configuration. Null for non-reasoning models. */
        private ReasoningConfig reasoning;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReasoningConfig {
        @JsonProperty("default")
        private String defaultLevel;
        private Map<String, ReasoningLevelConfig> levels = new LinkedHashMap<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReasoningLevelConfig {
        private int maxInputTokens = 128000;
    }
}
