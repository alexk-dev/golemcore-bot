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
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for loading and managing model configurations from external JSON
 * file.
 *
 * <p>
 * This service loads model metadata from {@code models.json} in the working
 * directory. Model configurations include:
 * <ul>
 * <li>Provider - OpenAI, Anthropic, etc.</li>
 * <li>Reasoning support - whether model supports reasoning effort
 * parameter</li>
 * <li>Temperature support - whether model supports temperature parameter</li>
 * <li>Context limits - maximum input tokens</li>
 * </ul>
 *
 * <p>
 * If {@code models.json} is not found, the service creates a default
 * configuration with common models (GPT-4o, Claude Sonnet, etc.) and writes it
 * to disk for user reference.
 *
 * <p>
 * Model config is loaded once at startup via {@code @PostConstruct}.
 *
 * @since 1.0
 */
@Service
@Slf4j
public class ModelConfigService {

    private static final String CONFIG_FILE = "models.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ModelsConfig config;

    @PostConstruct
    public void init() {
        loadConfig();
    }

    private void loadConfig() {
        Path configPath = Paths.get(CONFIG_FILE);

        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                config = objectMapper.readValue(json, ModelsConfig.class);
                log.info("Loaded model config from {}: {} models", configPath.toAbsolutePath(),
                        config.getModels().size());
            } catch (IOException e) {
                log.error("Failed to load models.json: {}", e.getMessage());
                config = createDefaultConfig();
            }
        } else {
            log.warn("models.json not found in working directory, using defaults");
            config = createDefaultConfig();
            // Write default config for user reference
            writeDefaultConfig(configPath);
        }
    }

    private void writeDefaultConfig(Path path) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            Files.writeString(path, json);
            log.info("Created default models.json at {}", path.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Failed to write default models.json: {}", e.getMessage());
        }
    }

    private ModelsConfig createDefaultConfig() {
        ModelsConfig cfg = new ModelsConfig();

        // OpenAI reasoning models
        cfg.getModels().put("gpt-5.1", new ModelSettings("openai", true, false));
        cfg.getModels().put("gpt-5.2", new ModelSettings("openai", true, false));
        cfg.getModels().put("o1", new ModelSettings("openai", true, false));
        cfg.getModels().put("o3", new ModelSettings("openai", true, false));
        cfg.getModels().put("o3-mini", new ModelSettings("openai", true, false));

        // OpenAI standard models
        cfg.getModels().put("gpt-4o", new ModelSettings("openai", false, true));
        cfg.getModels().put("gpt-4-turbo", new ModelSettings("openai", false, true));

        // Anthropic models
        cfg.getModels().put("claude-sonnet-4-20250514", new ModelSettings("anthropic", false, true));
        cfg.getModels().put("claude-3-5-sonnet", new ModelSettings("anthropic", false, true));
        cfg.getModels().put("claude-3-opus", new ModelSettings("anthropic", false, true));
        cfg.getModels().put("claude-3-haiku", new ModelSettings("anthropic", false, true));

        // Defaults
        cfg.setDefaults(new ModelSettings("openai", false, true));

        return cfg;
    }

    /**
     * Reload config from file.
     */
    public void reload() {
        loadConfig();
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
     * Check if model requires reasoning parameter.
     */
    public boolean isReasoningRequired(String modelName) {
        return getModelSettings(modelName).isReasoningRequired();
    }

    /**
     * Check if model supports temperature.
     */
    public boolean supportsTemperature(String modelName) {
        return getModelSettings(modelName).isSupportsTemperature();
    }

    /**
     * Get maximum input tokens for a model.
     */
    public int getMaxInputTokens(String modelName) {
        return getModelSettings(modelName).getMaxInputTokens();
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
        private String provider = "openai";
        private boolean reasoningRequired = false;
        private boolean supportsTemperature = true;
        /**
         * Maximum input tokens the model accepts. Used for auto-compaction and
         * emergency truncation.
         */
        private int maxInputTokens = 128000;

        public ModelSettings() {
        }

        public ModelSettings(String provider, boolean reasoningRequired, boolean supportsTemperature) {
            this.provider = provider;
            this.reasoningRequired = reasoningRequired;
            this.supportsTemperature = supportsTemperature;
        }

        public ModelSettings(String provider, boolean reasoningRequired, boolean supportsTemperature,
                int maxInputTokens) {
            this.provider = provider;
            this.reasoningRequired = reasoningRequired;
            this.supportsTemperature = supportsTemperature;
            this.maxInputTokens = maxInputTokens;
        }
    }
}
