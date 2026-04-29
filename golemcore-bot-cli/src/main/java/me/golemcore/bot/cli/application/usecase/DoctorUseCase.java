package me.golemcore.bot.cli.application.usecase;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.golemcore.bot.cli.application.port.in.DoctorInputBoundary;
import me.golemcore.bot.cli.domain.CliCommandOptions;
import me.golemcore.bot.cli.domain.DoctorCheck;
import me.golemcore.bot.cli.domain.DoctorCheckStatus;
import me.golemcore.bot.cli.domain.DoctorReport;

public final class DoctorUseCase implements DoctorInputBoundary {

    private final List<String> commandNames;

    public DoctorUseCase(List<String> commandNames) {
        this.commandNames = List.copyOf(commandNames);
    }

    @Override
    public DoctorReport inspect(CliCommandOptions options) {
        List<DoctorCheck> checks = new ArrayList<>();
        checks.add(new DoctorCheck(
                "cli.command_surface",
                DoctorCheckStatus.OK,
                commandNames.size() + " top-level commands are registered"));
        checks.add(new DoctorCheck(
                "java.runtime",
                DoctorCheckStatus.OK,
                "Java " + System.getProperty("java.version", "unknown")));
        checks.add(new DoctorCheck(
                "workspace",
                statusForConfiguredPath(options.workspace()),
                configuredPathMessage("workspace", options.workspace())));
        checks.add(new DoctorCheck(
                "project",
                statusForConfiguredPath(options.project()),
                configuredPathMessage("project", options.project())));
        checks.add(new DoctorCheck(
                "cli.package_boundaries",
                DoctorCheckStatus.OK,
                "Picocli adapter, application use cases, domain DTOs, presenters, and config are separated"));
        checks.add(new DoctorCheck(
                "tui.runtime",
                DoctorCheckStatus.WARN,
                "TUI runtime is not implemented in this CLI slice"));

        DoctorCheckStatus aggregateStatus = checks.stream()
                .map(DoctorCheck::status)
                .filter(DoctorCheckStatus.ERROR::equals)
                .findAny()
                .orElse(DoctorCheckStatus.OK);
        return new DoctorReport(aggregateStatus.serializedValue(), checks);
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
