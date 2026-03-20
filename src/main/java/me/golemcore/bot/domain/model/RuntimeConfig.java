package me.golemcore.bot.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Application-level runtime settings persisted as per-section files under
 * {@code preferences/}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuntimeConfig {

    @Builder.Default
    private TelegramConfig telegram = new TelegramConfig();

    @Builder.Default
    private ModelRouterConfig modelRouter = new ModelRouterConfig();

    @Builder.Default
    private LlmConfig llm = new LlmConfig();

    @Builder.Default
    private ToolsConfig tools = new ToolsConfig();

    @Builder.Default
    private VoiceConfig voice = new VoiceConfig();

    @Builder.Default
    private AutoModeConfig autoMode = new AutoModeConfig();

    @Builder.Default
    private UpdateConfig update = new UpdateConfig();

    @Builder.Default
    private RateLimitConfig rateLimit = new RateLimitConfig();

    @Builder.Default
    private SecurityConfig security = new SecurityConfig();

    @Builder.Default
    private CompactionConfig compaction = new CompactionConfig();

    @Builder.Default
    private TurnConfig turn = new TurnConfig();

    @Builder.Default
    private MemoryConfig memory = new MemoryConfig();

    @Builder.Default
    private SkillsConfig skills = new SkillsConfig();

    @Builder.Default
    private UsageConfig usage = new UsageConfig();

    @Builder.Default
    private McpConfig mcp = new McpConfig();

    @Builder.Default
    private PlanConfig plan = new PlanConfig();

    @Builder.Default
    private DelayedActionsConfig delayedActions = new DelayedActionsConfig();

    @Builder.Default
    private HiveConfig hive = new HiveConfig();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TelegramConfig {
        private Boolean enabled;
        private Secret token;
        /** Invite authentication flow mode. */
        @Builder.Default
        private String authMode = "invite_only";
        @Builder.Default
        private List<String> allowedUsers = new ArrayList<>();
        @Builder.Default
        private List<InviteCode> inviteCodes = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class InviteCode {
        private String code;
        private boolean used;
        private Instant createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({ "temperature", "routing", "tiers", "dynamicTierEnabled" })
    public static class ModelRouterConfig {
        private Double temperature;
        private TierBinding routing;
        private LinkedHashMap<String, TierBinding> tiers = new LinkedHashMap<>();
        private Boolean dynamicTierEnabled;

        public static class ModelRouterConfigBuilder {
            private TierBinding routing;
            private LinkedHashMap<String, TierBinding> tiers;

            public ModelRouterConfigBuilder routingModel(String model) {
                routingBinding().setModel(model);
                return this;
            }

            public ModelRouterConfigBuilder routingModelReasoning(String reasoning) {
                routingBinding().setReasoning(reasoning);
                return this;
            }

            public ModelRouterConfigBuilder balancedModel(String model) {
                tierBinding("balanced").setModel(model);
                return this;
            }

            public ModelRouterConfigBuilder balancedModelReasoning(String reasoning) {
                tierBinding("balanced").setReasoning(reasoning);
                return this;
            }

            public ModelRouterConfigBuilder smartModel(String model) {
                tierBinding("smart").setModel(model);
                return this;
            }

            public ModelRouterConfigBuilder smartModelReasoning(String reasoning) {
                tierBinding("smart").setReasoning(reasoning);
                return this;
            }

            public ModelRouterConfigBuilder deepModel(String model) {
                tierBinding("deep").setModel(model);
                return this;
            }

            public ModelRouterConfigBuilder deepModelReasoning(String reasoning) {
                tierBinding("deep").setReasoning(reasoning);
                return this;
            }

            public ModelRouterConfigBuilder codingModel(String model) {
                tierBinding("coding").setModel(model);
                return this;
            }

            public ModelRouterConfigBuilder codingModelReasoning(String reasoning) {
                tierBinding("coding").setReasoning(reasoning);
                return this;
            }

            private TierBinding routingBinding() {
                if (this.routing == null) {
                    this.routing = new TierBinding();
                }
                return this.routing;
            }

            private TierBinding tierBinding(String tier) {
                if (this.tiers == null) {
                    this.tiers = new LinkedHashMap<>();
                }
                return this.tiers.computeIfAbsent(tier, ignored -> new TierBinding());
            }
        }

        public TierBinding getTierBinding(String tier) {
            if (tiers == null || tier == null) {
                return null;
            }
            return tiers.get(tier);
        }

        public void setTierBinding(String tier, TierBinding binding) {
            if (tier == null) {
                return;
            }
            if (tiers == null) {
                tiers = new LinkedHashMap<>();
            }
            tiers.put(tier, binding);
        }

        public String getTierModel(String tier) {
            TierBinding binding = getTierBinding(tier);
            return binding != null ? binding.getModel() : null;
        }

        public void setTierModel(String tier, String model) {
            TierBinding binding = ensureTierBinding(tier);
            binding.setModel(model);
        }

        public String getTierReasoning(String tier) {
            TierBinding binding = getTierBinding(tier);
            return binding != null ? binding.getReasoning() : null;
        }

        public void setTierReasoning(String tier, String reasoning) {
            TierBinding binding = ensureTierBinding(tier);
            binding.setReasoning(reasoning);
        }

        private TierBinding ensureTierBinding(String tier) {
            if (tiers == null) {
                tiers = new LinkedHashMap<>();
            }
            return tiers.computeIfAbsent(tier, ignored -> new TierBinding());
        }

        private TierBinding ensureRoutingBinding() {
            if (routing == null) {
                routing = new TierBinding();
            }
            return routing;
        }

        @JsonIgnore
        public String getRoutingModel() {
            return routing != null ? routing.getModel() : null;
        }

        @JsonSetter("routingModel")
        public void setRoutingModel(String model) {
            ensureRoutingBinding().setModel(model);
        }

        @JsonIgnore
        public String getRoutingModelReasoning() {
            return routing != null ? routing.getReasoning() : null;
        }

        @JsonSetter("routingModelReasoning")
        public void setRoutingModelReasoning(String reasoning) {
            ensureRoutingBinding().setReasoning(reasoning);
        }

        @JsonIgnore
        public String getBalancedModel() {
            return getTierModel("balanced");
        }

        @JsonSetter("balancedModel")
        public void setBalancedModel(String model) {
            setTierModel("balanced", model);
        }

        @JsonIgnore
        public String getBalancedModelReasoning() {
            return getTierReasoning("balanced");
        }

        @JsonSetter("balancedModelReasoning")
        public void setBalancedModelReasoning(String reasoning) {
            setTierReasoning("balanced", reasoning);
        }

        @JsonIgnore
        public String getSmartModel() {
            return getTierModel("smart");
        }

        @JsonSetter("smartModel")
        public void setSmartModel(String model) {
            setTierModel("smart", model);
        }

        @JsonIgnore
        public String getSmartModelReasoning() {
            return getTierReasoning("smart");
        }

        @JsonSetter("smartModelReasoning")
        public void setSmartModelReasoning(String reasoning) {
            setTierReasoning("smart", reasoning);
        }

        @JsonIgnore
        public String getDeepModel() {
            return getTierModel("deep");
        }

        @JsonSetter("deepModel")
        public void setDeepModel(String model) {
            setTierModel("deep", model);
        }

        @JsonIgnore
        public String getDeepModelReasoning() {
            return getTierReasoning("deep");
        }

        @JsonSetter("deepModelReasoning")
        public void setDeepModelReasoning(String reasoning) {
            setTierReasoning("deep", reasoning);
        }

        @JsonIgnore
        public String getCodingModel() {
            return getTierModel("coding");
        }

        @JsonSetter("codingModel")
        public void setCodingModel(String model) {
            setTierModel("coding", model);
        }

        @JsonIgnore
        public String getCodingModelReasoning() {
            return getTierReasoning("coding");
        }

        @JsonSetter("codingModelReasoning")
        public void setCodingModelReasoning(String reasoning) {
            setTierReasoning("coding", reasoning);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({ "model", "reasoning" })
    public static class TierBinding {
        private String model;
        private String reasoning;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmConfig {
        @Builder.Default
        private Map<String, LlmProviderConfig> providers = new LinkedHashMap<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LlmProviderConfig {
        private Secret apiKey;
        private String baseUrl;
        private Integer requestTimeoutSeconds;
        private String apiType;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolsConfig {
        private Boolean filesystemEnabled;
        private Boolean shellEnabled;
        private Boolean skillManagementEnabled;
        private Boolean skillTransitionEnabled;
        private Boolean tierEnabled;
        private Boolean goalManagementEnabled;
        @Builder.Default
        private List<ShellEnvironmentVariable> shellEnvironmentVariables = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ShellEnvironmentVariable {
        private String name;
        private String value;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class VoiceConfig {
        private Boolean enabled;
        private Secret apiKey;
        private String voiceId;
        private String ttsModelId;
        private String sttModelId;
        private Float speed;
        private Boolean telegramRespondWithVoice;
        private Boolean telegramTranscribeIncoming;
        /** STT provider: "elevenlabs" (default) or "whisper" */
        private String sttProvider;
        /** TTS provider: currently only "elevenlabs" */
        private String ttsProvider;
        /** Base URL for Whisper-compatible STT server, e.g. "http://parakeet:5092" */
        private String whisperSttUrl;
        /** Optional API key for Whisper-compatible STT (e.g. OpenAI) */
        private Secret whisperSttApiKey;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AutoModeConfig {
        private Boolean enabled;
        private Integer tickIntervalSeconds;
        private Integer taskTimeLimitMinutes;
        private Boolean autoStart;
        private Integer maxGoals;
        private String modelTier;
        @Builder.Default
        private Boolean reflectionEnabled = true;
        @Builder.Default
        private Integer reflectionFailureThreshold = 2;
        private String reflectionModelTier;
        private Boolean reflectionTierPriority;
        private Boolean notifyMilestones;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UpdateConfig {
        @Builder.Default
        private Boolean autoEnabled = true;
        @Builder.Default
        private Integer checkIntervalMinutes = 60;
        @Builder.Default
        private Boolean maintenanceWindowEnabled = false;
        @Builder.Default
        private String maintenanceWindowStartUtc = "00:00";
        @Builder.Default
        private String maintenanceWindowEndUtc = "00:00";
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RateLimitConfig {
        private Boolean enabled;
        private Integer userRequestsPerMinute;
        private Integer userRequestsPerHour;
        private Integer userRequestsPerDay;
        private Integer channelMessagesPerSecond;
        private Integer llmRequestsPerMinute;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SecurityConfig {
        private Boolean sanitizeInput;
        private Boolean detectPromptInjection;
        private Boolean detectCommandInjection;
        private Integer maxInputLength;
        private Boolean allowlistEnabled;
        private Boolean toolConfirmationEnabled;
        private Integer toolConfirmationTimeoutSeconds;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CompactionConfig {
        private Boolean enabled;
        private Integer maxContextTokens;
        private Integer keepLastMessages;
        @Builder.Default
        private String triggerMode = "model_ratio";
        @Builder.Default
        private Double modelThresholdRatio = 0.95d;
        @Builder.Default
        private Boolean preserveTurnBoundaries = true;
        @Builder.Default
        private Boolean detailsEnabled = true;
        @Builder.Default
        private Integer detailsMaxItemsPerCategory = 50;
        @Builder.Default
        private Integer summaryTimeoutMs = 15000;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TurnConfig {
        @Builder.Default
        private Integer maxLlmCalls = 200;
        @Builder.Default
        private Integer maxToolExecutions = 500;
        @Builder.Default
        private String deadline = "PT1H";
        @Builder.Default
        private Boolean autoRetryEnabled = true;
        @Builder.Default
        private Integer autoRetryMaxAttempts = 2;
        @Builder.Default
        private Long autoRetryBaseDelayMs = 600L;
        @Builder.Default
        private Boolean queueSteeringEnabled = true;
        @Builder.Default
        private String queueSteeringMode = "one-at-a-time";
        @Builder.Default
        private String queueFollowUpMode = "one-at-a-time";
        @Builder.Default
        private Boolean progressUpdatesEnabled = true;
        @Builder.Default
        private Boolean progressIntentEnabled = true;
        @Builder.Default
        private Integer progressBatchSize = 8;
        @Builder.Default
        private Integer progressMaxSilenceSeconds = 10;
        @Builder.Default
        private Integer progressSummaryTimeoutMs = 8000;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MemoryConfig {
        @Builder.Default
        private Integer version = 2;
        @Builder.Default
        private Boolean enabled = true;
        @Builder.Default
        private Integer softPromptBudgetTokens = 1800;
        @Builder.Default
        private Integer maxPromptBudgetTokens = 3500;
        @Builder.Default
        private Integer workingTopK = 6;
        @Builder.Default
        private Integer episodicTopK = 8;
        @Builder.Default
        private Integer semanticTopK = 6;
        @Builder.Default
        private Integer proceduralTopK = 4;
        @Builder.Default
        private Boolean promotionEnabled = true;
        @Builder.Default
        private Double promotionMinConfidence = 0.75;
        @Builder.Default
        private Boolean decayEnabled = true;
        @Builder.Default
        private Integer decayDays = 30;
        @Builder.Default
        private Integer retrievalLookbackDays = 21;
        @Builder.Default
        private Boolean codeAwareExtractionEnabled = true;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SkillsConfig {
        @Builder.Default
        private Boolean enabled = true;
        @Builder.Default
        private Boolean progressiveLoading = true;
        private String marketplaceSourceType;
        private String marketplaceRepositoryDirectory;
        private String marketplaceSandboxPath;
        private String marketplaceRepositoryUrl;
        private String marketplaceBranch;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UsageConfig {
        private Boolean enabled;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class McpConfig {
        private Boolean enabled;
        private Integer defaultStartupTimeout;
        private Integer defaultIdleTimeout;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HiveConfig {
        @Builder.Default
        private Boolean enabled = false;
        private String serverUrl;
        private String displayName;
        private String hostLabel;
        @Builder.Default
        private Boolean autoConnect = false;
        @Builder.Default
        private Boolean managedByProperties = false;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PlanConfig {
        @Builder.Default
        private Boolean enabled = false;
        @Builder.Default
        private Integer maxPlans = 5;
        @Builder.Default
        private Integer maxStepsPerPlan = 50;
        @Builder.Default
        private Boolean stopOnFailure = true;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DelayedActionsConfig {
        @Builder.Default
        private Boolean enabled = true;
        @Builder.Default
        private Integer tickSeconds = 1;
        @Builder.Default
        private Integer maxPendingPerSession = 50;
        @Builder.Default
        private String maxDelay = "P30D";
        @Builder.Default
        private Integer defaultMaxAttempts = 4;
        @Builder.Default
        private String leaseDuration = "PT2M";
        @Builder.Default
        private String retentionAfterCompletion = "P7D";
        @Builder.Default
        private Boolean allowRunLater = true;
    }

    /**
     * Whitelist of valid configuration sections. Each section corresponds to a
     * separate JSON file in the preferences directory.
     *
     * <p>
     * File naming convention: kebab-case (e.g., model-router.json, auto-mode.json)
     */
    public enum ConfigSection {
        TELEGRAM("telegram", TelegramConfig.class), MODEL_ROUTER("model-router", ModelRouterConfig.class), LLM("llm",
                LlmConfig.class), TOOLS("tools", ToolsConfig.class), VOICE("voice", VoiceConfig.class), AUTO_MODE(
                        "auto-mode", AutoModeConfig.class), UPDATE("update", UpdateConfig.class), RATE_LIMIT(
                                "rate-limit",
                                RateLimitConfig.class), SECURITY("security", SecurityConfig.class), COMPACTION(
                                        "compaction",
                                        CompactionConfig.class), TURN("turn", TurnConfig.class), MEMORY("memory",
                                                MemoryConfig.class), SKILLS("skills", SkillsConfig.class), USAGE(
                                                        "usage",
                                                        UsageConfig.class), MCP("mcp", McpConfig.class), PLAN("plan",
                                                                PlanConfig.class), DELAYED_ACTIONS("delayed-actions",
                                                                        DelayedActionsConfig.class), HIVE("hive",
                                                                                HiveConfig.class);

        private final String fileId;
        private final Class<?> configClass;

        ConfigSection(String fileId, Class<?> configClass) {
            this.fileId = fileId;
            this.configClass = configClass;
        }

        /**
         * Get the file ID (used as filename without extension).
         */
        public String getFileId() {
            return fileId;
        }

        /**
         * Get the filename with .json extension.
         */
        public String getFileName() {
            return fileId + ".json";
        }

        /**
         * Get the configuration class for this section.
         */
        public Class<?> getConfigClass() {
            return configClass;
        }

        /**
         * Find a ConfigSection by its file ID.
         *
         * @param fileId
         *            the file ID to search for (case-insensitive)
         * @return Optional containing the matching section, or empty if not found
         */
        public static Optional<ConfigSection> fromFileId(String fileId) {
            if (fileId == null || fileId.isBlank()) {
                return Optional.empty();
            }
            String normalized = fileId.toLowerCase(Locale.ROOT).trim();
            for (ConfigSection section : values()) {
                if (section.fileId.equals(normalized)) {
                    return Optional.of(section);
                }
            }
            return Optional.empty();
        }

        /**
         * Check if the given file ID corresponds to a valid configuration section.
         *
         * @param fileId
         *            the file ID to validate
         * @return true if the file ID is in the whitelist
         */
        public static boolean isValidSection(String fileId) {
            return fromFileId(fileId).isPresent();
        }
    }
}
