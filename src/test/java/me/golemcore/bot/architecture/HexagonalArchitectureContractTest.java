package me.golemcore.bot.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class HexagonalArchitectureContractTest {

    private static final String ROOT_PACKAGE = "me.golemcore.bot";
    private static final String DOMAIN_PACKAGE = "me.golemcore.bot.domain";
    private static final String PORT_PACKAGE = "me.golemcore.bot.port";
    private static final List<String> FORBIDDEN_PACKAGE_PREFIXES = List.of(
            "me.golemcore.bot.adapter.",
            "me.golemcore.bot.infrastructure.",
            "me.golemcore.bot.plugin.",
            "me.golemcore.bot.proto.");
    private static final Set<String> FORBIDDEN_RUNTIME_TYPES = Set.of(
            "org.springframework.boot.SpringApplication",
            "org.springframework.boot.context.event.ApplicationReadyEvent",
            "org.springframework.boot.info.BuildProperties",
            "org.springframework.context.ApplicationContext",
            "org.springframework.context.ApplicationEventPublisher",
            "org.springframework.context.event.EventListener",
            "org.springframework.scheduling.support.CronExpression",
            "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder");

    @Test
    void domain_should_not_depend_on_adapter_plugin_infrastructure_or_proto_packages() {
        assertNoForbiddenDependencies(DOMAIN_PACKAGE, this::dependsOnForbiddenPackagePrefix);
    }

    @Test
    void port_should_not_depend_on_adapter_plugin_infrastructure_or_proto_packages() {
        assertNoForbiddenDependencies(PORT_PACKAGE, this::dependsOnForbiddenPackagePrefix);
    }

    @Test
    void domain_should_not_depend_on_forbidden_framework_runtime_types() {
        assertNoForbiddenDependencies(DOMAIN_PACKAGE, this::dependsOnForbiddenRuntimeType);
    }

    @Test
    void port_should_not_depend_on_forbidden_framework_runtime_types() {
        assertNoForbiddenDependencies(PORT_PACKAGE, this::dependsOnForbiddenRuntimeType);
    }

    private void assertNoForbiddenDependencies(String packagePrefix,
            java.util.function.Predicate<Dependency> predicate) {
        JavaClasses importedClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(ROOT_PACKAGE);

        Set<String> violations = importedClasses.stream()
                .filter(javaClass -> javaClass.getPackageName().startsWith(packagePrefix))
                .flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
                .filter(dependency -> dependency.getOriginClass().getPackageName().startsWith(packagePrefix))
                .filter(predicate)
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

    private String formatDependency(Dependency dependency) {
        return dependency.getOriginClass().getFullName() + " -> " + dependency.getTargetClass().getFullName();
    }

    private String buildViolationMessage(String packagePrefix, Set<String> violations) {
        return "Hexagonal architecture violations for " + packagePrefix + ":\n"
                + String.join("\n", violations)
                + "\nRemove the forbidden dependency or move the logic to an adapter/port boundary.";
    }
}
