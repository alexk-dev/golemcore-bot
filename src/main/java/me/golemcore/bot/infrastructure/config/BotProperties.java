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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralized configuration properties for the bot, bound from
 * application.properties.
 *
 * <p>
 * All bot configuration is organized under the {@code bot.*} prefix. This class
 * contains nested property classes for different subsystems:
 * <ul>
 * <li>{@link AgentProperties} - agent loop configuration</li>
 * <li>{@link LlmProperties} - LLM provider settings</li>
 * <li>{@link ChannelProperties} - input channels (Telegram, etc.)</li>
 * <li>{@link StorageProperties} - persistence configuration</li>
 * <li>{@link SecurityProperties} - security and access control</li>
 * <li>{@link ToolsProperties} - tool enablement and configuration</li>
 * <li>{@link McpClientProperties} - MCP client settings</li>
 * <li>And many more subsystems...</li>
 * </ul>
 *
 * <p>
 * Uses Spring Boot's {@link ConfigurationProperties} for type-safe property
 * binding.
 *
 * @since 1.0
 */
@Component
@ConfigurationProperties(prefix = "bot")
@Data
public class BotProperties {

    private AgentProperties agent = new AgentProperties();
    private LlmProperties llm = new LlmProperties();
    private Map<String, ChannelProperties> channels = new HashMap<>();
    private StorageProperties storage = new StorageProperties();
    private MemoryProperties memory = new MemoryProperties();
    private SkillsProperties skills = new SkillsProperties();
    private SecurityProperties security = new SecurityProperties();
    private BrowserProperties browser = new BrowserProperties();
    private HttpProperties http = new HttpProperties();
    private VoiceProperties voice = new VoiceProperties();
    private ToolsProperties tools = new ToolsProperties();
    private McpClientProperties mcp = new McpClientProperties();
    private PromptsProperties prompts = new PromptsProperties();
    private AutoCompactProperties autoCompact = new AutoCompactProperties();
    private TurnProperties turn = new TurnProperties();
    private ToolLoopProperties toolLoop = new ToolLoopProperties();
    private PlanProperties plan = new PlanProperties();
    private ModelSelectionProperties modelSelection = new ModelSelectionProperties();
    private DashboardProperties dashboard = new DashboardProperties();

    @Data
    public static class AgentProperties {
        private int maxIterations = 20;
    }

    @Data
    public static class LlmProperties {
        private String provider = "langchain4j";
        /** Canonical per-request timeout for any LLM provider adapter. */
        private java.time.Duration requestTimeout = java.time.Duration.ofSeconds(300);
        private Langchain4jProperties langchain4j = new Langchain4jProperties();
    }

    @Data
    public static class Langchain4jProperties {
        private Map<String, ProviderProperties> providers = new HashMap<>();
    }

    @Data
    public static class ProviderProperties {
        private String apiKey;
        private String baseUrl;
    }

    @Data
    public static class ChannelProperties {
        private boolean enabled = false;
        private String token;
        private List<String> allowFrom = new ArrayList<>();
    }

    @Data
    public static class StorageProperties {
        private LocalStorageProperties local = new LocalStorageProperties();
        private DirectoriesProperties directories = new DirectoriesProperties();
    }

    @Data
    public static class LocalStorageProperties {
        private String basePath = "${user.home}/.golemcore/workspace";
    }

    @Data
    public static class DirectoriesProperties {
        private String sessions = "sessions";
        private String memory = "memory";
        private String skills = "skills";
    }

    @Data
    public static class MemoryProperties {
        private String directory = "memory";
        private int recentDays = 7;
    }

    @Data
    public static class SkillsProperties {
        private String directory = "skills";
        private String workspacePath = "workspace/skills";
        private String builtinPath = "classpath:skills/";
    }

    @Data
    public static class SecurityProperties {
        private boolean sanitizeInput = true;
        private boolean detectPromptInjection = true;
        private boolean detectCommandInjection = true;
        private int maxInputLength = 10000;
        private AllowlistProperties allowlist = new AllowlistProperties();
        private ToolConfirmationProperties toolConfirmation = new ToolConfirmationProperties();
    }

    @Data
    public static class ToolConfirmationProperties {
        private boolean enabled = true;
        private int timeoutSeconds = 60;
    }

    @Data
    public static class AllowlistProperties {
        private boolean enabled = true;
        private List<String> blockedUsers = new ArrayList<>();
    }

    @Data
    public static class BrowserProperties {
        private boolean enabled = true;
    }

    @Data
    public static class HttpProperties {
        private long connectTimeout = 10000;
        private long readTimeout = 60000;
        private long writeTimeout = 60000;
        private int maxIdleConnections = 5;
        private long keepAliveDuration = 300000;
    }

    @Data
    public static class VoiceProperties {
        private boolean enabled = false;
        private String apiKey = "";
        private String voiceId = "21m00Tcm4TlvDq8ikWAM";
        private String ttsModelId = "eleven_multilingual_v2";
        private String sttModelId = "scribe_v1";
        private float speed = 1.0f;
        private TelegramVoiceProperties telegram = new TelegramVoiceProperties();
    }

