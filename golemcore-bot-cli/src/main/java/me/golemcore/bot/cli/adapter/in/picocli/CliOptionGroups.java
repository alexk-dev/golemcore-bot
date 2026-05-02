package me.golemcore.bot.cli.adapter.in.picocli;

import me.golemcore.bot.cli.config.CliAttachMode;
import me.golemcore.bot.domain.cli.CliOutputFormat;
import me.golemcore.bot.domain.cli.CliPermissionMode;
import picocli.CommandLine.Option;

@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
final class CliProjectOptions {

    private static final String DEFAULT_PROFILE = "default";

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

    String cwd() {
        return cwd;
    }

    String project() {
        return project;
    }

    String workspace() {
        return workspace;
    }

    String config() {
        return config;
    }

    String configDir() {
        return configDir;
    }

    String profile() {
        return valueOrDefault(profile, DEFAULT_PROFILE);
    }

    String envFile() {
        return envFile;
    }

    CliProjectOptions merge(CliProjectOptions override) {
        CliProjectOptions merged = new CliProjectOptions();
        merged.cwd = firstNonNull(override.cwd, cwd);
        merged.project = firstNonNull(override.project, project);
        merged.workspace = firstNonNull(override.workspace, workspace);
        merged.config = firstNonNull(override.config, config);
        merged.configDir = firstNonNull(override.configDir, configDir);
        merged.profile = firstNonNull(override.profile, profile);
        merged.envFile = firstNonNull(override.envFile, envFile);
        return merged;
    }

    void copyFrom(CliProjectOptions source) {
        cwd = source.cwd;
        project = source.project;
        workspace = source.workspace;
        config = source.config;
        configDir = source.configDir;
        profile = source.profile;
        envFile = source.envFile;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static <T> T firstNonNull(T primary, T fallback) {
        return primary == null ? fallback : primary;
    }
}

@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
final class CliRuntimeSelectionOptions {

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

    String model() {
        return model;
    }

    String tier() {
        return tier;
    }

    String agent() {
        return agent;
    }

    String session() {
        return session;
    }

    boolean continueLatest() {
        return continueLatest;
    }

    String fork() {
        return fork;
    }

    CliRuntimeSelectionOptions merge(CliRuntimeSelectionOptions override) {
        CliRuntimeSelectionOptions merged = new CliRuntimeSelectionOptions();
        merged.model = firstNonNull(override.model, model);
        merged.tier = firstNonNull(override.tier, tier);
        merged.agent = firstNonNull(override.agent, agent);
        merged.session = firstNonNull(override.session, session);
        merged.continueLatest = override.continueLatest || continueLatest;
        merged.fork = firstNonNull(override.fork, fork);
        return merged;
    }

    void copyFrom(CliRuntimeSelectionOptions source) {
        model = source.model;
        tier = source.tier;
        agent = source.agent;
        session = source.session;
        continueLatest = source.continueLatest;
        fork = source.fork;
    }

    private static <T> T firstNonNull(T primary, T fallback) {
        return primary == null ? fallback : primary;
    }
}

@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
final class CliOutputOptions {

    private static final CliOutputFormat DEFAULT_FORMAT = CliOutputFormat.TEXT;
    private static final String DEFAULT_COLOR = "auto";

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

    CliOutputFormat effectiveFormat() {
        return json ? CliOutputFormat.JSON : valueOrDefault(format, DEFAULT_FORMAT);
    }

    boolean json() {
        return json;
    }

    boolean noColor() {
        return noColor;
    }

    String color() {
        return valueOrDefault(color, DEFAULT_COLOR);
    }

    boolean quiet() {
        return quiet;
    }

    boolean verbose() {
        return verbose;
    }

    String logLevel() {
        return logLevel;
    }

    CliOutputFormat explicitFormat() {
        return format;
    }

    String explicitColor() {
        return color;
    }

    CliOutputOptions merge(CliOutputOptions override) {
        CliOutputOptions merged = new CliOutputOptions();
        merged.format = firstNonNull(override.format, format);
        merged.json = override.json || json;
        merged.noColor = override.noColor || noColor;
        merged.color = firstNonNull(override.color, color);
        merged.quiet = override.quiet || quiet;
        merged.verbose = override.verbose || verbose;
        merged.logLevel = firstNonNull(override.logLevel, logLevel);
        return merged;
    }

    void copyFrom(CliOutputOptions source) {
        format = source.format;
        json = source.json;
        noColor = source.noColor;
        color = source.color;
        quiet = source.quiet;
        verbose = source.verbose;
        logLevel = source.logLevel;
    }

    private static <T> T valueOrDefault(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static <T> T firstNonNull(T primary, T fallback) {
        return primary == null ? fallback : primary;
    }
}

@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
final class CliTraceOptions {

    @Option(names = "--trace", description = "Enable/print trace id and trace collection.")
    private boolean trace;

    @Option(names = "--trace-export", description = "Export trace after run.")
    private String traceExport;

    boolean trace() {
        return trace;
    }

    String traceExport() {
        return traceExport;
    }

    CliTraceOptions merge(CliTraceOptions override) {
        CliTraceOptions merged = new CliTraceOptions();
        merged.trace = override.trace || trace;
        merged.traceExport = firstNonNull(override.traceExport, traceExport);
        return merged;
    }

    void copyFrom(CliTraceOptions source) {
        trace = source.trace;
        traceExport = source.traceExport;
    }

