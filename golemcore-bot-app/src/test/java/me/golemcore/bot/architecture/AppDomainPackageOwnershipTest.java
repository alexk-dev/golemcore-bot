package me.golemcore.bot.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class AppDomainPackageOwnershipTest {

    private static final Path DOMAIN_SERVICE_PACKAGE = Paths.get(
            "src/main/java/me/golemcore/bot/domain/service");

    @Test
    void app_domain_service_package_should_not_contain_production_sources() throws IOException {
        if (!Files.exists(DOMAIN_SERVICE_PACKAGE)) {
            return;
        }

        Set<String> sources;
        try (Stream<Path> paths = Files.walk(DOMAIN_SERVICE_PACKAGE)) {
            sources = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(DOMAIN_SERVICE_PACKAGE::relativize)
                    .map(Path::toString)
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        assertTrue(sources.isEmpty(),
                () -> "Move app domain services into bounded-context packages instead of domain/service:\n"
                        + String.join("\n", sources));
    }
}
