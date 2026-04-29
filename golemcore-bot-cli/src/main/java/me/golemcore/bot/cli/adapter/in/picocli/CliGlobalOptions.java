package me.golemcore.bot.cli.adapter.in.picocli;

import java.util.ArrayList;
import java.util.List;
import me.golemcore.bot.cli.config.CliAttachMode;
import me.golemcore.bot.domain.cli.CliOutputFormat;
import me.golemcore.bot.domain.cli.CliPermissionMode;
import picocli.CommandLine.Option;

public final class CliGlobalOptions {

    private static final String DEFAULT_PROFILE = "default";
    private static final String DEFAULT_COLOR = "auto";
    private static final CliOutputFormat DEFAULT_FORMAT = CliOutputFormat.TEXT;
    private static final CliPermissionMode DEFAULT_PERMISSION_MODE = CliPermissionMode.ASK;
    private static final CliAttachMode DEFAULT_ATTACH_MODE = CliAttachMode.AUTO;
    private static final String DEFAULT_HOSTNAME = "127.0.0.1";

    @Option(names = "--cwd", description = "Working directory for command.")
    private String cwd;

    @Option(names = "--project", description = "Explicit project root.")
    private String project;

    @Option(names = "--workspace", description = "GolemCore storage workspace.")
    private String workspace;

    @Option(names = "--config", description = "Runtime config file.")
    private String config;

    @Option(names = "--config-dir", description = "Config directory.")
    private String configDir;

    @Option(names = "--profile", description = "Runtime/profile selection.")
    private String profile;

    @Option(names = "--env-file", description = "Load provider/API env vars.")
    private String envFile;

    @Option(names = { "-m", "--model" }, description = "Explicit model override.")
    private String model;

    @Option(names = "--tier", description = "Model tier preference.")
    private String tier;

    @Option(names = { "-a", "--agent" }, description = "Agent profile.")
    private String agent;

    @Option(names = { "-s", "--session" }, description = "Session id.")
    private String session;

    @Option(names = { "-c", "--continue" }, description = "Continue latest session for project.")
    private boolean continueLatest;

    @Option(names = "--fork", description = "Fork existing session.")
    private String fork;

    @Option(names = "--format", converter = CliOutputFormatConverter.class, description = "Output format: ${COMPLETION-CANDIDATES}.")
    private CliOutputFormat format;

    @Option(names = "--json", description = "Alias for --format json.")
    private boolean json;

    @Option(names = "--no-color", description = "Disable ANSI colors.")
    private boolean noColor;

    @Option(names = "--color", description = "Color policy: auto, always, never.")
    private String color;

    @Option(names = "--quiet", description = "Suppress non-essential output.")
    private boolean quiet;

    @Option(names = "--verbose", description = "Verbose diagnostics.")
    private boolean verbose;

    @Option(names = "--log-level", description = "Logging level.")
    private String logLevel;

    @Option(names = "--trace", description = "Enable/print trace id and trace collection.")
    private boolean trace;

    @Option(names = "--trace-export", description = "Export trace after run.")
    private String traceExport;

    @Option(names = "--no-memory", description = "Disable memory read/write for this run.")
    private boolean noMemory;

    @Option(names = "--no-rag", description = "Disable RAG retrieval/indexing for this run.")
    private boolean noRag;

    @Option(names = "--no-mcp", description = "Disable MCP tools for this run.")
    private boolean noMcp;

    @Option(names = "--no-skills", description = "Disable skills for this run.")
    private boolean noSkills;

    @Option(names = "--permission-mode", converter = CliPermissionModeConverter.class, description = "Permission preset.")
    private CliPermissionMode permissionMode;

    @Option(names = { "--yes", "-y" }, description = "Auto-confirm safe prompts only.")
    private boolean yes;

    @Option(names = "--no-input", description = "Never prompt; fail if input required.")
    private boolean noInput;

    @Option(names = "--timeout", description = "Run timeout.")
    private String timeout;

    @Option(names = "--max-llm-calls", description = "Budget override.")
    private Integer maxLlmCalls;

    @Option(names = "--max-tool-executions", description = "Budget override.")
    private Integer maxToolExecutions;

    @Option(names = "--attach", arity = "0..1", parameterConsumer = CliAttachModeParameterConsumer.class, description = "Use existing server.")
    private CliAttachMode attach;

    @Option(names = "--port", description = "Server/TUI attach port.")
    private Integer port;

    @Option(names = "--hostname", description = "Server host.")
    private String hostname;

    @Option(names = { "-J", "--java-option" }, description = "Forward JVM option.", arity = "1")
    private final List<String> javaOptions = new ArrayList<>();

    public String cwd() {
        return cwd;
    }

    public String project() {
        return project;
    }

