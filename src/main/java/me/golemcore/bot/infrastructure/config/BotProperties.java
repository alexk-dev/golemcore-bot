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
 * <li>{@link ModelRouterProperties} - skill routing and model selection</li>
 * <li>{@link ToolsProperties} - tool enablement and configuration</li>
 * <li>{@link McpClientProperties} - MCP client settings</li>
 * <li>{@link RagProperties} - RAG integration</li>
 * <li>{@link AutoModeProperties} - autonomous mode settings</li>
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
    private StreamingProperties streaming = new StreamingProperties();
    private RateLimitProperties rateLimit = new RateLimitProperties();
    private UsageProperties usage = new UsageProperties();
    private VoiceProperties voice = new VoiceProperties();
    private ModelRouterProperties router = new ModelRouterProperties();
    private ToolsProperties tools = new ToolsProperties();
    private McpClientProperties mcp = new McpClientProperties();
    private RagProperties rag = new RagProperties();
    private AutoModeProperties auto = new AutoModeProperties();
    private PromptsProperties prompts = new PromptsProperties();
    private AutoCompactProperties autoCompact = new AutoCompactProperties();

    @Data
    public static class AgentProperties {
        private int maxIterations = 20;
    }

    @Data
    public static class LlmProperties {
        private String provider = "langchain4j";
        private Langchain4jProperties langchain4j = new Langchain4jProperties();
        private CustomLlmProperties custom = new CustomLlmProperties();
        private Map<String, ModelConfig> models = new HashMap<>(); // Model configurations
    }

    @Data
    public static class ModelConfig {
        private String provider; // openai, anthropic, etc.
        private boolean reasoningSupported; // Can use reasoning
        private boolean reasoningRequired; // Must specify reasoning (gpt-5.1)
        private boolean supportsTemperature = true;
        private Integer maxTokens;
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
    public static class CustomLlmProperties {
        private String apiUrl;
        private String apiKey;
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
        private String preferences = "preferences";
    }

    @Data
    public static class MemoryProperties {
        private boolean enabled = true;
        private String directory = "memory";
        private int recentDays = 7;
    }

    @Data
    public static class SkillsProperties {
        private boolean enabled = true;
        private String directory = "skills";
        private String workspacePath = "workspace/skills";
        private String builtinPath = "classpath:skills/";
        private boolean progressiveLoading = true;
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
        private String type = "playwright";
        private boolean headless = true;
        private int timeout = 30000;
        private String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
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
    public static class StreamingProperties {
        private boolean enabled = true;
        private long chunkDelayMs = 50;
        private boolean typingIndicator = true;
    }

    @Data
    public static class RateLimitProperties {
        private boolean enabled = true;
        private UserRateLimitProperties user = new UserRateLimitProperties();
        private ChannelRateLimitProperties channel = new ChannelRateLimitProperties();
        private LlmRateLimitProperties llm = new LlmRateLimitProperties();
    }

    @Data
    public static class UserRateLimitProperties {
        private int requestsPerMinute = 20;
        private int requestsPerHour = 100;
        private int requestsPerDay = 500;
    }

    @Data
    public static class ChannelRateLimitProperties {
        private int messagesPerSecond = 30;
    }

    @Data
    public static class LlmRateLimitProperties {
        private int requestsPerMinute = 60;
    }

    @Data
    public static class UsageProperties {
        private boolean enabled = true;
        private String directory = "usage";
        private String exportInterval = "PT1H";
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
        private boolean transcribeIncoming = true;
    }

    // ==================== SKILL ROUTING ====================

    /**
     * Model router configuration for intelligent model selection.
     * <p>
     * Model tiers (selected by SkillRoutingSystem based on task complexity):
     * <ul>
     * <li><b>balanced</b> - Standard tasks: general questions, greetings,
     * summarization, basic coding (default/fallback)</li>
     * <li><b>smart</b> - Complex tasks: architecture decisions, analysis,
     * multi-step reasoning</li>
     * <li><b>coding</b> - Programming tasks: code generation, debugging,
     * refactoring</li>
     * <li><b>deep</b> - PhD-level reasoning: mathematical proofs, scientific
     * analysis, architectural planning before coding</li>
     * </ul>
     * <p>
     * Reasoning effort (low/medium/high) - required for gpt-5.1, controls thinking
     * depth.
     */
    @Data
    public static class ModelRouterProperties {
        private static final String DEFAULT_GPT_5_1_MODEL = "openai/gpt-5.1";

        /** Temperature for models that support it (0.0-2.0) */
        private double temperature = 0.7;

        /**
         * Standard tasks: general questions, summarization, basic coding (used as
         * fallback)
         */
        private String defaultModel = DEFAULT_GPT_5_1_MODEL;
        private String defaultModelReasoning = "medium";

        /** Complex tasks: architecture, deep analysis, multi-step reasoning */
        private String smartModel = DEFAULT_GPT_5_1_MODEL;
        private String smartModelReasoning = "high";

        /** Coding tasks: code generation, debugging, refactoring, code review */
        private String codingModel = "openai/gpt-5.2";
        private String codingModelReasoning = "medium";

        /** PhD-level reasoning: proofs, scientific analysis, deep calculations */
        private String deepModel = "openai/gpt-5.2";
        private String deepModelReasoning = "xhigh";

        /**
         * Dynamically upgrade model tier to "coding" when coding activity is detected
         * mid-conversation
         */
        private boolean dynamicTierEnabled = true;

        private SkillMatcherProperties skillMatcher = new SkillMatcherProperties();
    }

    @Data
    public static class SkillMatcherProperties {
        private boolean enabled = false;

        // Embedding settings
        private EmbeddingProperties embedding = new EmbeddingProperties();

        // Semantic search settings
        private SemanticSearchProperties semanticSearch = new SemanticSearchProperties();

        // LLM classifier settings
        private ClassifierProperties classifier = new ClassifierProperties();

        // Caching
        private CacheProperties cache = new CacheProperties();

        // Thresholds
        private double skipClassifierThreshold = 0.95; // Skip LLM if semantic score > this
        private double minSemanticScore = 0.6; // Min score to consider a candidate

        // Overall routing timeout (must be > classifier timeout + embedding time)
        private long routingTimeoutMs = 15000;
    }

    @Data
    public static class EmbeddingProperties {
        private String provider = "openai";
        private String model = "text-embedding-3-small";
        private int dimension = 1536;
    }

    @Data
    public static class SemanticSearchProperties {
        private int topK = 5;
        private double minScore = 0.6;
    }

    @Data
    public static class ClassifierProperties {
        private boolean enabled = true;
        private String model = ModelRouterProperties.DEFAULT_GPT_5_1_MODEL; // Fast model for classification
        private String modelReasoning = "low"; // Low reasoning for speed
        private double temperature = 0.1; // Low for consistency
        private int maxTokens = 200;
        private long timeoutMs = 10000;
    }

    @Data
    public static class CacheProperties {
        private boolean enabled = true;
        private int ttlMinutes = 60;
        private int maxSize = 1000;
    }

    // ==================== TOOLS ====================

    @Data
    public static class ToolsProperties {
        private FileSystemToolProperties filesystem = new FileSystemToolProperties();
        private ShellToolProperties shell = new ShellToolProperties();
        private SkillManagementToolProperties skillManagement = new SkillManagementToolProperties();
        private BraveSearchToolProperties braveSearch = new BraveSearchToolProperties();
        private GoalManagementToolProperties goalManagement = new GoalManagementToolProperties();
        private SkillTransitionToolProperties skillTransition = new SkillTransitionToolProperties();
        private ImapToolProperties imap = new ImapToolProperties();
        private SmtpToolProperties smtp = new SmtpToolProperties();
    }

    @Data
    public static class GoalManagementToolProperties {
        private boolean enabled = true;
    }

    @Data
    public static class FileSystemToolProperties {
        private boolean enabled = true;
        private String workspace = "${user.home}/.golemcore/sandbox";
    }

    @Data
    public static class ShellToolProperties {
        private boolean enabled = true;
        private String workspace = "${user.home}/.golemcore/sandbox";
        private int defaultTimeout = 30;
        private int maxTimeout = 300;
        private String allowedEnvVars = "";
    }

    @Data
    public static class SkillManagementToolProperties {
        private boolean enabled = true;
    }

    @Data
    public static class BraveSearchToolProperties {
        private boolean enabled = false;
        private String apiKey = "";
        private int defaultCount = 5;
    }

    @Data
    public static class SkillTransitionToolProperties {
        private boolean enabled = true;
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

    @Data
    public static class RagProperties {
        private boolean enabled = false;
        private String url = "http://localhost:9621";
        private String apiKey = "";
        private String queryMode = "hybrid";
        private int maxContextTokens = 2000;
        private int timeoutSeconds = 10;
        private int indexMinLength = 50;
    }

    // ==================== AUTO MODE ====================

    @Data
    public static class AutoModeProperties {
        private boolean enabled = false;
        private int tickIntervalSeconds = 30;
        private int taskTimeoutMinutes = 10;
        private boolean autoStart = true;
        private int maxGoals = 3;
        private String modelTier = "default";
        private boolean notifyMilestones = true;
        private int maxDiaryEntriesInContext = 10;
        private int maxTasksPerGoal = 20;
    }

    // ==================== AUTO COMPACT ====================

    @Data
    public static class AutoCompactProperties {
        private boolean enabled = true;
        private int maxContextTokens = 50000;
        private int keepLastMessages = 20;
        private int systemPromptOverheadTokens = 8000;
        private double charsPerToken = 3.5;
        /**
         * Max characters allowed in a single tool result message. Longer results are
         * truncated.
         */
        private int maxToolResultChars = 100000;
    }

    // ==================== PROMPTS (system prompt sections) ====================

    @Data
    public static class PromptsProperties {
        private boolean enabled = true;
        private String botName = "AI Assistant";
        private Map<String, String> customVars = new HashMap<>();
    }
}
