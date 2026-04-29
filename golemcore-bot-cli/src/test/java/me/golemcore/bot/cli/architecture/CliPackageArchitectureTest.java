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
        Set<String> allowedRootFiles = Set.of("CliApplication.java", "CliCommandFactory.java");
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
    void use_cases_should_not_depend_on_picocli_or_adapters() throws IOException {
        Set<String> violations = sourcesContaining(
                CLI_ROOT.resolve("application/usecase"),
                List.of("picocli.", ".adapter.", ".presentation."));

        assertTrue(violations.isEmpty(), () -> "CLI use cases must depend on ports/domain, not adapters: "
                + violations);
    }

    @Test
    void application_ports_should_not_depend_on_adapters_presenters_or_picocli() throws IOException {
        Set<String> violations = sourcesContaining(
                CLI_ROOT.resolve("application/port"),
                List.of("picocli.", ".adapter.", ".presentation.", "org.springframework."));

        assertTrue(violations.isEmpty(), () -> "CLI application ports must stay channel-agnostic: " + violations);
    }

    @Test
    void config_should_not_depend_on_picocli_converters() throws IOException {
        Set<String> violations = sourcesContaining(
                CLI_ROOT.resolve("config"),
                List.of("picocli."));

        assertTrue(violations.isEmpty(), () -> "Picocli converters must stay in the Picocli adapter: "
                + violations);
    }

    @Test
    void domain_models_should_not_depend_on_terminal_or_picocli() throws IOException {
        Set<String> violations = sourcesContaining(
                CLI_ROOT.resolve("domain"),
                List.of("picocli.", "java.io.PrintWriter", "System.out", "System.err"));

        assertTrue(violations.isEmpty(), () -> "CLI domain must stay free of terminal and Picocli dependencies: "
                + violations);
    }

    @Test
    void presenters_should_not_call_filesystem_shell_or_network() throws IOException {
        Set<String> violations = sourcesContaining(
                CLI_ROOT.resolve("presentation"),
                List.of("java.nio.file.", "ProcessBuilder", "java.net.", "java.net.http.", "picocli.",
                        ".application.port.", ".application.usecase.", ".adapter."));

        assertTrue(violations.isEmpty(), () -> "CLI presenters must only render view models: " + violations);
    }

    private static Set<String> sourcesContaining(Path root, List<String> forbiddenPatterns) throws IOException {
        Set<String> violations = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                for (String forbiddenPattern : forbiddenPatterns) {
                    if (source.contains(forbiddenPattern)) {
                        violations.add(root.relativize(path) + " contains " + forbiddenPattern);
                    }
                }
            }
        }
        return violations;
    }
}