    @Data
    public static class TelegramVoiceProperties {
        private boolean respondWithVoice = true;
    }

    // ==================== TOOLS ====================

    @Data
    public static class ToolsProperties {
        private FileSystemToolProperties filesystem = new FileSystemToolProperties();
        private ShellToolProperties shell = new ShellToolProperties();
        private SkillManagementToolProperties skillManagement = new SkillManagementToolProperties();
        private BraveSearchToolProperties braveSearch = new BraveSearchToolProperties();
        private SkillTransitionToolProperties skillTransition = new SkillTransitionToolProperties();
        private TierToolProperties tier = new TierToolProperties();
        private ImapToolProperties imap = new ImapToolProperties();
        private SmtpToolProperties smtp = new SmtpToolProperties();
    }

    @Data
    public static class FileSystemToolProperties {
        private String workspace = "${user.home}/.golemcore/sandbox";
    }

    @Data
    public static class ShellToolProperties {
        private String workspace = "${user.home}/.golemcore/sandbox";
        private int defaultTimeout = 30;
        private int maxTimeout = 300;
        private String allowedEnvVars = "";
    }

    @Data
    public static class SkillManagementToolProperties {
    }

    @Data
    public static class BraveSearchToolProperties {
        private int defaultCount = 5;
    }

    @Data
    public static class SkillTransitionToolProperties {
    }

    @Data
    public static class TierToolProperties {
    }

    @Data
    public static class ImapToolProperties {
        private boolean enabled = false;
        private String host = "";
        private int port = 993;
        private String username = "";
        private String password = "";
        private String security = "ssl";
        private String sslTrust = "";
        private int connectTimeout = 10000;
        private int readTimeout = 30000;
        private int maxBodyLength = 50000;
        private int defaultMessageLimit = 20;
    }

    @Data
    public static class SmtpToolProperties {
        private boolean enabled = false;
        private String host = "";
        private int port = 587;
        private String username = "";
        private String password = "";
        private String security = "starttls";
        private String sslTrust = "";
        private int connectTimeout = 10000;
        private int readTimeout = 30000;
    }

    // ==================== MCP CLIENT ====================

    @Data
    public static class McpClientProperties {
        private boolean enabled = true;
        private int defaultStartupTimeout = 30;
        private int defaultIdleTimeout = 5;
    }

    // ==================== RAG (LightRAG) ====================

    // ==================== AUTO COMPACT ====================

    @Data
    public static class AutoCompactProperties {
        /**
         * Max characters allowed in a single tool result message. Longer results are
         * truncated.
         */
        private int maxToolResultChars = 100000;
    }

    // ==================== TURN BUDGET ====================

    @Data
    public static class TurnProperties {
        /** Max number of internal LLM calls allowed within a single turn. */
        private int maxLlmCalls = 200;

        /** Max number of tool executions allowed within a single turn. */
        private int maxToolExecutions = 500;

        /** Max wall-clock time budget for a single turn. Default: 1 hour. */
        private java.time.Duration deadline = java.time.Duration.ofHours(1);
    }

    // ==================== TOOL LOOP ====================

    @Data
    public static class ToolLoopProperties {

        // Execution behavior flags that are specific to tool calling.
        // NOTE: budgets/timeouts moved to bot.turn.*.
        /**
         * If true, stop the internal loop immediately after the first tool failure
         * (ToolResult.success=false).
         */
        private boolean stopOnToolFailure = false;

        /**
         * Stop the loop when a tool execution was denied by the user (confirmation
         * declined).
         */
        private boolean stopOnConfirmationDenied = true;

        /**
         * Stop the loop when a tool was blocked by policy (disabled/unknown/etc).
         */
        private boolean stopOnToolPolicyDenied = false;
    }

    // ==================== PLAN MODE ====================

    @Data
    public static class PlanProperties {
        private boolean enabled = false;
        private int maxPlans = 5;
        private int maxStepsPerPlan = 50;
        private boolean stopOnFailure = true;
    }

    // ==================== MODEL SELECTION (user model overrides)
    // ====================

    @Data
    public static class ModelSelectionProperties {
        private List<String> allowedProviders = List.of("openai", "anthropic");
    }

    // ==================== DASHBOARD ====================

    @Data
    public static class DashboardProperties {
        private boolean enabled = false;
        private String adminPasswordHash = "";
        private String jwtSecret = "";
        private int jwtExpirationMinutes = 30;
        private int refreshExpirationDays = 7;
        private String corsAllowedOrigins = "";
    }

    // ==================== PROMPTS (system prompt sections) ====================

    @Data
    public static class PromptsProperties {
        private boolean enabled = true;
        private String botName = "AI Assistant";
        private Map<String, String> customVars = new HashMap<>();
    }
}
