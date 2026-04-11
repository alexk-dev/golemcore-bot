package me.golemcore.bot.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

@SuppressWarnings({ "PMD.UnusedPrivateField", "PMD.LooseCoupling", "PMD.CloseResource", "PMD.UseProperClassLoader" })
class HexagonalArchitectureContractTest {

    private static final String ROOT_PACKAGE = "me.golemcore.bot";
    private static final String DOMAIN_PACKAGE = "me.golemcore.bot.domain";
    private static final String APPLICATION_PACKAGE = "me.golemcore.bot.application";
    private static final String PORT_PACKAGE = "me.golemcore.bot.port";
    private static final List<String> FORBIDDEN_PACKAGE_PREFIXES = List.of(
            "me.golemcore.bot.adapter.",
            "me.golemcore.bot.auto.",
            "me.golemcore.bot.infrastructure.",
            "me.golemcore.bot.launcher.",
            "me.golemcore.bot.plugin.",
            "me.golemcore.bot.proto.",
            "me.golemcore.bot.ratelimit.",
            "me.golemcore.bot.security.",
            "me.golemcore.bot.tools.",
            "me.golemcore.bot.usage.");
    private static final String INBOUND_ADAPTER_PACKAGE = "me.golemcore.bot.adapter.inbound";
    private static final String OUTBOUND_ADAPTER_PACKAGE = "me.golemcore.bot.adapter.outbound";
    private static final Set<String> FORBIDDEN_STEREOTYPE_TYPES = Set.of(
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service");
    private static final Set<String> FORBIDDEN_RUNTIME_TYPES = Set.of(
            "org.springframework.boot.SpringApplication",
            "org.springframework.boot.context.event.ApplicationReadyEvent",
            "org.springframework.boot.info.BuildProperties",
            "org.springframework.context.ApplicationContext",
            "org.springframework.context.ApplicationEventPublisher",
            "org.springframework.context.event.EventListener",
            "org.springframework.scheduling.support.CronExpression",
            "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder",
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service");
    private static final Set<String> FORBIDDEN_DOMAIN_LOW_LEVEL_TYPES = Set.of(
            "com.fasterxml.jackson.databind.JsonNode",
            "com.fasterxml.jackson.databind.ObjectMapper",
            "com.fasterxml.jackson.dataformat.yaml.YAMLFactory",
            "java.net.http.HttpClient",
            "java.net.http.HttpRequest",
            "java.net.http.HttpResponse",
            "java.nio.file.Files");
    private static final Set<String> DOMAIN_FRAMEWORK_ALLOWLIST = loadAllowlist(
            "architecture/domain-spring-stereotype-allowlist.txt");
    private static final Set<String> DOMAIN_LOW_LEVEL_ALLOWLIST = loadAllowlist(
            "architecture/domain-low-level-dependency-allowlist.txt");
    private static final Set<String> APPLICATION_LOW_LEVEL_ALLOWLIST = loadAllowlist(
            "architecture/application-low-level-dependency-allowlist.txt");

    @Test
    void domain_should_not_depend_on_adapter_plugin_infrastructure_or_proto_packages() {
        assertNoForbiddenDependencies(DOMAIN_PACKAGE, this::dependsOnForbiddenPackagePrefix);
    }

    @Test
    void application_should_not_depend_on_adapter_plugin_infrastructure_or_proto_packages() {
        assertNoForbiddenDependencies(APPLICATION_PACKAGE, this::dependsOnForbiddenPackagePrefix);
    }

    @Test
    void port_should_not_depend_on_adapter_plugin_infrastructure_or_proto_packages() {
        assertNoForbiddenDependencies(PORT_PACKAGE, this::dependsOnForbiddenPackagePrefix);
    }

    @Test
    void domain_should_not_depend_on_forbidden_framework_runtime_types() {
        assertNoForbiddenDependencies(DOMAIN_PACKAGE, this::dependsOnForbiddenRuntimeType, DOMAIN_FRAMEWORK_ALLOWLIST);
    }

    @Test
    void application_should_not_depend_on_forbidden_framework_runtime_types() {
        assertNoForbiddenDependencies(APPLICATION_PACKAGE, this::dependsOnForbiddenRuntimeType);
    }

    @Test
    void port_should_not_depend_on_forbidden_framework_runtime_types() {
        assertNoForbiddenDependencies(PORT_PACKAGE, this::dependsOnForbiddenRuntimeType);
    }

    @Test
    void domain_should_not_depend_on_low_level_io_http_or_serialization_types() {
        assertNoForbiddenDependencies(DOMAIN_PACKAGE, this::dependsOnForbiddenDomainLowLevelType,
                DOMAIN_LOW_LEVEL_ALLOWLIST);
    }

    @Test
    void application_should_not_depend_on_low_level_io_http_or_serialization_types() {
        assertNoForbiddenDependencies(APPLICATION_PACKAGE, this::dependsOnForbiddenDomainLowLevelType,
                APPLICATION_LOW_LEVEL_ALLOWLIST);
    }

    @Test
    void inbound_adapters_should_not_depend_on_outbound_adapters() {
        assertNoForbiddenDependencies(INBOUND_ADAPTER_PACKAGE,
                dependency -> dependency.getTargetClass().getPackageName().startsWith(OUTBOUND_ADAPTER_PACKAGE));
    }

    @Test
    void outbound_adapters_should_not_depend_on_inbound_adapters() {
        assertNoForbiddenDependencies(OUTBOUND_ADAPTER_PACKAGE,
                dependency -> dependency.getTargetClass().getPackageName().startsWith(INBOUND_ADAPTER_PACKAGE));
    }

    private void assertNoForbiddenDependencies(String packagePrefix,
            java.util.function.Predicate<Dependency> predicate) {
        assertNoForbiddenDependencies(packagePrefix, predicate, Set.of());
    }

    private void assertNoForbiddenDependencies(
            String packagePrefix,
            java.util.function.Predicate<Dependency> predicate,
            Set<String> allowlistedOrigins) {
        JavaClasses importedClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(ROOT_PACKAGE);

        Set<String> violations = importedClasses.stream()
                .filter(javaClass -> javaClass.getPackageName().startsWith(packagePrefix))
                .flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
                .filter(dependency -> dependency.getOriginClass().getPackageName().startsWith(packagePrefix))
                .filter(predicate)
                .filter(dependency -> !allowlistedOrigins.contains(dependency.getOriginClass().getFullName()))
                .map(this::formatDependency)
                .collect(Collectors.toCollection(TreeSet::new));

        assertTrue(violations.isEmpty(), () -> buildViolationMessage(packagePrefix, violations));
    }

    private boolean dependsOnForbiddenPackagePrefix(Dependency dependency) {
        String packageName = dependency.getTargetClass().getPackageName();
        return FORBIDDEN_PACKAGE_PREFIXES.stream().anyMatch(packageName::startsWith);
    }

    private boolean dependsOnForbiddenRuntimeType(Dependency dependency) {
        JavaClass targetClass = dependency.getTargetClass();
        return FORBIDDEN_RUNTIME_TYPES.contains(targetClass.getFullName());
    }

    private boolean dependsOnForbiddenDomainLowLevelType(Dependency dependency) {
        JavaClass targetClass = dependency.getTargetClass();
        return FORBIDDEN_DOMAIN_LOW_LEVEL_TYPES.contains(targetClass.getFullName());
    }

    private static Set<String> loadAllowlist(String resourcePath) {
        InputStream resourceStream = HexagonalArchitectureContractTest.class.getClassLoader()
                .getResourceAsStream(resourcePath);
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

    private String formatDependency(Dependency dependency) {
        return dependency.getOriginClass().getFullName() + " -> " + dependency.getTargetClass().getFullName();
    }

    private String buildViolationMessage(String packagePrefix, Set<String> violations) {
        return "Hexagonal architecture violations for " + packagePrefix + ":\n"
                + String.join("\n", violations)
                + "\nRemove the forbidden dependency or move the logic to an adapter/port boundary.";
    }
}
