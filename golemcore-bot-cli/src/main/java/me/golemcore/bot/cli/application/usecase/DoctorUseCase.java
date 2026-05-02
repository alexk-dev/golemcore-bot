package me.golemcore.bot.cli.application.usecase;

import java.nio.file.Path;
import java.util.List;
import me.golemcore.bot.cli.application.port.in.DoctorInputBoundary;
import me.golemcore.bot.cli.application.port.out.DoctorCheckProvider;
import me.golemcore.bot.cli.domain.CliCommandOptions;
import me.golemcore.bot.cli.domain.DoctorCheck;
import me.golemcore.bot.cli.domain.DoctorCheckStatus;
import me.golemcore.bot.cli.domain.DoctorReport;

public final class DoctorUseCase implements DoctorInputBoundary {

    private final List<DoctorCheckProvider> checkProviders;

    public DoctorUseCase(List<String> commandNames) {
        this(commandNames, defaultCheckProviders(commandNames));
    }

    public DoctorUseCase(List<String> commandNames, List<DoctorCheckProvider> checkProviders) {
        List.copyOf(commandNames);
        this.checkProviders = List.copyOf(checkProviders);
    }

    @Override
    public DoctorReport inspect(CliCommandOptions options) {
        List<DoctorCheck> checks = checkProviders.stream()
                .map(provider -> provider.inspect(options))
                .toList();
        DoctorCheckStatus aggregateStatus = aggregateStatus(checks);
        return new DoctorReport(aggregateStatus.serializedValue(), checks);
    }

    private static List<DoctorCheckProvider> defaultCheckProviders(List<String> commandNames) {
        List<String> registeredCommandNames = List.copyOf(commandNames);
        return List.of(
                options -> new DoctorCheck(
                        "cli.command_surface",
                        DoctorCheckStatus.OK,
                        registeredCommandNames.size() + " top-level commands are registered"),
                options -> new DoctorCheck(
                        "java.runtime",
                        DoctorCheckStatus.OK,
                        "Java " + System.getProperty("java.version", "unknown")),
                options -> new DoctorCheck(
                        "workspace",
                        statusForConfiguredPath(options.workspace()),
                        configuredPathMessage("workspace", options.workspace())),
                options -> new DoctorCheck(
                        "project",
                        statusForConfiguredPath(options.project()),
                        configuredPathMessage("project", options.project())),
                options -> new DoctorCheck(
                        "cli.package_boundaries",
                        DoctorCheckStatus.OK,
                        "Picocli adapter, application use cases, domain DTOs, presenters, and config are separated"),
                options -> new DoctorCheck(
                        "tui.runtime",
                        DoctorCheckStatus.WARN,
                        "TUI runtime is not implemented in this CLI slice"));
    }

    private static DoctorCheckStatus aggregateStatus(List<DoctorCheck> checks) {
        if (checks.stream().map(DoctorCheck::status).anyMatch(DoctorCheckStatus.ERROR::equals)) {
            return DoctorCheckStatus.ERROR;
        }
        if (checks.stream().map(DoctorCheck::status).anyMatch(DoctorCheckStatus.WARN::equals)) {
            return DoctorCheckStatus.WARN;
        }
        return DoctorCheckStatus.OK;
    }

    private static DoctorCheckStatus statusForConfiguredPath(Path path) {
        return path == null ? DoctorCheckStatus.WARN : DoctorCheckStatus.OK;
    }

    private static String configuredPathMessage(String label, Path path) {
        if (path == null) {
            return label + " path was not provided; runtime config will resolve it later";
        }
        return label + " path configured as " + path;
    }
}