    private static <T> T firstNonNull(T primary, T fallback) {
        return primary == null ? fallback : primary;
    }
}

@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
final class CliCapabilityOptions {

    @Option(names = "--no-memory", description = "Disable memory read/write for this run.")
    private boolean noMemory;

    @Option(names = "--no-rag", description = "Disable RAG retrieval/indexing for this run.")
    private boolean noRag;

    @Option(names = "--no-mcp", description = "Disable MCP tools for this run.")
    private boolean noMcp;

    @Option(names = "--no-skills", description = "Disable skills for this run.")
    private boolean noSkills;

    boolean noMemory() {
        return noMemory;
    }

    boolean noRag() {
        return noRag;
    }

    boolean noMcp() {
        return noMcp;
    }

    boolean noSkills() {
        return noSkills;
    }

    CliCapabilityOptions merge(CliCapabilityOptions override) {
        CliCapabilityOptions merged = new CliCapabilityOptions();
        merged.noMemory = override.noMemory || noMemory;
        merged.noRag = override.noRag || noRag;
        merged.noMcp = override.noMcp || noMcp;
        merged.noSkills = override.noSkills || noSkills;
        return merged;
    }

    void copyFrom(CliCapabilityOptions source) {
        noMemory = source.noMemory;
        noRag = source.noRag;
        noMcp = source.noMcp;
        noSkills = source.noSkills;
    }
}

@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
final class CliPermissionOptions {

    private static final CliPermissionMode DEFAULT_PERMISSION_MODE = CliPermissionMode.ASK;

    @Option(names = "--permission-mode", converter = CliPermissionModeConverter.class, description = "Permission preset.")
    private CliPermissionMode permissionMode;

    @Option(names = { "--yes", "-y" }, description = "Auto-confirm safe prompts only.")
    private boolean yes;

    @Option(names = "--no-input", description = "Never prompt; fail if input required.")
    private boolean noInput;

    CliPermissionMode permissionMode() {
        return valueOrDefault(permissionMode, DEFAULT_PERMISSION_MODE);
    }

    boolean yes() {
        return yes;
    }

    boolean noInput() {
        return noInput;
    }

    CliPermissionMode explicitPermissionMode() {
        return permissionMode;
    }

    CliPermissionOptions merge(CliPermissionOptions override) {
        CliPermissionOptions merged = new CliPermissionOptions();
        merged.permissionMode = firstNonNull(override.permissionMode, permissionMode);
        merged.yes = override.yes || yes;
        merged.noInput = override.noInput || noInput;
        return merged;
    }

    void copyFrom(CliPermissionOptions source) {
        permissionMode = source.permissionMode;
        yes = source.yes;
        noInput = source.noInput;
    }

    private static <T> T valueOrDefault(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static <T> T firstNonNull(T primary, T fallback) {
        return primary == null ? fallback : primary;
    }
}

@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
final class CliBudgetOptions {

    @Option(names = "--timeout", description = "Run timeout.")
    private String timeout;

    @Option(names = "--max-llm-calls", description = "Budget override.")
    private Integer maxLlmCalls;

    @Option(names = "--max-tool-executions", description = "Budget override.")
    private Integer maxToolExecutions;

    String timeout() {
        return timeout;
    }

    Integer maxLlmCalls() {
        return maxLlmCalls;
    }

    Integer maxToolExecutions() {
        return maxToolExecutions;
    }

    CliBudgetOptions merge(CliBudgetOptions override) {
        CliBudgetOptions merged = new CliBudgetOptions();
        merged.timeout = firstNonNull(override.timeout, timeout);
        merged.maxLlmCalls = firstNonNull(override.maxLlmCalls, maxLlmCalls);
        merged.maxToolExecutions = firstNonNull(override.maxToolExecutions, maxToolExecutions);
        return merged;
    }

    void copyFrom(CliBudgetOptions source) {
        timeout = source.timeout;
        maxLlmCalls = source.maxLlmCalls;
        maxToolExecutions = source.maxToolExecutions;
    }

    private static <T> T firstNonNull(T primary, T fallback) {
        return primary == null ? fallback : primary;
    }
}

@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
final class CliAttachOptions {

    private static final CliAttachMode DEFAULT_ATTACH_MODE = CliAttachMode.AUTO;
    private static final String DEFAULT_HOSTNAME = "127.0.0.1";

    @Option(names = "--attach", arity = "0..1", parameterConsumer = CliAttachModeParameterConsumer.class, description = "Use existing server.")
    private CliAttachMode attach;

    @Option(names = "--port", description = "Server/TUI attach port.")
    private Integer port;

    @Option(names = "--hostname", description = "Server host.")
    private String hostname;

    CliAttachMode attach() {
        return valueOrDefault(attach, DEFAULT_ATTACH_MODE);
    }

    Integer port() {
        return port;
    }

    String hostname() {
        return valueOrDefault(hostname, DEFAULT_HOSTNAME);
    }

    CliAttachOptions merge(CliAttachOptions override) {
        CliAttachOptions merged = new CliAttachOptions();
        merged.attach = firstNonNull(override.attach, attach);
        merged.port = firstNonNull(override.port, port);
        merged.hostname = firstNonNull(override.hostname, hostname);
        return merged;
    }

    void copyFrom(CliAttachOptions source) {
        attach = source.attach;
        port = source.port;
        hostname = source.hostname;
    }

    private static <T> T valueOrDefault(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static <T> T firstNonNull(T primary, T fallback) {
        return primary == null ? fallback : primary;
    }
}
