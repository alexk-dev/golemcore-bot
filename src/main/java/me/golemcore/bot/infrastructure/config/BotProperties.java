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
 * <li>{@link LlmProperties} - LLM provider settings</li>
 * <li>{@link ChannelProperties} - input channels (Telegram, etc.)</li>
 * <li>{@link StorageProperties} - persistence configuration</li>
 * <li>{@link SecurityProperties} - security and access control</li>
 * <li>{@link ToolsProperties} - tool enablement and configuration</li>
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

    private LlmProperties llm = new LlmProperties();
    private Map<String, ChannelProperties> channels = new HashMap<>();
    private StorageProperties storage = new StorageProperties();
    private MemoryProperties memory = new MemoryProperties();
    private SkillsProperties skills = new SkillsProperties();
    private SecurityProperties security = new SecurityProperties();
    private HttpProperties http = new HttpProperties();
    private ToolsProperties tools = new ToolsProperties();
    private PromptsProperties prompts = new PromptsProperties();
    private AutoCompactProperties autoCompact = new AutoCompactProperties();
    private TurnProperties turn = new TurnProperties();
    private ToolLoopProperties toolLoop = new ToolLoopProperties();
    private PlanProperties plan = new PlanProperties();
    private DashboardProperties dashboard = new DashboardProperties();
    private UpdateProperties update = new UpdateProperties();

    @Data
    public static class LlmProperties {
        private String provider = "langchain4j";
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
    }

    @Data
    public static class SkillsProperties {
        private String directory = "skills";
        private String workspacePath = "workspace/skills";
        private String builtinPath = "classpath:skills/";
    }

    @Data
    public static class SecurityProperties {
    }

    @Data
    public static class HttpProperties {
        private long connectTimeout = 10000;
        private long readTimeout = 60000;
        private long writeTimeout = 60000;
        private int maxIdleConnections = 5;
        private long keepAliveDuration = 300000;
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

    // ==================== DASHBOARD ====================

    @Data
    public static class DashboardProperties {
        private boolean enabled = false;
        private String adminPasswordHash = "";
        private String jwtSecret = "";
        private int jwtExpirationMinutes = 30;
        private int refreshExpirationDays = 7;
        private String corsAllowedOrigins = "";
        private LogsProperties logs = new LogsProperties();
    }

    @Data
    public static class LogsProperties {
        private boolean enabled = true;
        private int maxEntries = 10000;
        private int defaultPageSize = 200;
        private int maxPageSize = 1000;
        private int maxMessageChars = 8000;
        private int maxExceptionChars = 16000;
    }

    // ==================== SELF-UPDATE ====================

    @Data
    public static class UpdateProperties {
        private boolean enabled = true;
        private String updatesPath = "/data/updates";
        private int maxKeptVersions = 3;
        private java.time.Duration checkInterval = java.time.Duration.ofHours(1);
        private java.time.Duration confirmTtl = java.time.Duration.ofMinutes(2);
    }

    // ==================== PROMPTS (system prompt sections) ====================

    @Data
    public static class PromptsProperties {
        private boolean enabled = true;
        private String botName = "AI Assistant";
        private Map<String, String> customVars = new HashMap<>();
    }
}
