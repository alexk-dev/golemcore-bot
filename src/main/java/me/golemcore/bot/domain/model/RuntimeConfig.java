package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TelegramConfig {
        private Boolean enabled;
        private String token;
        /** "allowlist" or "invite" */
        private String authMode;
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
    public static class ToolsConfig {
        private Boolean filesystemEnabled;
        private Boolean shellEnabled;
        private Boolean braveSearchEnabled;
        private String braveSearchApiKey;
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
        private String password;
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
        private String password;
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
        private String apiKey;
        private String voiceId;
        private String ttsModelId;
        private String sttModelId;
        private Float speed;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AutoModeConfig {
        private Boolean enabled;
        private Integer tickIntervalSeconds;
        private Integer taskTimeoutMinutes;
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
}
