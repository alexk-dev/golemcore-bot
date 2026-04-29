package me.golemcore.bot.cli;

import java.util.ArrayList;
import java.util.List;

import picocli.CommandLine.Option;

/**
 * Global options accepted by the first CLI command surface.
 */
final class CliGlobalOptions {

    @Option(names = "--cwd", paramLabel = "<path>", description = "Working directory for command.")
    private String cwd;

    @Option(names = "--project", paramLabel = "<path>", description = "Explicit project root.")
    private String project;

    @Option(names = "--workspace", paramLabel = "<path>", description = "GolemCore storage workspace.")
    private String workspace;

    @Option(names = "--config", paramLabel = "<path>", description = "Runtime config file.")
    private String config;

    @Option(names = "--config-dir", paramLabel = "<path>", description = "Config directory.")
    private String configDir;

    @Option(names = "--profile", paramLabel = "<name>", description = "Runtime/profile selection.")
    private String profile = "default";

    @Option(names = "--env-file", paramLabel = "<path>", description = "Environment file.")
    private String envFile;

    @Option(names = { "-m", "--model" }, paramLabel = "<provider/model>", description = "Explicit model override.")
    private String model;

    @Option(names = "--tier", paramLabel = "<tier>", description = "Model tier preference.")
    private String tier;

    @Option(names = { "-a", "--agent" }, paramLabel = "<id>", description = "Agent profile.")
    private String agent;

    @Option(names = { "-s", "--session" }, paramLabel = "<id>", description = "Session id.")
    private String session;

    @Option(names = { "-c", "--continue" }, description = "Continue latest session for project.")
    private boolean continueLatest;

    @Option(names = "--fork", paramLabel = "<session>", description = "Fork existing session.")
    private String fork;

    @Option(names = "--format", converter = CliOutputFormat.Converter.class, description = "Output format.")
    private CliOutputFormat format = CliOutputFormat.TEXT;

    @Option(names = "--json", description = "Alias for --format json.")
    private boolean json;

    @Option(names = "--no-color", description = "Disable ANSI colors.")
    private boolean noColor;

    @Option(names = "--color", paramLabel = "<mode>", description = "Color policy.")
    private String color = "auto";

    @Option(names = "--quiet", description = "Suppress non-essential output.")
    private boolean quiet;

    @Option(names = "--verbose", description = "Verbose diagnostics.")
    private boolean verbose;

    @Option(names = "--log-level", paramLabel = "<level>", description = "Logging level.")
    private String logLevel;

    @Option(names = "--trace", description = "Enable trace collection.")
    private boolean trace;

    @Option(names = "--trace-export", paramLabel = "<path>", description = "Export trace after run.")
    private String traceExport;

    @Option(names = "--no-memory", description = "Disable memory read/write for this run.")
    private boolean noMemory;

    @Option(names = "--no-rag", description = "Disable RAG retrieval/indexing for this run.")
    private boolean noRag;

    @Option(names = "--no-mcp", description = "Disable MCP tools for this run.")
    private boolean noMcp;

    @Option(names = "--no-skills", description = "Disable skills for this run.")
    private boolean noSkills;

    @Option(names = "--permission-mode", converter = CliPermissionMode.Converter.class, description = "Permission preset.")
    private CliPermissionMode permissionMode = CliPermissionMode.ASK;

    @Option(names = { "--yes", "-y" }, description = "Auto-confirm safe prompts only.")
    private boolean yes;

    @Option(names = "--no-input", description = "Never prompt; fail if input required.")
    private boolean noInput;

    @Option(names = "--timeout", paramLabel = "<duration>", description = "Run timeout.")
    private String timeout;

    @Option(names = "--max-llm-calls", paramLabel = "<n>", description = "LLM call budget.")
    private Integer maxLlmCalls;

    @Option(names = "--max-tool-executions", paramLabel = "<n>", description = "Tool execution budget.")
    private Integer maxToolExecutions;

    @Option(names = "--attach", arity = "0..1", fallbackValue = "required", converter = CliAttachMode.Converter.class, description = "Attach behavior.")
    private CliAttachMode attach = CliAttachMode.AUTO;

    @Option(names = "--port", paramLabel = "<port>", description = "Server/TUI attach port.")
    private Integer port;

    @Option(names = "--hostname", paramLabel = "<host>", description = "Server host.")
    private String hostname = "127.0.0.1";

    @Option(names = { "-J",
            "--java-option" }, paramLabel = "<jvm-option>", description = "Additional JVM option forwarded by launchers.")
    private List<String> javaOptions = new ArrayList<>();

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
        return profile;
    }

    String envFile() {
        return envFile;
    }

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

    CliOutputFormat effectiveFormat() {
        return json ? CliOutputFormat.JSON : format;
    }

    boolean json() {
        return json;
    }

    boolean noColor() {
        return noColor;
    }

    String color() {
        return color;
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

    boolean trace() {
        return trace;
    }

    String traceExport() {
        return traceExport;
    }

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

    CliPermissionMode permissionMode() {
        return permissionMode;
    }

    boolean yes() {
        return yes;
    }

    boolean noInput() {
        return noInput;
    }

    String timeout() {
        return timeout;
    }

    Integer maxLlmCalls() {
        return maxLlmCalls;
    }

    Integer maxToolExecutions() {
        return maxToolExecutions;
    }

    CliAttachMode attach() {
        return attach;
    }

    Integer port() {
        return port;
    }

    String hostname() {
        return hostname;
    }

    List<String> javaOptions() {
        return List.copyOf(javaOptions);
    }
}
