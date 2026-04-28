package me.golemcore.bot.architecture;

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

class AppDomainPackageOwnershipTest {

    private static final Path REPO_ROOT = Paths.get("..").normalize();
    private static final List<Path> PRODUCTION_SOURCE_ROOTS = List.of(
            Paths.get("src/main/java"),
            Paths.get("../golemcore-bot-contracts/src/main/java"),
            Paths.get("../golemcore-bot-runtime-config/src/main/java"),
            Paths.get("../golemcore-bot-sessions/src/main/java"),
            Paths.get("../golemcore-bot-memory/src/main/java"),
            Paths.get("../golemcore-bot-tools/src/main/java"),
            Paths.get("../golemcore-bot-tracing/src/main/java"),
            Paths.get("../golemcore-bot-scheduling/src/main/java"),
            Paths.get("../golemcore-bot-runtime-core/src/main/java"),
            Paths.get("../golemcore-bot-client/src/main/java"),
            Paths.get("../golemcore-bot-self-evolving/src/main/java"),
            Paths.get("../golemcore-bot-hive/src/main/java"),
            Paths.get("../golemcore-bot-extensions/src/main/java"));

    @Test
    void production_code_should_not_use_domain_service_bucket_packages() throws IOException {
        Set<String> sources = new TreeSet<>();
        for (Path sourceRoot : PRODUCTION_SOURCE_ROOTS) {
            if (!Files.exists(sourceRoot)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                sources.addAll(paths.filter(path -> path.toString().endsWith(".java"))
                        .filter(this::declaresDomainServicePackage)
                        .map(REPO_ROOT::relativize)
                        .map(Path::toString)
                        .collect(Collectors.toCollection(TreeSet::new)));
            }
        }

        assertTrue(sources.isEmpty(), () -> "Move production classes into bounded-context packages instead of "
                + "generic domain/service buckets:\n"
                + String.join("\n", sources));
    }

    private boolean declaresDomainServicePackage(Path path) {
        try {
            return Files.lines(path)
                    .limit(40)
                    .anyMatch(line -> line.matches("^package\\s+.+\\.domain\\.service(\\..*)?;\\s*$"));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read source file " + path, exception);
        }
    }
}
