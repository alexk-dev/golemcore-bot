package me.golemcore.bot.adapter.outbound.config;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.BotSettingsPort;
import org.springframework.stereotype.Component;

/**
 * Maps Spring-bound {@link BotProperties} into domain-facing settings records.
 */
@Component
@RequiredArgsConstructor
public class BotPropertiesSettingsAdapter implements BotSettingsPort {

    private final BotProperties botProperties;

    @Override
    public MemorySettings memory() {
        BotProperties.MemoryProperties properties = botProperties.getMemory();
        return new MemorySettings(properties != null ? properties.getDirectory() : null);
    }

    @Override
    public SkillSettings skills() {
        BotProperties.SkillsProperties properties = botProperties.getSkills();
        if (properties == null) {
            return new SkillSettings(null, MarketplaceSettings.disabled());
        }
        return new SkillSettings(
                properties.getDirectory(),
                new MarketplaceSettings(
                        properties.isMarketplaceEnabled(),
                        properties.getMarketplaceRepositoryDirectory(),
                        properties.getMarketplaceSandboxPath(),
                        properties.getMarketplaceRepositoryUrl(),
                        properties.getMarketplaceBranch(),
                        properties.getMarketplaceApiBaseUrl(),
                        properties.getMarketplaceRawBaseUrl(),
                        properties.getMarketplaceRemoteCacheTtl()));
    }

    @Override
    public PromptSettings prompts() {
        BotProperties.PromptsProperties properties = botProperties.getPrompts();
        if (properties == null) {
            return new PromptSettings(true, "AI Assistant", Map.of());
        }
        return new PromptSettings(properties.isEnabled(), properties.getBotName(), properties.getCustomVars());
    }

    @Override
    public ToolExecutionSettings toolExecution() {
        BotProperties.AutoCompactProperties properties = botProperties.getAutoCompact();
        return new ToolExecutionSettings(properties != null ? properties.getMaxToolResultChars() : 100000);
    }

    @Override
    public UpdateSettings update() {
        BotProperties.UpdateProperties properties = botProperties.getUpdate();
        if (properties == null) {
            return new UpdateSettings(true, null, 3, null);
        }
        return new UpdateSettings(
                properties.isEnabled(),
                properties.getUpdatesPath(),
                properties.getMaxKeptVersions(),
                properties.getCheckInterval());
    }

    @Override
    public WorkspaceSettings workspace() {
        BotProperties.ToolsProperties tools = botProperties.getTools();
        BotProperties.FileSystemToolProperties filesystem = tools != null ? tools.getFilesystem() : null;
        BotProperties.ShellToolProperties shell = tools != null ? tools.getShell() : null;
        return new WorkspaceSettings(
                filesystem != null ? filesystem.getWorkspace() : null,
                shell != null ? shell.getWorkspace() : null);
    }

    @Override
    public TurnSettings turn() {
        BotProperties.TurnProperties properties = botProperties.getTurn();
        if (properties == null) {
            return BotSettingsPort.defaultTurnSettings();
        }
        return new TurnSettings(properties.getMaxLlmCalls(), properties.getMaxToolExecutions(),
                properties.getDeadline());
    }

    @Override
    public ToolLoopSettings toolLoop() {
        BotProperties.ToolLoopProperties properties = botProperties.getToolLoop();
        if (properties == null) {
            return BotSettingsPort.defaultToolLoopSettings();
        }
        return new ToolLoopSettings(
                properties.isStopOnToolFailure(),
                properties.isStopOnConfirmationDenied(),
                properties.isStopOnToolPolicyDenied());
    }

    @Override
    public SelfEvolvingBootstrapSettings selfEvolvingBootstrap() {
        BotProperties.SelfEvolvingProperties selfEvolving = botProperties.getSelfEvolving();
        BotProperties.SelfEvolvingBootstrapProperties bootstrap = selfEvolving != null ? selfEvolving.getBootstrap()
                : null;
        if (bootstrap == null) {
            return new SelfEvolvingBootstrapSettings(null, null);
        }
        return new SelfEvolvingBootstrapSettings(
                bootstrap.getEnabled(),
                new TacticsSettings(
                        tacticsEnabled(bootstrap),
                        new SearchSettings(
                                searchMode(bootstrap),
                                new EmbeddingsSettings(
                                        embeddingsProvider(bootstrap),
                                        embeddingsBaseUrl(bootstrap),
                                        embeddingsApiKey(bootstrap),
                                        embeddingsModel(bootstrap),
                                        embeddingsDimensions(bootstrap),
                                        embeddingsBatchSize(bootstrap),
                                        embeddingsTimeoutMs(bootstrap),
                                        new LocalEmbeddingsSettings(
                                                localAutoInstall(bootstrap),
                                                localPullOnStart(bootstrap),
                                                localRequireHealthyRuntime(bootstrap),
                                                localFailOpen(bootstrap),
                                                localStartupTimeoutMs(bootstrap),
                                                localInitialRestartBackoffMs(bootstrap),
                                                localMinimumRuntimeVersion(bootstrap))),
                                new ToggleSettings(personalizationEnabled(bootstrap)),
                                new ToggleSettings(negativeMemoryEnabled(bootstrap)))));
    }

