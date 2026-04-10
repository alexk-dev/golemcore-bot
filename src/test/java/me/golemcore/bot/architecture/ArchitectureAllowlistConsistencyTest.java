package me.golemcore.bot.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ArchitectureAllowlistConsistencyTest {

    private static final Path MAIN_JAVA_ROOT = Paths.get("src/main/java");
    private static final List<AllowlistSpec> ALLOWLISTS = List.of(
            new AllowlistSpec(
                    "architecture/domain-spring-stereotype-allowlist.txt",
                    "domain spring stereotype allowlist"),
            new AllowlistSpec(
                    "architecture/domain-low-level-dependency-allowlist.txt",
                    "domain low-level dependency allowlist"),
            new AllowlistSpec(
                    "architecture/application-low-level-dependency-allowlist.txt",
                    "application low-level dependency allowlist"));

    @Test
    void architecture_allowlists_should_be_sorted_and_unique() {
        for (AllowlistSpec allowlist : ALLOWLISTS) {
            List<String> entries = loadEntries(allowlist.resourcePath());
            List<String> normalized = entries.stream()
                    .distinct()
                    .sorted()
                    .toList();

            assertEquals(normalized, entries,
                    () -> allowlist.displayName()
                            + " must stay sorted and unique to keep architecture debt reviewable");
        }
    }

    @Test
    void architecture_allowlists_should_reference_existing_production_classes() {
        for (AllowlistSpec allowlist : ALLOWLISTS) {
            Set<String> missingEntries = new LinkedHashSet<>();
            for (String entry : loadEntries(allowlist.resourcePath())) {
                Path sourcePath = MAIN_JAVA_ROOT.resolve(entry.replace('.', '/') + ".java");
                if (!Files.exists(sourcePath)) {
                    missingEntries.add(entry);
                }
            }

            assertTrue(missingEntries.isEmpty(), () -> allowlist.displayName()
                    + " contains stale entries for missing classes:\n"
                    + String.join("\n", missingEntries)
                    + "\nRemove dead allowlist entries when code moves or is deleted.");
        }
    }

    private List<String> loadEntries(String resourcePath) {
        InputStream resourceStream = ArchitectureAllowlistConsistencyTest.class.getClassLoader()
                .getResourceAsStream(resourcePath);
        if (resourceStream == null) {
            throw new IllegalStateException("Missing architecture allowlist resource: " + resourcePath);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceStream))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("#"))
                    .collect(Collectors.toList());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load architecture allowlist: " + resourcePath, exception);
        }
    }

    private record AllowlistSpec(String resourcePath, String displayName) {
    }
}
