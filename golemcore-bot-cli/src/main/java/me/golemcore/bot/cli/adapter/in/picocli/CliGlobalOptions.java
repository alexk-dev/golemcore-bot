package me.golemcore.bot.cli.adapter.in.picocli;

import me.golemcore.bot.cli.config.CliAttachMode;
import me.golemcore.bot.domain.cli.CliOutputFormat;
import me.golemcore.bot.domain.cli.CliPermissionMode;
import picocli.CommandLine.Mixin;

public final class CliGlobalOptions {

    @Mixin
    private final CliProjectOptions projectOptions = new CliProjectOptions();

    @Mixin
    private final CliRuntimeSelectionOptions runtimeSelectionOptions = new CliRuntimeSelectionOptions();

    @Mixin
    private final CliOutputOptions outputOptions = new CliOutputOptions();

    @Mixin
    private final CliTraceOptions traceOptions = new CliTraceOptions();

    @Mixin
    private final CliCapabilityOptions capabilityOptions = new CliCapabilityOptions();

    @Mixin
    private final CliPermissionOptions permissionOptions = new CliPermissionOptions();

    @Mixin
    private final CliBudgetOptions budgetOptions = new CliBudgetOptions();

    @Mixin
    private final CliAttachOptions attachOptions = new CliAttachOptions();

    public String cwd() {
        return projectOptions.cwd();
    }

    public String project() {
        return projectOptions.project();
    }

    public String workspace() {
        return projectOptions.workspace();
    }

    public String config() {
        return projectOptions.config();
    }

    public String configDir() {
        return projectOptions.configDir();
    }

    public String profile() {
        return projectOptions.profile();
    }

    public String envFile() {
        return projectOptions.envFile();
    }

    public String model() {
        return runtimeSelectionOptions.model();
    }

    public String tier() {
        return runtimeSelectionOptions.tier();
    }

    public String agent() {
        return runtimeSelectionOptions.agent();
    }

    public String session() {
        return runtimeSelectionOptions.session();
    }

    public boolean continueLatest() {
        return runtimeSelectionOptions.continueLatest();
    }

    public String fork() {
        return runtimeSelectionOptions.fork();
    }

    public CliOutputFormat format() {
        return outputOptions.effectiveFormat();
    }

    public boolean json() {
        return outputOptions.json();
    }

    public boolean noColor() {
        return outputOptions.noColor();
    }

    public String color() {
        return outputOptions.color();
    }

    public boolean quiet() {
        return outputOptions.quiet();
    }

    public boolean verbose() {
        return outputOptions.verbose();
    }

    public String logLevel() {
        return outputOptions.logLevel();
    }

    public boolean trace() {
        return traceOptions.trace();
    }

    public String traceExport() {
        return traceOptions.traceExport();
    }

    public boolean noMemory() {
        return capabilityOptions.noMemory();
    }

    public boolean noRag() {
        return capabilityOptions.noRag();
    }

    public boolean noMcp() {
        return capabilityOptions.noMcp();
    }

    public boolean noSkills() {
        return capabilityOptions.noSkills();
    }

    public CliPermissionMode permissionMode() {
        return permissionOptions.permissionMode();
    }

    public boolean yes() {
        return permissionOptions.yes();
    }

    public boolean noInput() {
        return permissionOptions.noInput();
    }

    public String timeout() {
        return budgetOptions.timeout();
    }

    public Integer maxLlmCalls() {
        return budgetOptions.maxLlmCalls();
    }

    public Integer maxToolExecutions() {
        return budgetOptions.maxToolExecutions();
    }

    public CliAttachMode attach() {
        return attachOptions.attach();
    }

    public Integer port() {
        return attachOptions.port();
    }

    public String hostname() {
        return attachOptions.hostname();
    }

    public CliOutputFormat effectiveFormat() {
        return outputOptions.effectiveFormat();
    }

    CliOutputFormat explicitFormat() {
        return outputOptions.explicitFormat();
    }

    String explicitColor() {
        return outputOptions.explicitColor();
    }

    CliPermissionMode explicitPermissionMode() {
        return permissionOptions.explicitPermissionMode();
    }

    CliGlobalOptions merge(CliGlobalOptions override) {
        CliGlobalOptions merged = new CliGlobalOptions();
        merged.projectOptions.copyFrom(projectOptions.merge(override.projectOptions));
        merged.runtimeSelectionOptions.copyFrom(runtimeSelectionOptions.merge(override.runtimeSelectionOptions));
        merged.outputOptions.copyFrom(outputOptions.merge(override.outputOptions));
        merged.traceOptions.copyFrom(traceOptions.merge(override.traceOptions));
        merged.capabilityOptions.copyFrom(capabilityOptions.merge(override.capabilityOptions));
        merged.permissionOptions.copyFrom(permissionOptions.merge(override.permissionOptions));
        merged.budgetOptions.copyFrom(budgetOptions.merge(override.budgetOptions));
        merged.attachOptions.copyFrom(attachOptions.merge(override.attachOptions));
        return merged;
    }
}
