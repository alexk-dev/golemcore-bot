package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Application-level runtime settings that override BotProperties defaults.
 * Persisted to {@code preferences/runtime-config.json} via StoragePort.
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
    private RagConfig rag = new RagConfig();

    @Builder.Default
    private McpConfig mcp = new McpConfig();

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
    public static class ModelRouterConfig {
        private Double temperature;
        private String routingModel;
        private String routingModelReasoning;
        private String balancedModel;
        private String balancedModelReasoning;
        private String smartModel;
        private String smartModelReasoning;
        private String codingModel;
        private String codingModelReasoning;
        private String deepModel;
        private String deepModelReasoning;
        private Boolean dynamicTierEnabled;
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
    public static class ToolsConfig {
        private Boolean browserEnabled;
        private String browserType;
        private Integer browserTimeout;
        private String browserUserAgent;
        private Boolean filesystemEnabled;
        private Boolean shellEnabled;
        private String browserApiProvider;
        private Boolean browserHeadless;
        private Boolean braveSearchEnabled;
        private Secret braveSearchApiKey;
        private Boolean skillManagementEnabled;
        private Boolean skillTransitionEnabled;
        private Boolean tierEnabled;
        private Boolean goalManagementEnabled;
        @Builder.Default
        private ImapConfig imap = new ImapConfig();
        @Builder.Default
        private SmtpConfig smtp = new SmtpConfig();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ImapConfig {
        private Boolean enabled;
        private String host;
        private Integer port;
        private String username;
        private Secret password;
        private String security;
        private String sslTrust;
        private Integer connectTimeout;
        private Integer readTimeout;
        private Integer maxBodyLength;
        private Integer defaultMessageLimit;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SmtpConfig {
        private Boolean enabled;
        private String host;
        private Integer port;
        private String username;
        private Secret password;
        private String security;
        private String sslTrust;
        private Integer connectTimeout;
        private Integer readTimeout;
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
        private Boolean notifyMilestones;
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
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MemoryConfig {
        @Builder.Default
        private Boolean enabled = true;
        @Builder.Default
        private Integer recentDays = 7;
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
    public static class RagConfig {
        private Boolean enabled;
        private String url;
        private Secret apiKey;
        private String queryMode;
        private Integer timeoutSeconds;
        private Integer indexMinLength;
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
                        "auto-mode",
                        AutoModeConfig.class), RATE_LIMIT("rate-limit", RateLimitConfig.class), SECURITY("security",
                                SecurityConfig.class), COMPACTION("compaction", CompactionConfig.class), TURN("turn",
                                        TurnConfig.class), MEMORY("memory", MemoryConfig.class), SKILLS("skills",
                                                SkillsConfig.class), USAGE("usage", UsageConfig.class), RAG("rag",
                                                        RagConfig.class), MCP("mcp", McpConfig.class);

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