    private Boolean tacticsEnabled(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticsProperties tactics = bootstrap.getTactics();
        return tactics != null ? tactics.getEnabled() : null;
    }

    private String searchMode(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticSearchProperties search = tacticSearch(bootstrap);
        return search != null ? search.getMode() : null;
    }

    private String embeddingsProvider(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticEmbeddingsProperties embeddings = tacticEmbeddings(bootstrap);
        return embeddings != null ? embeddings.getProvider() : null;
    }

    private String embeddingsBaseUrl(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticEmbeddingsProperties embeddings = tacticEmbeddings(bootstrap);
        return embeddings != null ? embeddings.getBaseUrl() : null;
    }

    private String embeddingsApiKey(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticEmbeddingsProperties embeddings = tacticEmbeddings(bootstrap);
        return embeddings != null ? embeddings.getApiKey() : null;
    }

    private String embeddingsModel(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticEmbeddingsProperties embeddings = tacticEmbeddings(bootstrap);
        return embeddings != null ? embeddings.getModel() : null;
    }

    private Integer embeddingsDimensions(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticEmbeddingsProperties embeddings = tacticEmbeddings(bootstrap);
        return embeddings != null ? embeddings.getDimensions() : null;
    }

    private Integer embeddingsBatchSize(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticEmbeddingsProperties embeddings = tacticEmbeddings(bootstrap);
        return embeddings != null ? embeddings.getBatchSize() : null;
    }

    private Integer embeddingsTimeoutMs(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticEmbeddingsProperties embeddings = tacticEmbeddings(bootstrap);
        return embeddings != null ? embeddings.getTimeoutMs() : null;
    }

    private Boolean localAutoInstall(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticEmbeddingsLocalProperties local = tacticEmbeddingsLocal(bootstrap);
        return local != null ? local.getAutoInstall() : null;
    }

    private Boolean localPullOnStart(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticEmbeddingsLocalProperties local = tacticEmbeddingsLocal(bootstrap);
        return local != null ? local.getPullOnStart() : null;
    }

    private Boolean localRequireHealthyRuntime(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticEmbeddingsLocalProperties local = tacticEmbeddingsLocal(bootstrap);
        return local != null ? local.getRequireHealthyRuntime() : null;
    }

    private Boolean localFailOpen(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticEmbeddingsLocalProperties local = tacticEmbeddingsLocal(bootstrap);
        return local != null ? local.getFailOpen() : null;
    }

    private Integer localStartupTimeoutMs(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticEmbeddingsLocalProperties local = tacticEmbeddingsLocal(bootstrap);
        return local != null ? local.getStartupTimeoutMs() : null;
    }

    private Integer localInitialRestartBackoffMs(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticEmbeddingsLocalProperties local = tacticEmbeddingsLocal(bootstrap);
        return local != null ? local.getInitialRestartBackoffMs() : null;
    }

    private String localMinimumRuntimeVersion(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticEmbeddingsLocalProperties local = tacticEmbeddingsLocal(bootstrap);
        return local != null ? local.getMinimumRuntimeVersion() : null;
    }

    private Boolean personalizationEnabled(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapToggleProperties toggle = personalization(bootstrap);
        return toggle != null ? toggle.getEnabled() : null;
    }

    private Boolean negativeMemoryEnabled(BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapToggleProperties toggle = negativeMemory(bootstrap);
        return toggle != null ? toggle.getEnabled() : null;
    }

    private BotProperties.SelfEvolvingBootstrapTacticsProperties tactics(
            BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        return bootstrap.getTactics();
    }

    private BotProperties.SelfEvolvingBootstrapTacticSearchProperties tacticSearch(
            BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticsProperties tactics = tactics(bootstrap);
        return tactics != null ? tactics.getSearch() : null;
    }

    private BotProperties.SelfEvolvingBootstrapTacticEmbeddingsProperties tacticEmbeddings(
            BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticSearchProperties search = tacticSearch(bootstrap);
        return search != null ? search.getEmbeddings() : null;
    }

    private BotProperties.SelfEvolvingBootstrapTacticEmbeddingsLocalProperties tacticEmbeddingsLocal(
            BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticEmbeddingsProperties embeddings = tacticEmbeddings(bootstrap);
        return embeddings != null ? embeddings.getLocal() : null;
    }

    private BotProperties.SelfEvolvingBootstrapToggleProperties personalization(
            BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticSearchProperties search = tacticSearch(bootstrap);
        return search != null ? search.getPersonalization() : null;
    }

    private BotProperties.SelfEvolvingBootstrapToggleProperties negativeMemory(
            BotProperties.SelfEvolvingBootstrapProperties bootstrap) {
        BotProperties.SelfEvolvingBootstrapTacticSearchProperties search = tacticSearch(bootstrap);
        return search != null ? search.getNegativeMemory() : null;
    }
}
