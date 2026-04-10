package me.golemcore.bot.application.settings;

import me.golemcore.bot.domain.model.MemoryPreset;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.plugin.runtime.SttProviderRegistry;
import me.golemcore.bot.plugin.runtime.TtsProviderRegistry;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public class RuntimeSettingsValidator {

    private static final String TELEGRAM_AUTH_MODE_INVITE_ONLY = "invite_only";
    private static final String STT_PROVIDER_ELEVENLABS = "golemcore/elevenlabs";
    private static final String STT_PROVIDER_WHISPER = "golemcore/whisper";
    private static final String LEGACY_STT_PROVIDER_ELEVENLABS = "elevenlabs";
    private static final String LEGACY_STT_PROVIDER_WHISPER = "whisper";
    private static final Set<String> VALID_API_TYPES = Set.of("openai", "anthropic", "gemini");
    private static final String DEFAULT_COMPACTION_TRIGGER_MODE = "model_ratio";
    private static final String COMPACTION_TRIGGER_MODE_TOKEN_THRESHOLD = "token_threshold";
    private static final Set<String> VALID_COMPACTION_TRIGGER_MODES = Set.of(
            DEFAULT_COMPACTION_TRIGGER_MODE,
            COMPACTION_TRIGGER_MODE_TOKEN_THRESHOLD);
    private static final double DEFAULT_COMPACTION_MODEL_THRESHOLD_RATIO = 0.95d;
    private static final int MEMORY_SOFT_BUDGET_MIN = 200;
    private static final int MEMORY_SOFT_BUDGET_MAX = 10000;
    private static final int MEMORY_MAX_BUDGET_MIN = 200;
    private static final int MEMORY_MAX_BUDGET_MAX = 12000;
    private static final int MEMORY_TOP_K_MIN = 0;
    private static final int MEMORY_TOP_K_MAX = 30;
    private static final int MEMORY_DECAY_DAYS_MIN = 1;
    private static final int MEMORY_DECAY_DAYS_MAX = 3650;
    private static final int MEMORY_RETRIEVAL_LOOKBACK_DAYS_MIN = 1;
    private static final int MEMORY_RETRIEVAL_LOOKBACK_DAYS_MAX = 90;
    private static final Set<String> VALID_MEMORY_DISCLOSURE_MODES = Set.of(
            "index",
            "summary",
            "selective_detail",
            "full_pack");
    private static final Set<String> VALID_MEMORY_PROMPT_STYLES = Set.of("compact", "balanced", "rich");
    private static final Set<String> VALID_MEMORY_RERANKING_PROFILES = Set.of("balanced", "aggressive");
    private static final Set<String> VALID_MEMORY_DIAGNOSTICS_VERBOSITY = Set.of("off", "basic", "detailed");
    private static final int TURN_PROGRESS_BATCH_SIZE_MIN = 1;
    private static final int TURN_PROGRESS_BATCH_SIZE_MAX = 50;
    private static final int TURN_PROGRESS_MAX_SILENCE_SECONDS_MIN = 1;
    private static final int TURN_PROGRESS_MAX_SILENCE_SECONDS_MAX = 300;
    private static final int TURN_PROGRESS_SUMMARY_TIMEOUT_MS_MIN = 1000;
    private static final int TURN_PROGRESS_SUMMARY_TIMEOUT_MS_MAX = 60000;
    private static final Pattern SHELL_ENV_VAR_NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> RESERVED_SHELL_ENV_VAR_NAMES = Set.of("HOME", "PWD");
    private static final int SHELL_ENV_VAR_NAME_MAX_LENGTH = 128;
    private static final int SHELL_ENV_VAR_VALUE_MAX_LENGTH = 8192;

    private final ModelSelectionService modelSelectionService;
    private final SttProviderRegistry sttProviderRegistry;
    private final TtsProviderRegistry ttsProviderRegistry;

    public RuntimeSettingsValidator(ModelSelectionService modelSelectionService,
            SttProviderRegistry sttProviderRegistry,
            TtsProviderRegistry ttsProviderRegistry) {
        this.modelSelectionService = modelSelectionService;
        this.sttProviderRegistry = sttProviderRegistry;
        this.ttsProviderRegistry = ttsProviderRegistry;
    }

    public void validateRuntimeConfigUpdate(RuntimeConfig current, RuntimeConfig merged,
            boolean hiveManagedByProperties) {
        if (merged == null) {
            throw new IllegalArgumentException("runtime config is required");
        }
        rejectManagedHiveMutation(current, merged.getHive(), hiveManagedByProperties);
        if (merged.getTelegram() == null) {
            merged.setTelegram(new RuntimeConfig.TelegramConfig());
        }
        normalizeAndValidateTelegramConfig(merged.getTelegram());
        normalizeAndValidateShellEnvironmentVariables(merged.getTools());
        validateLlmConfig(merged.getLlm(), merged.getModelRouter());
        validateModelRouterConfig(merged.getModelRouter(), merged.getLlm());
        if (merged.getAutoMode() != null) {
            validateAndNormalizeAutoModeConfig(merged.getAutoMode());
        }
        if (merged.getTracing() != null) {
            validateAndNormalizeTracingConfig(merged.getTracing());
        }
        if (merged.getMemory() != null) {
            validateMemoryConfig(merged.getMemory());
        }
        validateCompactionConfig(merged.getCompaction());
        validateVoiceConfig(merged.getVoice());
        validateAndNormalizeModelRegistryConfig(merged.getModelRegistry());
        validateHiveConfig(merged.getHive());
    }

    public void validateModelRouterConfig(RuntimeConfig.ModelRouterConfig modelRouterConfig,
            RuntimeConfig.LlmConfig llmConfig) {
        if (modelRouterConfig == null) {
            return;
        }
        List<String> configuredProviders = llmConfig != null && llmConfig.getProviders() != null
                ? new ArrayList<>(llmConfig.getProviders().keySet())
                : List.of();
        validateModelRouterBinding(modelRouterConfig.getRouting(), "routing", configuredProviders);
        if (modelRouterConfig.getTiers() == null) {
            return;
        }
        for (Map.Entry<String, RuntimeConfig.TierBinding> entry : modelRouterConfig.getTiers().entrySet()) {
            validateModelRouterBinding(entry.getValue(), entry.getKey(), configuredProviders);
        }
    }

    public void validateLlmConfig(RuntimeConfig.LlmConfig llmConfig,
            RuntimeConfig.ModelRouterConfig modelRouterConfig) {
        if (llmConfig == null) {
            throw new IllegalArgumentException("llm config is required");
        }

        Map<String, RuntimeConfig.LlmProviderConfig> providers = llmConfig.getProviders();
        if (providers == null) {
            llmConfig.setProviders(new LinkedHashMap<>());
            return;
        }

        Set<String> normalizedNames = new LinkedHashSet<>();
        for (Map.Entry<String, RuntimeConfig.LlmProviderConfig> entry : providers.entrySet()) {
            String providerName = entry.getKey();
            RuntimeConfig.LlmProviderConfig providerConfig = entry.getValue();

            if (providerName == null || providerName.isBlank()) {
                throw new IllegalArgumentException("llm.providers keys must be non-empty");
            }
            if (!providerName.equals(providerName.trim())) {
                throw new IllegalArgumentException("llm.providers keys must not have leading/trailing spaces");
            }
            if (!providerName.equals(providerName.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("llm.providers keys must be lowercase");
            }
            if (!providerName.matches("[a-z0-9][a-z0-9_-]*")) {
                throw new IllegalArgumentException("llm.providers keys must match [a-z0-9][a-z0-9_-]*");
            }
            if (!normalizedNames.add(providerName)) {
                throw new IllegalArgumentException("llm.providers contains duplicate provider key: " + providerName);
            }
            validateProviderConfig(providerName, providerConfig);
        }

        Set<String> providersUsedByModelRouter = getProvidersUsedByModelRouter(modelRouterConfig);
        for (String usedProvider : providersUsedByModelRouter) {
            if (!providers.containsKey(usedProvider)) {
                throw new IllegalArgumentException(
                        "Cannot remove provider '" + usedProvider + "' because it is used by model router tiers");
            }
        }
    }

    public void validateProviderConfig(String name, RuntimeConfig.LlmProviderConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Provider config is required");
        }
        Integer timeout = config.getRequestTimeoutSeconds();
        if (timeout != null && (timeout < 1 || timeout > 3600)) {
            throw new IllegalArgumentException(
                    "llm.providers." + name + ".requestTimeoutSeconds must be between 1 and 3600");
        }
        String baseUrl = config.getBaseUrl();
        if (baseUrl != null && !baseUrl.isBlank() && !isValidHttpUrl(baseUrl)) {
            throw new IllegalArgumentException(
                    "llm.providers." + name + ".baseUrl must be a valid http(s) URL");
        }
        String apiType = config.getApiType();
        if (apiType != null && !apiType.isBlank() && !VALID_API_TYPES.contains(apiType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "llm.providers." + name + ".apiType must be one of " + VALID_API_TYPES);
        }
    }

    public String normalizeProviderName(String name) {
        String normalizedName = name.toLowerCase(Locale.ROOT);
        if (!normalizedName.matches("[a-z0-9][a-z0-9_-]*")) {
            throw new IllegalArgumentException("Provider name must match [a-z0-9][a-z0-9_-]*");
        }
        return normalizedName;
    }

    public void validateProviderRemoval(RuntimeConfig.ModelRouterConfig modelRouterConfig,
            String normalizedProviderName) {
        Set<String> usedProviders = getProvidersUsedByModelRouter(modelRouterConfig);
        if (usedProviders.contains(normalizedProviderName)) {
            throw new IllegalArgumentException(
                    "Cannot remove provider '" + normalizedProviderName + "' because it is used by model router tiers");
        }
    }

    public void validateTurnConfig(RuntimeConfig.TurnConfig turnConfig) {
        if (turnConfig == null) {
            throw new IllegalArgumentException("turn config is required");
        }
        if (turnConfig.getMaxLlmCalls() != null && turnConfig.getMaxLlmCalls() < 1) {
            throw new IllegalArgumentException("turn.maxLlmCalls must be >= 1");
        }
        if (turnConfig.getMaxToolExecutions() != null && turnConfig.getMaxToolExecutions() < 1) {
            throw new IllegalArgumentException("turn.maxToolExecutions must be >= 1");
        }
        if (turnConfig.getDeadline() != null && !turnConfig.getDeadline().isBlank()) {
            try {
                Duration deadline = Duration.parse(turnConfig.getDeadline().trim());
                if (deadline.isZero() || deadline.isNegative()) {
                    throw new IllegalArgumentException("turn.deadline must be a positive ISO-8601 duration");
                }
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("turn.deadline must be a valid ISO-8601 duration");
            }
        }
        validateRange(turnConfig.getProgressBatchSize(), TURN_PROGRESS_BATCH_SIZE_MIN, TURN_PROGRESS_BATCH_SIZE_MAX,
                "turn.progressBatchSize");
        validateRange(turnConfig.getProgressMaxSilenceSeconds(),
                TURN_PROGRESS_MAX_SILENCE_SECONDS_MIN,
                TURN_PROGRESS_MAX_SILENCE_SECONDS_MAX,
                "turn.progressMaxSilenceSeconds");
        validateRange(turnConfig.getProgressSummaryTimeoutMs(),
                TURN_PROGRESS_SUMMARY_TIMEOUT_MS_MIN,
                TURN_PROGRESS_SUMMARY_TIMEOUT_MS_MAX,
                "turn.progressSummaryTimeoutMs");
    }

    public void validateMemoryConfig(RuntimeConfig.MemoryConfig memoryConfig) {
        if (memoryConfig == null) {
            throw new IllegalArgumentException("memory config is required");
        }
        if (memoryConfig.getDisclosure() == null) {
            memoryConfig.setDisclosure(RuntimeConfig.MemoryDisclosureConfig.builder().build());
        }
        if (memoryConfig.getReranking() == null) {
            memoryConfig.setReranking(RuntimeConfig.MemoryRerankingConfig.builder().build());
        }
        if (memoryConfig.getDiagnostics() == null) {
            memoryConfig.setDiagnostics(RuntimeConfig.MemoryDiagnosticsConfig.builder().build());
        }

        validateNullableInteger(memoryConfig.getSoftPromptBudgetTokens(), MEMORY_SOFT_BUDGET_MIN,
                MEMORY_SOFT_BUDGET_MAX, "memory.softPromptBudgetTokens");
        validateNullableInteger(memoryConfig.getMaxPromptBudgetTokens(), MEMORY_MAX_BUDGET_MIN,
                MEMORY_MAX_BUDGET_MAX, "memory.maxPromptBudgetTokens");
        validateNullableInteger(memoryConfig.getWorkingTopK(), MEMORY_TOP_K_MIN, MEMORY_TOP_K_MAX,
                "memory.workingTopK");
        validateNullableInteger(memoryConfig.getEpisodicTopK(), MEMORY_TOP_K_MIN, MEMORY_TOP_K_MAX,
                "memory.episodicTopK");
        validateNullableInteger(memoryConfig.getSemanticTopK(), MEMORY_TOP_K_MIN, MEMORY_TOP_K_MAX,
                "memory.semanticTopK");
        validateNullableInteger(memoryConfig.getProceduralTopK(), MEMORY_TOP_K_MIN, MEMORY_TOP_K_MAX,
                "memory.proceduralTopK");
        validateNullableDouble(memoryConfig.getPromotionMinConfidence(), 0.0, 1.0, "memory.promotionMinConfidence");
        validateNullableInteger(memoryConfig.getDecayDays(), MEMORY_DECAY_DAYS_MIN, MEMORY_DECAY_DAYS_MAX,
                "memory.decayDays");
        validateNullableInteger(memoryConfig.getRetrievalLookbackDays(), MEMORY_RETRIEVAL_LOOKBACK_DAYS_MIN,
                MEMORY_RETRIEVAL_LOOKBACK_DAYS_MAX, "memory.retrievalLookbackDays");

        Integer softBudget = memoryConfig.getSoftPromptBudgetTokens();
        Integer maxBudget = memoryConfig.getMaxPromptBudgetTokens();
        if (softBudget != null && maxBudget != null && maxBudget < softBudget) {
            throw new IllegalArgumentException(
                    "memory.maxPromptBudgetTokens must be greater than or equal to memory.softPromptBudgetTokens");
        }

        normalizeAndValidateMemoryDisclosureConfig(memoryConfig.getDisclosure());
        normalizeAndValidateMemoryRerankingConfig(memoryConfig.getReranking());
        normalizeAndValidateMemoryDiagnosticsConfig(memoryConfig.getDiagnostics());
    }

    public void validateCompactionConfig(RuntimeConfig.CompactionConfig compactionConfig) {
        if (compactionConfig == null) {
            return;
        }
        String triggerMode = compactionConfig.getTriggerMode();
        if (triggerMode == null || triggerMode.isBlank()) {
            compactionConfig.setTriggerMode(DEFAULT_COMPACTION_TRIGGER_MODE);
        } else {
            String normalized = triggerMode.trim().toLowerCase(Locale.ROOT);
            if (!VALID_COMPACTION_TRIGGER_MODES.contains(normalized)) {
                throw new IllegalArgumentException(
                        "compaction.triggerMode must be one of " + VALID_COMPACTION_TRIGGER_MODES);
            }
            compactionConfig.setTriggerMode(normalized);
        }

        Double modelThresholdRatio = compactionConfig.getModelThresholdRatio();
        if (modelThresholdRatio == null) {
            compactionConfig.setModelThresholdRatio(DEFAULT_COMPACTION_MODEL_THRESHOLD_RATIO);
        } else if (modelThresholdRatio <= 0.0d || modelThresholdRatio > 1.0d) {
            throw new IllegalArgumentException("compaction.modelThresholdRatio must be between 0 and 1");
        }

        Integer maxContextTokens = compactionConfig.getMaxContextTokens();
        if (maxContextTokens != null && maxContextTokens < 1) {
            throw new IllegalArgumentException("compaction.maxContextTokens must be greater than 0");
        }
        Integer keepLastMessages = compactionConfig.getKeepLastMessages();
        if (keepLastMessages != null && keepLastMessages < 1) {
            throw new IllegalArgumentException("compaction.keepLastMessages must be greater than 0");
        }
    }

    public void validateVoiceConfig(RuntimeConfig.VoiceConfig voiceConfig) {
        if (voiceConfig == null) {
            return;
        }
        boolean voiceEnabled = Boolean.TRUE.equals(voiceConfig.getEnabled());

        String normalizedSttProvider = normalizeProvider(voiceConfig.getSttProvider());
        if (normalizedSttProvider == null) {
            normalizedSttProvider = firstLoadedSttProvider();
        }
        if (normalizedSttProvider == null && voiceEnabled) {
            throw new IllegalArgumentException("voice.sttProvider must resolve to a loaded STT provider");
        }
        if (normalizedSttProvider != null && !isKnownSttProvider(normalizedSttProvider)) {
            throw new IllegalArgumentException("voice.sttProvider must resolve to a loaded STT provider");
        }
        voiceConfig.setSttProvider(normalizedSttProvider);

        String normalizedTtsProvider = normalizeProvider(voiceConfig.getTtsProvider());
        if (normalizedTtsProvider == null) {
            normalizedTtsProvider = firstLoadedTtsProvider();
        }
        if (normalizedTtsProvider == null && voiceEnabled) {
            throw new IllegalArgumentException("voice.ttsProvider must resolve to a loaded TTS provider");
        }
        if (normalizedTtsProvider != null && !isKnownTtsProvider(normalizedTtsProvider)) {
            throw new IllegalArgumentException("voice.ttsProvider must resolve to a loaded TTS provider");
        }
        voiceConfig.setTtsProvider(normalizedTtsProvider);

        String whisperSttUrl = voiceConfig.getWhisperSttUrl();
        if (whisperSttUrl != null && whisperSttUrl.isBlank()) {
            voiceConfig.setWhisperSttUrl(null);
        }
    }

    public void validatePlanConfig(RuntimeConfig.PlanConfig planConfig) {
        if (planConfig == null) {
            throw new IllegalArgumentException("plan config is required");
        }
        validateNullableInteger(planConfig.getMaxPlans(), 1, 100, "plan.maxPlans");
        validateNullableInteger(planConfig.getMaxStepsPerPlan(), 1, 1000, "plan.maxStepsPerPlan");
    }

    public void validateAndNormalizeAutoModeConfig(RuntimeConfig.AutoModeConfig autoConfig) {
        if (autoConfig == null) {
            throw new IllegalArgumentException("autoMode config is required");
        }
        autoConfig.setModelTier(normalizeOptionalSelectableTier(autoConfig.getModelTier(), "autoMode.modelTier"));
        autoConfig.setReflectionModelTier(normalizeOptionalSelectableTier(autoConfig.getReflectionModelTier(),
                "autoMode.reflectionModelTier"));
    }

    public void validateAndNormalizeTracingConfig(RuntimeConfig.TracingConfig tracingConfig) {
        if (tracingConfig == null) {
            throw new IllegalArgumentException("tracing config is required");
        }
        validateNullableInteger(tracingConfig.getSessionTraceBudgetMb(), 1, 1024, "tracing.sessionTraceBudgetMb");
        validateNullableInteger(tracingConfig.getMaxSnapshotSizeKb(), 1, 10240, "tracing.maxSnapshotSizeKb");
        validateNullableInteger(tracingConfig.getMaxSnapshotsPerSpan(), 1, 1000, "tracing.maxSnapshotsPerSpan");
        validateNullableInteger(tracingConfig.getMaxTracesPerSession(), 1, 10000, "tracing.maxTracesPerSession");
    }

    public void validateHiveConfig(RuntimeConfig.HiveConfig hiveConfig) {
        if (hiveConfig == null) {
            throw new IllegalArgumentException("hive config is required");
        }
        String serverUrl = hiveConfig.getServerUrl();
        if (serverUrl != null && !serverUrl.isBlank() && !isValidHttpUrl(serverUrl)) {
            throw new IllegalArgumentException("hive.serverUrl must be a valid http(s) URL");
        }
    }

    public void validateAndNormalizeModelRegistryConfig(RuntimeConfig.ModelRegistryConfig modelRegistryConfig) {
        if (modelRegistryConfig == null) {
            return;
        }
        String repositoryUrl = modelRegistryConfig.getRepositoryUrl();
        if (repositoryUrl != null) {
            repositoryUrl = repositoryUrl.trim();
        }
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            modelRegistryConfig.setRepositoryUrl(null);
        } else {
            if (!isValidHttpUrl(repositoryUrl)) {
                throw new IllegalArgumentException("modelRegistry.repositoryUrl must be a valid http(s) URL");
            }
            modelRegistryConfig.setRepositoryUrl(repositoryUrl);
        }

        String branch = modelRegistryConfig.getBranch();
        if (branch == null || branch.isBlank()) {
            modelRegistryConfig.setBranch("main");
        } else {
            modelRegistryConfig.setBranch(branch.trim());
        }
    }

    public void validateWebhookConfig(UserPreferences.WebhookConfig webhookConfig) {
        if (webhookConfig == null || webhookConfig.getMappings() == null) {
            return;
        }
        for (UserPreferences.HookMapping mapping : webhookConfig.getMappings()) {
            if (mapping == null) {
                continue;
            }
            mapping.setModel(normalizeOptionalSelectableTier(mapping.getModel(), "webhooks.mapping.model"));
        }
    }

    public RuntimeConfig.ToolsConfig ensureToolsConfig(RuntimeConfig config) {
        RuntimeConfig.ToolsConfig toolsConfig = config.getTools();
        if (toolsConfig == null) {
            toolsConfig = RuntimeConfig.ToolsConfig.builder().build();
            config.setTools(toolsConfig);
        }
        return toolsConfig;
    }

    public List<RuntimeConfig.ShellEnvironmentVariable> ensureShellEnvironmentVariables(
            RuntimeConfig.ToolsConfig toolsConfig) {
        List<RuntimeConfig.ShellEnvironmentVariable> variables = toolsConfig.getShellEnvironmentVariables();
        if (variables == null) {
            variables = new ArrayList<>();
            toolsConfig.setShellEnvironmentVariables(variables);
        }
        return variables;
    }

    public void normalizeAndValidateShellEnvironmentVariables(RuntimeConfig.ToolsConfig toolsConfig) {
        if (toolsConfig == null) {
            return;
        }
        List<RuntimeConfig.ShellEnvironmentVariable> variables = toolsConfig.getShellEnvironmentVariables();
        if (variables == null || variables.isEmpty()) {
            toolsConfig.setShellEnvironmentVariables(new ArrayList<>());
            return;
        }

        List<RuntimeConfig.ShellEnvironmentVariable> normalized = new ArrayList<>(variables.size());
        Set<String> names = new LinkedHashSet<>();
        for (RuntimeConfig.ShellEnvironmentVariable variable : variables) {
            RuntimeConfig.ShellEnvironmentVariable normalizedVariable = normalizeAndValidateShellEnvironmentVariable(
                    variable);
            if (!names.add(normalizedVariable.getName())) {
                throw new IllegalArgumentException(
                        "tools.shellEnvironmentVariables contains duplicate name: " + normalizedVariable.getName());
            }
            normalized.add(normalizedVariable);
        }
        toolsConfig.setShellEnvironmentVariables(normalized);
    }

    public RuntimeConfig.ShellEnvironmentVariable normalizeAndValidateShellEnvironmentVariable(
            RuntimeConfig.ShellEnvironmentVariable variable) {
        if (variable == null) {
            throw new IllegalArgumentException("tools.shellEnvironmentVariables item is required");
        }
        String normalizedName = normalizeAndValidateShellEnvironmentVariableName(variable.getName());
        String normalizedValue = normalizeAndValidateShellEnvironmentVariableValue(variable.getValue(), normalizedName);
        return RuntimeConfig.ShellEnvironmentVariable.builder()
                .name(normalizedName)
                .value(normalizedValue)
                .build();
    }

    public String normalizeAndValidateShellEnvironmentVariableName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tools.shellEnvironmentVariables.name is required");
        }
        String normalized = value.trim();
        if (normalized.length() > SHELL_ENV_VAR_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("tools.shellEnvironmentVariables.name must be at most "
                    + SHELL_ENV_VAR_NAME_MAX_LENGTH + " characters");
        }
        if (!SHELL_ENV_VAR_NAME_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "tools.shellEnvironmentVariables.name must match [A-Za-z_][A-Za-z0-9_]*");
        }
        if (RESERVED_SHELL_ENV_VAR_NAMES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "tools.shellEnvironmentVariables.name must not redefine reserved variable: " + normalized);
        }
        return normalized;
    }

    public boolean containsShellEnvironmentVariableName(List<RuntimeConfig.ShellEnvironmentVariable> variables,
            String name) {
        return findShellEnvironmentVariableIndex(variables, name) >= 0;
    }

    public int findShellEnvironmentVariableIndex(List<RuntimeConfig.ShellEnvironmentVariable> variables, String name) {
        for (int index = 0; index < variables.size(); index++) {
            RuntimeConfig.ShellEnvironmentVariable variable = variables.get(index);
            if (variable != null && name.equals(variable.getName())) {
                return index;
            }
        }
        return -1;
    }

    public void validateMcpCatalogEntry(RuntimeConfig.McpCatalogEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("MCP catalog entry is required");
        }
        if (entry.getName() == null || entry.getName().isBlank()) {
            throw new IllegalArgumentException("MCP catalog entry name is required");
        }
        String name = entry.getName().toLowerCase(Locale.ROOT).trim();
        if (!name.matches("[a-z0-9][a-z0-9_-]*")) {
            throw new IllegalArgumentException("MCP catalog entry name must match [a-z0-9][a-z0-9_-]*");
        }
        if (entry.getCommand() == null || entry.getCommand().isBlank()) {
            throw new IllegalArgumentException("MCP catalog entry command is required");
        }
        Integer startupTimeout = entry.getStartupTimeoutSeconds();
        if (startupTimeout != null && (startupTimeout < 1 || startupTimeout > 300)) {
            throw new IllegalArgumentException("startupTimeoutSeconds must be between 1 and 300");
        }
        Integer idleTimeout = entry.getIdleTimeoutMinutes();
        if (idleTimeout != null && (idleTimeout < 1 || idleTimeout > 120)) {
            throw new IllegalArgumentException("idleTimeoutMinutes must be between 1 and 120");
        }
    }

    public String normalizeCatalogEntryName(String value) {
        return value.toLowerCase(Locale.ROOT).trim();
    }

    public void rejectManagedHiveMutation(RuntimeConfig current, RuntimeConfig.HiveConfig incomingHiveConfig,
            boolean hiveManagedByProperties) {
        if (current == null || !hiveManagedByProperties || incomingHiveConfig == null) {
            return;
        }
        RuntimeConfig.HiveConfig currentHiveConfig = current.getHive();
        RuntimeConfig.HiveConfig normalizedCurrentHiveConfig = currentHiveConfig != null
                ? currentHiveConfig
                : RuntimeConfig.HiveConfig.builder().build();
        if (!incomingHiveConfig.equals(normalizedCurrentHiveConfig)) {
            throw new IllegalStateException("Hive settings are managed by bot.hive.* and are read-only");
        }
    }

    public String normalizeOptionalSelectableTier(String tier, String fieldName) {
        String normalizedTier = ModelTierCatalog.normalizeTierId(tier);
        if (normalizedTier == null) {
            return null;
        }
        if ("default".equals(normalizedTier)) {
            return null;
        }
        return normalizeRequiredSelectableTier(normalizedTier, fieldName);
    }

    public String normalizeRequiredSelectableTier(String tier, String fieldName) {
        String normalizedTier = ModelTierCatalog.normalizeTierId(tier);
        if (!ModelTierCatalog.isExplicitSelectableTier(normalizedTier)) {
            throw new IllegalArgumentException(fieldName + " must be a known tier id");
        }
        return normalizedTier;
    }

    private void normalizeAndValidateTelegramConfig(RuntimeConfig.TelegramConfig telegramConfig) {
        telegramConfig.setAuthMode(TELEGRAM_AUTH_MODE_INVITE_ONLY);

        List<String> allowedUsers = telegramConfig.getAllowedUsers();
        if (allowedUsers == null) {
            telegramConfig.setAllowedUsers(new ArrayList<>());
            return;
        }

        for (String userId : allowedUsers) {
            if (userId == null || !userId.matches("\\d+")) {
                throw new IllegalArgumentException("telegram.allowedUsers must contain numeric IDs only");
            }
        }
        if (allowedUsers.size() > 1) {
            throw new IllegalArgumentException("telegram.allowedUsers supports only one invited user");
        }
    }

    private void validateModelRouterBinding(RuntimeConfig.TierBinding binding, String tier,
            List<String> configuredProviders) {
        if (binding == null || binding.getModel() == null || binding.getModel().isBlank()) {
            return;
        }
        ModelSelectionService.ValidationResult validation = modelSelectionService.validateModel(
                binding.getModel(),
                configuredProviders);
        if (validation.valid()) {
            return;
        }
        throw switch (validation.error()) {
        case "model.not.found" -> new IllegalArgumentException(
                "Model router tier '" + tier + "' points to unknown model '" + binding.getModel() + "'");
        case "provider.not.configured" -> new IllegalArgumentException(
                "Model router tier '" + tier + "' points to model '" + binding.getModel()
                        + "' whose provider is not configured");
        default -> new IllegalArgumentException(
                "Model router tier '" + tier + "' is invalid: " + validation.error());
        };
    }

    private void validateRange(Integer value, int min, int max, String fieldName) {
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max);
        }
    }

    private void normalizeAndValidateMemoryDisclosureConfig(RuntimeConfig.MemoryDisclosureConfig disclosureConfig) {
        if (disclosureConfig == null) {
            return;
        }
        disclosureConfig.setMode(normalizeAndValidateMemoryOption(
                disclosureConfig.getMode(),
                "summary",
                VALID_MEMORY_DISCLOSURE_MODES,
                "memory.disclosure.mode"));
        disclosureConfig.setPromptStyle(normalizeAndValidateMemoryOption(
                disclosureConfig.getPromptStyle(),
                "balanced",
                VALID_MEMORY_PROMPT_STYLES,
                "memory.disclosure.promptStyle"));
        validateNullableDouble(disclosureConfig.getDetailMinScore(), 0.0, 1.0, "memory.disclosure.detailMinScore");
        if (disclosureConfig.getToolExpansionEnabled() == null) {
            disclosureConfig.setToolExpansionEnabled(true);
        }
        if (disclosureConfig.getDisclosureHintsEnabled() == null) {
            disclosureConfig.setDisclosureHintsEnabled(true);
        }
    }

    private void normalizeAndValidateMemoryDiagnosticsConfig(RuntimeConfig.MemoryDiagnosticsConfig diagnosticsConfig) {
        if (diagnosticsConfig == null) {
            return;
        }
        diagnosticsConfig.setVerbosity(normalizeAndValidateMemoryOption(
                diagnosticsConfig.getVerbosity(),
                "basic",
                VALID_MEMORY_DIAGNOSTICS_VERBOSITY,
                "memory.diagnostics.verbosity"));
    }

    private void normalizeAndValidateMemoryRerankingConfig(RuntimeConfig.MemoryRerankingConfig rerankingConfig) {
        if (rerankingConfig == null) {
            return;
        }
        rerankingConfig.setProfile(normalizeAndValidateMemoryOption(
                rerankingConfig.getProfile(),
                "balanced",
                VALID_MEMORY_RERANKING_PROFILES,
                "memory.reranking.profile"));
        if (rerankingConfig.getEnabled() == null) {
            rerankingConfig.setEnabled(true);
        }
    }

    private void validateNullableInteger(Integer value, int min, int max, String fieldName) {
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max);
        }
    }

    private void validateNullableDouble(Double value, double min, double max, String fieldName) {
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max);
        }
    }

    private String normalizeAndValidateMemoryOption(
            String value,
            String defaultValue,
            Set<String> allowedValues,
            String fieldName) {
        String normalized = value == null || value.isBlank()
                ? defaultValue
                : value.trim().toLowerCase(Locale.ROOT);
        if (!allowedValues.contains(normalized)) {
            throw new IllegalArgumentException(fieldName + " must be one of " + allowedValues);
        }
        return normalized;
    }

    private String normalizeAndValidateShellEnvironmentVariableValue(String value, String normalizedName) {
        String normalizedValue = value != null ? value : "";
        if (normalizedValue.length() > SHELL_ENV_VAR_VALUE_MAX_LENGTH) {
            throw new IllegalArgumentException("tools.shellEnvironmentVariables." + normalizedName
                    + ".value must be at most " + SHELL_ENV_VAR_VALUE_MAX_LENGTH + " characters");
        }
        return normalizedValue;
    }

    private Set<String> getProvidersUsedByModelRouter(RuntimeConfig.ModelRouterConfig modelRouterConfig) {
        Set<String> usedProviders = new LinkedHashSet<>();
        if (modelRouterConfig == null) {
            return usedProviders;
        }
        RuntimeConfig.TierBinding routingBinding = modelRouterConfig.getRouting();
        if (routingBinding != null) {
            addProviderFromModel(usedProviders, routingBinding.getModel());
        }
        if (modelRouterConfig.getTiers() != null) {
            for (RuntimeConfig.TierBinding tierBinding : modelRouterConfig.getTiers().values()) {
                if (tierBinding != null) {
                    addProviderFromModel(usedProviders, tierBinding.getModel());
                }
            }
        }
        return usedProviders;
    }

    private void addProviderFromModel(Set<String> usedProviders, String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        String resolvedProvider = modelSelectionService.resolveProviderForModel(model);
        if (resolvedProvider != null) {
            usedProviders.add(resolvedProvider);
            return;
        }
        int delimiterIndex = model.indexOf('/');
        if (delimiterIndex <= 0) {
            return;
        }
        usedProviders.add(model.substring(0, delimiterIndex));
    }

    private String normalizeProvider(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (LEGACY_STT_PROVIDER_ELEVENLABS.equals(normalized)) {
            return STT_PROVIDER_ELEVENLABS;
        }
        if (LEGACY_STT_PROVIDER_WHISPER.equals(normalized)) {
            return STT_PROVIDER_WHISPER;
        }
        return normalized;
    }

    private boolean isKnownSttProvider(String providerId) {
        return providerId != null && sttProviderRegistry.find(providerId).isPresent();
    }

    private boolean isKnownTtsProvider(String providerId) {
        return providerId != null && ttsProviderRegistry.find(providerId).isPresent();
    }

    private String firstLoadedSttProvider() {
        return sttProviderRegistry.listProviderIds().keySet().stream().findFirst().orElse(null);
    }

    private String firstLoadedTtsProvider() {
        return ttsProviderRegistry.listProviderIds().keySet().stream().findFirst().orElse(null);
    }

    private boolean isValidHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return host != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (URISyntaxException ignored) {
            return false;
        }
    }
}
