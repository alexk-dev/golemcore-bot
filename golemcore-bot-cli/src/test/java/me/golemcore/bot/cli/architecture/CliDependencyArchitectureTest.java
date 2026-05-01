package me.golemcore.bot.cli.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class CliDependencyArchitectureTest {

    @SuppressWarnings("PMD.LooseCoupling")
    private static final com.tngtech.archunit.core.domain.JavaClasses CLI_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("me.golemcore.bot.cli");

    @Test
    void application_use_cases_should_only_depend_on_ports_domain_and_jdk() {
        ArchRule rule = classes().that().resideInAPackage("..cli.application.usecase..")
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "me.golemcore.bot.cli.application..",
                        "me.golemcore.bot.cli.domain..",
                        "java..");

        rule.check(CLI_CLASSES);
    }

    @Test
    void application_ports_should_not_depend_on_channel_adapters_presenters_or_frameworks() {
        ArchRule rule = noClasses().that().resideInAPackage("..cli.application.port..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "me.golemcore.bot.cli.adapter..",
                        "me.golemcore.bot.cli.presentation..",
                        "picocli..",
                        "org.springframework..");

        rule.check(CLI_CLASSES);
    }

    @Test
    void presenters_should_not_depend_on_io_ports_adapters_or_networking() {
        ArchRule rule = noClasses().that().resideInAPackage("..cli.presentation..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "me.golemcore.bot.cli.application..",
                        "me.golemcore.bot.cli.adapter..",
                        "picocli..",
                        "java.net..",
                        "java.nio.file..");

        rule.check(CLI_CLASSES);
    }

    @Test
    void domain_models_should_not_depend_on_terminal_picocli_or_frameworks() {
        ArchRule rule = noClasses().that().resideInAPackage("..cli.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "picocli..",
                        "org.springframework..",
                        "java.io..");

        rule.check(CLI_CLASSES);
    }

    @Test
    void config_should_not_depend_on_picocli_adapter_types() {
        ArchRule rule = noClasses().that().resideInAPackage("..cli.config..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("picocli..", "me.golemcore.bot.cli.adapter..");

        rule.check(CLI_CLASSES);
    }
}
