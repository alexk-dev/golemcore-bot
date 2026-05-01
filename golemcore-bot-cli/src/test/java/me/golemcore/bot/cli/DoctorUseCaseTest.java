package me.golemcore.bot.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import me.golemcore.bot.cli.application.port.out.DoctorCheckProvider;
import me.golemcore.bot.cli.application.usecase.DoctorUseCase;
import me.golemcore.bot.cli.domain.CliCommandOptions;
import me.golemcore.bot.cli.domain.DoctorCheck;
import me.golemcore.bot.cli.domain.DoctorCheckStatus;
import me.golemcore.bot.cli.domain.DoctorReport;
import org.junit.jupiter.api.Test;

class DoctorUseCaseTest {

    @Test
    void shouldInspectUsingInjectedCheckProviders() {
        DoctorCheckProvider provider = options -> new DoctorCheck(
                "fake.runtime",
                DoctorCheckStatus.WARN,
                "fake warning for " + options.format());
        DoctorUseCase useCase = new DoctorUseCase(List.of("run"), List.of(provider));

        DoctorReport report = useCase.inspect(emptyOptions());

        assertEquals("warn", report.status());
        assertEquals(1, report.checks().size());
        assertEquals("fake.runtime", report.checks().get(0).name());
        assertEquals("fake warning for text", report.checks().get(0).message());
    }

    private static CliCommandOptions emptyOptions() {
        return new CliCommandOptions(
                new CliCommandOptions.ProjectOptions(null, null, null, null, null, "default", null),
                new CliCommandOptions.RuntimeSelectionOptions(null, null, null, null, false, null),
                new CliCommandOptions.OutputOptions("text", false, false, "auto", false, false, null),
                new CliCommandOptions.TraceOptions(false, null),
                new CliCommandOptions.CapabilityOptions(false, false, false, false),
                new CliCommandOptions.PermissionOptions("ask", false, false),
                new CliCommandOptions.BudgetOptions(null, null, null),
                new CliCommandOptions.AttachOptions("never", null, "127.0.0.1"));
    }
}