    public String workspace() {
        return workspace;
    }

    public String config() {
        return config;
    }

    public String configDir() {
        return configDir;
    }

    public String profile() {
        return valueOrDefault(profile, DEFAULT_PROFILE);
    }

    public String envFile() {
        return envFile;
    }

    public String model() {
        return model;
    }

    public String tier() {
        return tier;
    }

    public String agent() {
        return agent;
    }

    public String session() {
        return session;
    }

    public boolean continueLatest() {
        return continueLatest;
    }

    public String fork() {
        return fork;
    }

    public CliOutputFormat format() {
        return effectiveFormat();
    }

    public boolean json() {
        return json;
    }

    public boolean noColor() {
        return noColor;
    }

    public String color() {
        return valueOrDefault(color, DEFAULT_COLOR);
    }

    public boolean quiet() {
        return quiet;
    }

    public boolean verbose() {
        return verbose;
    }

    public String logLevel() {
        return logLevel;
    }

    public boolean trace() {
        return trace;
    }

    public String traceExport() {
        return traceExport;
    }

    public boolean noMemory() {
        return noMemory;
    }

    public boolean noRag() {
        return noRag;
    }

    public boolean noMcp() {
        return noMcp;
    }

    public boolean noSkills() {
        return noSkills;
    }

    public CliPermissionMode permissionMode() {
        return valueOrDefault(permissionMode, DEFAULT_PERMISSION_MODE);
    }

    public boolean yes() {
        return yes;
    }

    public boolean noInput() {
        return noInput;
    }

    public String timeout() {
        return timeout;
    }

    public Integer maxLlmCalls() {
        return maxLlmCalls;
    }

    public Integer maxToolExecutions() {
        return maxToolExecutions;
    }

    public CliAttachMode attach() {
        return valueOrDefault(attach, DEFAULT_ATTACH_MODE);
    }

    public Integer port() {
        return port;
    }

    public String hostname() {
        return valueOrDefault(hostname, DEFAULT_HOSTNAME);
    }

    public List<String> javaOptions() {
        return List.copyOf(javaOptions);
    }

    public CliOutputFormat effectiveFormat() {
        return json ? CliOutputFormat.JSON : valueOrDefault(format, DEFAULT_FORMAT);
    }

    CliGlobalOptions merge(CliGlobalOptions override) {
        CliGlobalOptions merged = new CliGlobalOptions();
        merged.cwd = firstNonNull(override.cwd, cwd);
        merged.project = firstNonNull(override.project, project);
        merged.workspace = firstNonNull(override.workspace, workspace);
        merged.config = firstNonNull(override.config, config);
        merged.configDir = firstNonNull(override.configDir, configDir);
        merged.profile = firstNonNull(override.profile, profile);
        merged.envFile = firstNonNull(override.envFile, envFile);
        merged.model = firstNonNull(override.model, model);
        merged.tier = firstNonNull(override.tier, tier);
        merged.agent = firstNonNull(override.agent, agent);
        merged.session = firstNonNull(override.session, session);
        merged.continueLatest = override.continueLatest || continueLatest;
        merged.fork = firstNonNull(override.fork, fork);
        merged.format = firstNonNull(override.format, format);
        merged.json = override.json || json;
        merged.noColor = override.noColor || noColor;
        merged.color = firstNonNull(override.color, color);
        merged.quiet = override.quiet || quiet;
        merged.verbose = override.verbose || verbose;
        merged.logLevel = firstNonNull(override.logLevel, logLevel);
        merged.trace = override.trace || trace;
        merged.traceExport = firstNonNull(override.traceExport, traceExport);
        merged.noMemory = override.noMemory || noMemory;
        merged.noRag = override.noRag || noRag;
        merged.noMcp = override.noMcp || noMcp;
        merged.noSkills = override.noSkills || noSkills;
        merged.permissionMode = firstNonNull(override.permissionMode, permissionMode);
        merged.yes = override.yes || yes;
        merged.noInput = override.noInput || noInput;
        merged.timeout = firstNonNull(override.timeout, timeout);
        merged.maxLlmCalls = firstNonNull(override.maxLlmCalls, maxLlmCalls);
        merged.maxToolExecutions = firstNonNull(override.maxToolExecutions, maxToolExecutions);
        merged.attach = firstNonNull(override.attach, attach);
        merged.port = firstNonNull(override.port, port);
        merged.hostname = firstNonNull(override.hostname, hostname);
        merged.javaOptions.addAll(javaOptions);
        merged.javaOptions.addAll(override.javaOptions);
        return merged;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static <T> T valueOrDefault(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static <T> T firstNonNull(T primary, T fallback) {
        return primary == null ? fallback : primary;
    }
}
