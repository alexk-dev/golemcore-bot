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
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for loading and managing model configurations from external JSON
 * file.
 *
 * <p>
 * This service loads model metadata from {@code models.json} in the working
 * directory. Model configurations include:
 * <ul>
 * <li>Provider - OpenAI, Anthropic, etc.</li>
 * <li>Reasoning support - whether model supports reasoning levels</li>
 * <li>Temperature support - whether model supports temperature parameter</li>
 * <li>Context limits - maximum input tokens (per reasoning level for reasoning
 * models)</li>
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
    private static final String PROVIDER_OPENAI = "openai";
    private static final String PROVIDER_ANTHROPIC = "anthropic";
    private static final String REASONING_MEDIUM = "medium";

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
        ReasoningConfig gpt5Reasoning = new ReasoningConfig();
        gpt5Reasoning.setDefaultLevel(REASONING_MEDIUM);
        gpt5Reasoning.getLevels().put("low", new ReasoningLevelConfig(1000000));
        gpt5Reasoning.getLevels().put(REASONING_MEDIUM, new ReasoningLevelConfig(1000000));
        gpt5Reasoning.getLevels().put("high", new ReasoningLevelConfig(500000));
        gpt5Reasoning.getLevels().put("xhigh", new ReasoningLevelConfig(250000));

        ModelSettings gpt51 = new ModelSettings();
        gpt51.setProvider(PROVIDER_OPENAI);
        gpt51.setDisplayName("GPT-5.1");
        gpt51.setSupportsTemperature(false);
        gpt51.setReasoning(gpt5Reasoning);
        cfg.getModels().put("gpt-5.1", gpt51);

        ModelSettings gpt52 = new ModelSettings();
        gpt52.setProvider(PROVIDER_OPENAI);
        gpt52.setDisplayName("GPT-5.2");
        gpt52.setSupportsTemperature(false);
        gpt52.setReasoning(gpt5Reasoning);
        cfg.getModels().put("gpt-5.2", gpt52);

        ReasoningConfig o3Reasoning = new ReasoningConfig();
        o3Reasoning.setDefaultLevel(REASONING_MEDIUM);
        o3Reasoning.getLevels().put("low", new ReasoningLevelConfig(200000));
        o3Reasoning.getLevels().put(REASONING_MEDIUM, new ReasoningLevelConfig(200000));
        o3Reasoning.getLevels().put("high", new ReasoningLevelConfig(200000));

        ModelSettings o1 = new ModelSettings();
        o1.setProvider(PROVIDER_OPENAI);
        o1.setDisplayName("o1");
        o1.setSupportsTemperature(false);
        o1.setReasoning(o3Reasoning);
        cfg.getModels().put("o1", o1);

        ModelSettings o3 = new ModelSettings();
        o3.setProvider(PROVIDER_OPENAI);
        o3.setDisplayName("o3");
        o3.setSupportsTemperature(false);
        o3.setReasoning(o3Reasoning);
        cfg.getModels().put("o3", o3);

        ReasoningConfig o3MiniReasoning = new ReasoningConfig();
        o3MiniReasoning.setDefaultLevel(REASONING_MEDIUM);
        o3MiniReasoning.getLevels().put("low", new ReasoningLevelConfig(128000));
        o3MiniReasoning.getLevels().put(REASONING_MEDIUM, new ReasoningLevelConfig(128000));
        o3MiniReasoning.getLevels().put("high", new ReasoningLevelConfig(128000));

        ModelSettings o3Mini = new ModelSettings();
        o3Mini.setProvider(PROVIDER_OPENAI);
        o3Mini.setDisplayName("o3 Mini");
        o3Mini.setSupportsTemperature(false);
        o3Mini.setReasoning(o3MiniReasoning);
        cfg.getModels().put("o3-mini", o3Mini);

        // OpenAI standard models
        ModelSettings gpt4o = new ModelSettings();
        gpt4o.setProvider(PROVIDER_OPENAI);
        gpt4o.setDisplayName("GPT-4o");
        gpt4o.setSupportsTemperature(true);
        gpt4o.setMaxInputTokens(128000);
        cfg.getModels().put("gpt-4o", gpt4o);

        ModelSettings gpt4Turbo = new ModelSettings();
        gpt4Turbo.setProvider(PROVIDER_OPENAI);
        gpt4Turbo.setDisplayName("GPT-4 Turbo");
        gpt4Turbo.setSupportsTemperature(true);
        gpt4Turbo.setMaxInputTokens(128000);
        cfg.getModels().put("gpt-4-turbo", gpt4Turbo);

        // Anthropic models
        ModelSettings claudeSonnet4 = new ModelSettings();
        claudeSonnet4.setProvider(PROVIDER_ANTHROPIC);
        claudeSonnet4.setDisplayName("Claude Sonnet 4");
        claudeSonnet4.setSupportsTemperature(true);
        claudeSonnet4.setMaxInputTokens(200000);
        cfg.getModels().put("claude-sonnet-4-20250514", claudeSonnet4);

        ModelSettings claude35Sonnet = new ModelSettings();
        claude35Sonnet.setProvider(PROVIDER_ANTHROPIC);
        claude35Sonnet.setDisplayName("Claude 3.5 Sonnet");
        claude35Sonnet.setSupportsTemperature(true);
        claude35Sonnet.setMaxInputTokens(200000);
        cfg.getModels().put("claude-3-5-sonnet", claude35Sonnet);

        ModelSettings claude3Opus = new ModelSettings();
        claude3Opus.setProvider(PROVIDER_ANTHROPIC);
        claude3Opus.setDisplayName("Claude 3 Opus");
        claude3Opus.setSupportsTemperature(true);
        claude3Opus.setMaxInputTokens(200000);
        cfg.getModels().put("claude-3-opus", claude3Opus);

        ModelSettings claude3Haiku = new ModelSettings();
        claude3Haiku.setProvider(PROVIDER_ANTHROPIC);
        claude3Haiku.setDisplayName("Claude 3 Haiku");
        claude3Haiku.setSupportsTemperature(true);
        claude3Haiku.setMaxInputTokens(200000);
        cfg.getModels().put("claude-3-haiku", claude3Haiku);

        // Defaults
        ModelSettings defaults = new ModelSettings();
        defaults.setProvider(PROVIDER_OPENAI);
        defaults.setSupportsTemperature(true);
        defaults.setMaxInputTokens(128000);
        cfg.setDefaults(defaults);

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
