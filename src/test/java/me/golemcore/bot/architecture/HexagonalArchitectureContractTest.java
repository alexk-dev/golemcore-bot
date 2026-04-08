package me.golemcore.bot.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "me.golemcore.bot.adapter.",
            "me.golemcore.bot.infrastructure.",
            "me.golemcore.bot.plugin.");

    private static final Set<String> KNOWN_DOMAIN_FORBIDDEN_DEPENDENCIES = dependencySet(
            "me.golemcore.bot.domain.loop.AgentLoop -> me.golemcore.bot.plugin.runtime.ChannelRegistry",
            "me.golemcore.bot.domain.memory.persistence.MemoryPersistenceOrchestrator -> me.golemcore.bot.infrastructure.config.BotProperties",
            "me.golemcore.bot.domain.memory.persistence.MemoryPersistenceOrchestrator -> me.golemcore.bot.infrastructure.config.BotProperties$MemoryProperties",
            "me.golemcore.bot.domain.memory.retrieval.MemoryCandidateCollector -> me.golemcore.bot.infrastructure.config.BotProperties",
            "me.golemcore.bot.domain.memory.retrieval.MemoryCandidateCollector -> me.golemcore.bot.infrastructure.config.BotProperties$MemoryProperties",
            "me.golemcore.bot.domain.selfevolving.SelfEvolvingBootstrapOverrideService -> me.golemcore.bot.infrastructure.config.BotProperties",
            "me.golemcore.bot.domain.selfevolving.SelfEvolvingBootstrapOverrideService -> me.golemcore.bot.infrastructure.config.BotProperties$SelfEvolvingBootstrapProperties",
            "me.golemcore.bot.domain.selfevolving.SelfEvolvingBootstrapOverrideService -> me.golemcore.bot.infrastructure.config.BotProperties$SelfEvolvingBootstrapTacticEmbeddingsLocalProperties",
            "me.golemcore.bot.domain.selfevolving.SelfEvolvingBootstrapOverrideService -> me.golemcore.bot.infrastructure.config.BotProperties$SelfEvolvingBootstrapTacticEmbeddingsProperties",
            "me.golemcore.bot.domain.selfevolving.SelfEvolvingBootstrapOverrideService -> me.golemcore.bot.infrastructure.config.BotProperties$SelfEvolvingBootstrapTacticSearchProperties",
            "me.golemcore.bot.domain.selfevolving.SelfEvolvingBootstrapOverrideService -> me.golemcore.bot.infrastructure.config.BotProperties$SelfEvolvingBootstrapTacticsProperties",
            "me.golemcore.bot.domain.selfevolving.SelfEvolvingBootstrapOverrideService -> me.golemcore.bot.infrastructure.config.BotProperties$SelfEvolvingBootstrapToggleProperties",
            "me.golemcore.bot.domain.selfevolving.SelfEvolvingBootstrapOverrideService -> me.golemcore.bot.infrastructure.config.BotProperties$SelfEvolvingProperties",
            "me.golemcore.bot.domain.service.DashboardAuthService -> me.golemcore.bot.adapter.inbound.web.security.JwtTokenProvider",
            "me.golemcore.bot.domain.service.DashboardAuthService -> me.golemcore.bot.infrastructure.config.BotProperties",
            "me.golemcore.bot.domain.service.DashboardAuthService -> me.golemcore.bot.infrastructure.config.BotProperties$DashboardProperties",
            "me.golemcore.bot.domain.service.DelayedActionDispatcher -> me.golemcore.bot.plugin.runtime.ChannelRegistry",
            "me.golemcore.bot.domain.service.DelayedActionPolicyService -> me.golemcore.bot.adapter.inbound.web.WebChannelAdapter",
            "me.golemcore.bot.domain.service.DelayedActionPolicyService -> me.golemcore.bot.adapter.inbound.webhook.WebhookChannelAdapter",
            "me.golemcore.bot.domain.service.DelayedActionPolicyService -> me.golemcore.bot.plugin.runtime.ChannelRegistry",
            "me.golemcore.bot.domain.service.HiveBootstrapConfigSynchronizer -> me.golemcore.bot.infrastructure.config.BotProperties",
            "me.golemcore.bot.domain.service.HiveBootstrapConfigSynchronizer -> me.golemcore.bot.infrastructure.config.BotProperties$HiveProperties",
            "me.golemcore.bot.domain.service.HiveConnectionService -> me.golemcore.bot.infrastructure.config.BotProperties",
            "me.golemcore.bot.domain.service.HiveConnectionService -> me.golemcore.bot.infrastructure.config.BotProperties$HiveProperties",
            "me.golemcore.bot.domain.service.HiveConnectionService -> me.golemcore.bot.plugin.runtime.ChannelRegistry",
            "me.golemcore.bot.domain.service.ModelRegistryService -> me.golemcore.bot.infrastructure.config.ModelConfigService$ModelSettings",
            "me.golemcore.bot.domain.service.ModelRegistryService$ResolveResult -> me.golemcore.bot.infrastructure.config.ModelConfigService$ModelSettings",
            "me.golemcore.bot.domain.service.ModelSelectionService -> me.golemcore.bot.infrastructure.config.ModelConfigService$ModelSettings",
            "me.golemcore.bot.domain.service.PromptSectionService -> me.golemcore.bot.infrastructure.config.BotProperties",
            "me.golemcore.bot.domain.service.PromptSectionService -> me.golemcore.bot.infrastructure.config.BotProperties$PromptsProperties",
            "me.golemcore.bot.domain.service.ProviderModelDiscoveryService -> me.golemcore.bot.infrastructure.config.ModelConfigService$ModelSettings",
            "me.golemcore.bot.domain.service.ProviderModelDiscoveryService -> me.golemcore.bot.infrastructure.config.ModelConfigService$ReasoningConfig",
            "me.golemcore.bot.domain.service.ProviderModelDiscoveryService -> me.golemcore.bot.infrastructure.config.ModelConfigService$ReasoningLevelConfig",
            "me.golemcore.bot.domain.service.ProviderModelDiscoveryService$DiscoveredModel -> me.golemcore.bot.infrastructure.config.ModelConfigService$ModelSettings",
            "me.golemcore.bot.domain.service.SkillMarketplaceService -> me.golemcore.bot.infrastructure.config.BotProperties",
            "me.golemcore.bot.domain.service.SkillMarketplaceService -> me.golemcore.bot.infrastructure.config.BotProperties$SkillsProperties",
            "me.golemcore.bot.domain.service.SkillService -> me.golemcore.bot.infrastructure.config.BotProperties",
            "me.golemcore.bot.domain.service.SkillService -> me.golemcore.bot.infrastructure.config.BotProperties$SkillsProperties",
            "me.golemcore.bot.domain.service.ToolCallExecutionService -> me.golemcore.bot.infrastructure.config.BotProperties",
            "me.golemcore.bot.domain.service.ToolCallExecutionService -> me.golemcore.bot.infrastructure.config.BotProperties$AutoCompactProperties",
            "me.golemcore.bot.domain.service.TurnProgressService -> me.golemcore.bot.plugin.runtime.ChannelRegistry",
            "me.golemcore.bot.domain.service.UpdateRuntimeCleanupService -> me.golemcore.bot.infrastructure.config.BotProperties",
            "me.golemcore.bot.domain.service.UpdateRuntimeCleanupService -> me.golemcore.bot.infrastructure.config.BotProperties$UpdateProperties",
            "me.golemcore.bot.domain.service.UpdateService -> me.golemcore.bot.infrastructure.config.BotProperties",
            "me.golemcore.bot.domain.service.UpdateService -> me.golemcore.bot.infrastructure.config.BotProperties$UpdateProperties",
            "me.golemcore.bot.domain.service.UserPreferencesService -> me.golemcore.bot.infrastructure.i18n.MessageService",
            "me.golemcore.bot.domain.service.WorkspaceInstructionService -> me.golemcore.bot.infrastructure.config.BotProperties",
            "me.golemcore.bot.domain.service.WorkspaceInstructionService -> me.golemcore.bot.infrastructure.config.BotProperties$FileSystemToolProperties",
            "me.golemcore.bot.domain.service.WorkspaceInstructionService -> me.golemcore.bot.infrastructure.config.BotProperties$ShellToolProperties",
            "me.golemcore.bot.domain.service.WorkspaceInstructionService -> me.golemcore.bot.infrastructure.config.BotProperties$ToolsProperties",
            "me.golemcore.bot.domain.service.WorkspacePathService -> me.golemcore.bot.infrastructure.config.BotProperties",
            "me.golemcore.bot.domain.service.WorkspacePathService -> me.golemcore.bot.infrastructure.config.BotProperties$FileSystemToolProperties",
            "me.golemcore.bot.domain.service.WorkspacePathService -> me.golemcore.bot.infrastructure.config.BotProperties$ToolsProperties",
            "me.golemcore.bot.domain.system.ResponseRoutingSystem -> me.golemcore.bot.plugin.runtime.ChannelRegistry",
            "me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem -> me.golemcore.bot.infrastructure.config.BotProperties$ToolLoopProperties",
            "me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem -> me.golemcore.bot.infrastructure.config.BotProperties$TurnProperties",
            "me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem$Builder -> me.golemcore.bot.infrastructure.config.BotProperties$ToolLoopProperties",
            "me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem$Builder -> me.golemcore.bot.infrastructure.config.BotProperties$TurnProperties");

    private static final Set<String> KNOWN_PORT_FORBIDDEN_DEPENDENCIES = dependencySet(
            "me.golemcore.bot.port.outbound.HiveEventPublishPort -> me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticDto",
            "me.golemcore.bot.port.outbound.ModelConfigPort -> me.golemcore.bot.infrastructure.config.ModelConfigService$ModelSettings");

    @Test
    void domain_should_not_depend_on_adapter_plugin_or_infrastructure_packages() {
        assertForbiddenDependenciesMatchDebtLedger(DOMAIN_PACKAGE, KNOWN_DOMAIN_FORBIDDEN_DEPENDENCIES);
    }

    @Test
    void port_should_not_depend_on_adapter_plugin_or_infrastructure_packages() {
        assertForbiddenDependenciesMatchDebtLedger(PORT_PACKAGE, KNOWN_PORT_FORBIDDEN_DEPENDENCIES);
    }

    private void assertForbiddenDependenciesMatchDebtLedger(String packagePrefix, Set<String> expectedDependencies) {
        JavaClasses importedClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(ROOT_PACKAGE);

        Set<String> actualDependencies = importedClasses.stream()
                .filter(javaClass -> javaClass.getPackageName().startsWith(packagePrefix))
                .flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
                .filter(dependency -> dependency.getOriginClass().getPackageName().startsWith(packagePrefix))
                .filter(dependency -> hasForbiddenPrefix(dependency.getTargetClass()))
                .map(this::formatDependency)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(
                new TreeSet<>(expectedDependencies),
                actualDependencies,
                () -> buildMismatchMessage(packagePrefix, actualDependencies));
    }

    private boolean hasForbiddenPrefix(JavaClass targetClass) {
        String packageName = targetClass.getPackageName();
        return FORBIDDEN_PREFIXES.stream().anyMatch(packageName::startsWith);
    }

    private String formatDependency(Dependency dependency) {
        return dependency.getOriginClass().getFullName() + " -> " + dependency.getTargetClass().getFullName();
    }

    private String buildMismatchMessage(String packagePrefix, Set<String> actualDependencies) {
        Set<String> unexpectedDependencies = new TreeSet<>(actualDependencies);
        unexpectedDependencies.removeAll(expectedDependenciesFor(packagePrefix));

        Set<String> obsoleteDependencies = new TreeSet<>(expectedDependenciesFor(packagePrefix));
        obsoleteDependencies.removeAll(actualDependencies);

        return "Forbidden dependency ledger mismatch for " + packagePrefix + ".\n"
                + formatSection("Unexpected new dependencies", unexpectedDependencies)
                + formatSection("Obsolete ledger entries", obsoleteDependencies)
                + "Update the code to remove new violations or refresh the debt ledger after refactoring.";
    }

    private Set<String> expectedDependenciesFor(String packagePrefix) {
        if (DOMAIN_PACKAGE.equals(packagePrefix)) {
            return KNOWN_DOMAIN_FORBIDDEN_DEPENDENCIES;
        }
        if (PORT_PACKAGE.equals(packagePrefix)) {
            return KNOWN_PORT_FORBIDDEN_DEPENDENCIES;
        }
        throw new IllegalArgumentException("Unsupported package prefix: " + packagePrefix);
    }

    private String formatSection(String title, Set<String> dependencies) {
        if (dependencies.isEmpty()) {
            return title + ": none\n";
        }
        return title + ":\n" + String.join("\n", dependencies) + "\n";
    }

    private static Set<String> dependencySet(String... dependencies) {
        return Set.copyOf(List.of(dependencies));
    }
}
