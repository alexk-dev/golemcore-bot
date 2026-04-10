package me.golemcore.bot.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CodeSizeContractTest {

    private static final Path MAIN_JAVA_ROOT = Paths.get("src/main/java");
    private static final int LARGE_PRODUCTION_THRESHOLD = 1000;
    private static final int LARGE_DOMAIN_THRESHOLD = 400;
    private static final Set<String> LARGE_PRODUCTION_ALLOWLIST = loadAllowlist(
            "architecture/large-production-file-allowlist.txt");
    private static final Set<String> LARGE_DOMAIN_ALLOWLIST = loadAllowlist(
            "architecture/large-domain-file-allowlist.txt");

    @Test
    void production_files_should_not_exceed_size_threshold_outside_allowlist() {
        assertNoOversizedFiles(
                path -> true,
                LARGE_PRODUCTION_THRESHOLD,
                LARGE_PRODUCTION_ALLOWLIST,
                "production");
    }

    @Test
    void domain_files_should_not_exceed_size_threshold_outside_allowlist() {
        assertNoOversizedFiles(
                path -> path.startsWith(Paths.get("me/golemcore/bot/domain")),
                LARGE_DOMAIN_THRESHOLD,
                LARGE_DOMAIN_ALLOWLIST,
                "domain");
    }

    private void assertNoOversizedFiles(
            Predicate<Path> filter,
            int threshold,
            Set<String> allowlist,
            String label) {
        Set<String> violations = findJavaFiles().stream()
                .filter(filter)
                .map(path -> path.normalize().toString().replace('\\', '/'))
                .map(path -> path + " (" + countLines(MAIN_JAVA_ROOT.resolve(path)) + " LOC)")
                .filter(entry -> !allowlist.contains(stripCount(entry)))
                .filter(entry -> extractCount(entry) >= threshold)
                .collect(Collectors.toCollection(TreeSet::new));

        assertTrue(violations.isEmpty(), () -> "Oversized " + label + " files exceed threshold without allowlist:\n"
                + String.join("\n", violations)
                + "\nEither split the file or explicitly record the debt in the allowlist.");
    }

    private List<Path> findJavaFiles() {
        try (Stream<Path> paths = Files.walk(MAIN_JAVA_ROOT)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(MAIN_JAVA_ROOT::relativize)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan source tree for size contract", exception);
        }
    }

    private int countLines(Path path) {
        try (Stream<String> lines = Files.lines(path)) {
            return (int) lines.count();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to count source lines for " + path, exception);
        }
    }

    private String stripCount(String entry) {
        int suffixIndex = entry.indexOf(" (");
        return suffixIndex >= 0 ? entry.substring(0, suffixIndex) : entry;
    }

    private int extractCount(String entry) {
        int startIndex = entry.indexOf(" (");
        int endIndex = entry.indexOf(" LOC)");
        return Integer.parseInt(entry.substring(startIndex + 2, endIndex));
    }

    private static Set<String> loadAllowlist(String resourcePath) {
        InputStream resourceStream = CodeSizeContractTest.class.getClassLoader().getResourceAsStream(resourcePath);
        if (resourceStream == null) {
            throw new IllegalStateException("Missing architecture allowlist resource: " + resourcePath);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceStream))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("#"))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load architecture allowlist: " + resourcePath, exception);
        }
    }
}
