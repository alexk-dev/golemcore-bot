package me.golemcore.bot.cli.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CliPackageArchitectureTest {

    private static final Path MAIN_SOURCES = Paths.get("src/main/java");
    private static final Path CLI_ROOT = MAIN_SOURCES.resolve("me/golemcore/bot/cli");
    private static final Path CONTRACTS_CLI_PORT_ROOT = Paths.get("../golemcore-bot-contracts/src/main/java")
            .resolve("me/golemcore/bot/domain/cli/port");

    @Test
    void cli_module_should_expose_planned_package_boundaries() {
        List<String> expectedPackages = List.of(
                "adapter/in/picocli",
                "adapter/in/tui",
                "adapter/out/terminal",
                "adapter/out/pty",
                "adapter/out/localfs",
                "application/port/in",
                "application/port/out",
                "application/usecase",
                "config",
                "domain",
                "presentation",
                "router");

        Set<String> missingPackages = expectedPackages.stream()
                .filter(packagePath -> !Files.exists(CLI_ROOT.resolve(packagePath)))
                .collect(Collectors.toCollection(TreeSet::new));

        assertTrue(missingPackages.isEmpty(), () -> "Missing CLI architecture packages: " + missingPackages);
    }

    @Test
    void root_cli_package_should_only_contain_facades() throws IOException {
        Set<String> allowedRootFiles = Set.of("CliApplication.java", "CliCommandFactory.java", "CliDependencies.java");
        Set<String> violations;
        try (Stream<Path> paths = Files.list(CLI_ROOT)) {
            violations = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .filter(fileName -> !allowedRootFiles.contains(fileName))
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        assertTrue(violations.isEmpty(), () -> "Only root CLI facades may live in me.golemcore.bot.cli: "
                + violations);
    }

    @Test
    void picocli_root_should_not_construct_application_use_cases_directly() throws IOException {
        String source = Files.readString(CLI_ROOT.resolve("adapter/in/picocli/CliRootCommand.java"));
        List<String> forbiddenConstructions = List.of(
                "new StartTuiUseCase(",
                "new NotImplementedCommandUseCase(",
                "new DoctorUseCase(");
        List<String> violations = forbiddenConstructions.stream()
                .filter(source::contains)
                .toList();

        assertTrue(violations.isEmpty(), () -> "Picocli root must receive use cases through CliDependencies: "
                + violations);
    }

    @Test
    void picocli_root_should_not_own_command_implementation_classes() throws IOException {
        String source = Files.readString(CLI_ROOT.resolve("adapter/in/picocli/CliRootCommand.java"));
        List<String> commandMarkers = List.of(
                "@Command(name = \"run\"",
                "@Command(name = \"doctor\"",
                "abstract static class StubCommand",
                "extends StubCommand");
        List<String> violations = commandMarkers.stream()
                .filter(source::contains)
                .toList();

        assertTrue(violations.isEmpty(), () -> "CliRootCommand should only hold root metadata and shared context; "
                + "command classes must live in separate Picocli adapter classes: " + violations);
    }

    @Test
    void picocli_global_options_should_be_split_into_mixin_groups() throws IOException {
        String source = Files.readString(CLI_ROOT.resolve("adapter/in/picocli/CliGlobalOptions.java"));
        List<String> requiredMixins = List.of(
                "CliProjectOptions",
                "CliRuntimeSelectionOptions",
                "CliOutputOptions",
                "CliTraceOptions",
                "CliCapabilityOptions",
                "CliPermissionOptions",
                "CliBudgetOptions",
                "CliAttachOptions");
        List<String> missingMixins = requiredMixins.stream()
                .filter(mixin -> !source.contains(mixin))
                .toList();
        List<String> directOptionFields = List.of(
                "@Option(names = \"--cwd\"",
                "@Option(names = \"--format\"",
                "@Option(names = \"--permission-mode\"",
                "@Option(names = \"--attach\"");
        List<String> directViolations = directOptionFields.stream()
                .filter(source::contains)
                .toList();

        assertTrue(missingMixins.isEmpty(), () -> "CliGlobalOptions must compose option groups: " + missingMixins);
        assertTrue(directViolations.isEmpty(), () -> "CliGlobalOptions must not own individual option fields: "
                + directViolations);
    }

    @Test
    void picocli_subcommands_should_be_registered_from_command_descriptors() throws IOException {
        String rootSource = Files.readString(CLI_ROOT.resolve("adapter/in/picocli/CliRootCommand.java"));
        String catalogSource = Files.readString(CLI_ROOT.resolve("router/CliCommandCatalog.java"));

        assertTrue(!rootSource.contains("subcommands = {"),
                "CliRootCommand must not duplicate the command registry in @Command metadata");
        assertTrue(catalogSource.contains("CliCommandDescriptor"),
                "CliCommandCatalog must expose descriptors as the single command registry source");
    }

    @Test
    void shared_coding_agent_boundaries_should_not_live_in_cli_module() throws IOException {
        List<String> sharedBoundaryFiles = List.of(
                "RunPromptInputBoundary.java",
                "ProjectInputBoundary.java",
                "AgentRuntimePort.java",
                "ProjectDiscoveryPort.java",
                "ProjectConfigPort.java",
                "ProjectTrustRegistryPort.java");

        Set<String> misplaced;
        try (Stream<Path> paths = Files.walk(CLI_ROOT.resolve("application/port"))) {
            misplaced = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .filter(sharedBoundaryFiles::contains)
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        Set<String> missingSharedContracts = sharedBoundaryFiles.stream()
                .filter(fileName -> !Files.exists(CONTRACTS_CLI_PORT_ROOT.resolve("in").resolve(fileName))
                        && !Files.exists(CONTRACTS_CLI_PORT_ROOT.resolve("out").resolve(fileName)))
                .collect(Collectors.toCollection(TreeSet::new));

        assertTrue(misplaced.isEmpty(), () -> "Channel-neutral boundaries must move out of CLI module: "
                + misplaced);
        assertTrue(missingSharedContracts.isEmpty(), () -> "Shared boundaries must live in contracts: "
                + missingSharedContracts);
    }
}
