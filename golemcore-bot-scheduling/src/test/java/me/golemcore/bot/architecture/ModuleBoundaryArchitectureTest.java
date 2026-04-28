package me.golemcore.bot.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ModuleBoundaryArchitectureTest {

    private static final Path MAIN_SOURCES = Paths.get("src/main/java");
    private static final List<String> FORBIDDEN_APP_BOUNDARY_REFERENCES = List.of("me.golemcore.bot.adapter.",
            "me.golemcore.bot.infrastructure.", "me.golemcore.bot.launcher.", "me.golemcore.bot.plugin.",
            "me.golemcore.bot.proto.", "me.golemcore.bot.ratelimit.", "me.golemcore.bot.security.",
            "me.golemcore.bot.tools.", "me.golemcore.bot.usage.", "me.golemcore.bot.domain.auto.");
    private static final List<String> FORBIDDEN_INJECTION_SHORTCUTS = List.of("import lombok.RequiredArgsConstructor;",
            "@RequiredArgsConstructor", "import org.springframework.beans.factory.annotation.Autowired;",
            "import jakarta.annotation.Resource;", "@Autowired", "@Resource");

    @Test
    void mainSourcesShouldStayOutsideAppAndAdapterBoundaries() throws IOException {
        assertNoSourceText(FORBIDDEN_APP_BOUNDARY_REFERENCES, "must not depend on app or adapter packages");
    }

    @Test
    void mainSourcesShouldUseExplicitInjectionConstructors() throws IOException {
        assertNoSourceText(FORBIDDEN_INJECTION_SHORTCUTS, "must use final fields and explicit constructors");
    }

    private void assertNoSourceText(List<String> forbiddenPatterns, String reason) throws IOException {
        Set<String> violations = new TreeSet<>();
        if (!Files.exists(MAIN_SOURCES)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(MAIN_SOURCES)) {
            for (Path path : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                for (String forbiddenPattern : forbiddenPatterns) {
                    if (source.contains(forbiddenPattern)) {
                        violations.add(MAIN_SOURCES.relativize(path) + " contains " + forbiddenPattern);
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), () -> reason + ":\n" + String.join("\n", violations));
    }
}
