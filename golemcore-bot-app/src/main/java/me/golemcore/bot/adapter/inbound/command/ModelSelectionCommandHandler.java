package me.golemcore.bot.adapter.inbound.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.golemcore.bot.application.command.ModelSelectionCommandService;
import me.golemcore.bot.domain.command.CommandInvocation;
import me.golemcore.bot.domain.command.CommandOutcome;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.inbound.CommandPort;
import org.springframework.stereotype.Component;

@Component
class ModelSelectionCommandHandler implements CommandHandler {

    private static final int MIN_REASONING_ARGS = 2;
    private static final String SUBCMD_RESET = "reset";
    private static final String SUBCMD_REASONING = "reasoning";
    private static final List<String> COMMAND_NAMES = List.of("tier", "model");

    private final ModelSelectionCommandService modelSelectionCommandService;
    private final UserPreferencesService preferencesService;

    public ModelSelectionCommandHandler(
            ModelSelectionCommandService modelSelectionCommandService,
            UserPreferencesService preferencesService) {
        this.modelSelectionCommandService = modelSelectionCommandService;
        this.preferencesService = preferencesService;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public List<String> commandNames() {
        return COMMAND_NAMES;
    }

    @Override
    public List<CommandPort.CommandDefinition> listCommands() {
        return List.of(
                new CommandPort.CommandDefinition(
                        "tier",
                        "Set model tier",
                        "/tier [" + String.join("|", ModelTierCatalog.orderedExplicitTiers()) + "] [force]"),
                new CommandPort.CommandDefinition(
                        "model",
                        "Per-tier model selection",
                        "/model [list|<tier> <model>|<tier> reasoning <level>|<tier> reset]"));
    }

    @Override
    public CommandOutcome handle(CommandInvocation invocation) {
        if ("tier".equals(invocation.command())) {
            return handleTier(invocation.args(), invocation.context().sessionId());
        }
        if ("model".equals(invocation.command())) {
            return handleModel(invocation.args());
        }
        return CommandOutcome.failure(msg("command.unknown", invocation.command()));
    }

    CommandOutcome handleTier(List<String> args) {
        return handleTier(args, null);
    }

    CommandOutcome handleTier(List<String> args, String sessionId) {
        if (args.isEmpty()) {
            return renderTierOutcome(
                    modelSelectionCommandService
                            .handleTier(new ModelSelectionCommandService.ShowTierStatus(sessionId)));
        }

        String tier = ModelTierCatalog.normalizeTierId(args.get(0));
        boolean force = args.size() > 1 && "force".equalsIgnoreCase(args.get(1));
        return renderTierOutcome(modelSelectionCommandService.handleTier(
                new ModelSelectionCommandService.SetTierSelection(tier, force, sessionId)));
    }

    CommandOutcome handleModel(List<String> args) {
        if (args.isEmpty()) {
            return renderModelOutcome(
                    modelSelectionCommandService.handleModel(new ModelSelectionCommandService.ShowModelSelection()));
        }

        String subcommand = ModelTierCatalog.normalizeTierId(args.get(0));
        if ("list".equals(subcommand)) {
            return renderModelOutcome(
                    modelSelectionCommandService.handleModel(new ModelSelectionCommandService.ListAvailableModels()));
        }
        if (!ModelTierCatalog.isExplicitSelectableTier(subcommand)) {
            return CommandOutcome.success(msg("command.model.invalid.tier"));
        }

        List<String> subArgs = args.subList(1, args.size());
        if (subArgs.isEmpty()) {
            return CommandOutcome.success(msg("command.model.usage"));
        }

        String action = subArgs.get(0).toLowerCase(Locale.ROOT);
        if (SUBCMD_RESET.equals(action)) {
            return renderModelOutcome(modelSelectionCommandService.handleModel(
                    new ModelSelectionCommandService.ResetModelOverride(subcommand)));
        }
        if (SUBCMD_REASONING.equals(action)) {
            if (subArgs.size() < MIN_REASONING_ARGS) {
                return CommandOutcome.success(msg("command.model.usage"));
            }
            return renderModelOutcome(modelSelectionCommandService.handleModel(
                    new ModelSelectionCommandService.SetReasoningLevel(subcommand,
                            subArgs.get(1).toLowerCase(Locale.ROOT))));
        }

        return renderModelOutcome(modelSelectionCommandService.handleModel(
                new ModelSelectionCommandService.SetModelOverride(subcommand, subArgs.get(0))));
    }

    private CommandOutcome renderTierOutcome(ModelSelectionCommandService.TierOutcome outcome) {
        if (outcome instanceof ModelSelectionCommandService.CurrentTier currentTier) {
            String force = currentTier.force() ? "on" : "off";
            return CommandOutcome.success(msg("command.tier.current", currentTier.tier(), force));
        }
        if (outcome instanceof ModelSelectionCommandService.TierUpdated tierUpdated) {
            if (tierUpdated.force()) {
                return CommandOutcome.success(msg("command.tier.set.force", tierUpdated.tier()));
            }
            return CommandOutcome.success(msg("command.tier.set", tierUpdated.tier()));
        }
        return CommandOutcome.success(msg("command.tier.invalid"));
    }

    private CommandOutcome renderModelOutcome(ModelSelectionCommandService.ModelOutcome outcome) {
        if (outcome instanceof ModelSelectionCommandService.ModelSelectionOverview overview) {
            return CommandOutcome.success(renderModelOverview(overview));
        }
        if (outcome instanceof ModelSelectionCommandService.AvailableModels availableModels) {
            return CommandOutcome.success(renderAvailableModels(availableModels));
        }
        if (outcome instanceof ModelSelectionCommandService.InvalidModelTier) {
            return CommandOutcome.success(msg("command.model.invalid.tier"));
        }
        if (outcome instanceof ModelSelectionCommandService.ModelOverrideSet modelOverrideSet) {
            String displayReasoning = modelOverrideSet.defaultReasoning() != null
                    ? " (reasoning: " + modelOverrideSet.defaultReasoning() + ")"
                    : "";
            return CommandOutcome.success(
                    msg("command.model.set", modelOverrideSet.tier(), modelOverrideSet.modelSpec()) + displayReasoning);
        }
        if (outcome instanceof ModelSelectionCommandService.ProviderNotConfigured providerNotConfigured) {
            return CommandOutcome.success(msg(
                    "command.model.invalid.provider",
                    providerNotConfigured.modelSpec(),
                    String.join(", ", providerNotConfigured.configuredProviders())));
        }
        if (outcome instanceof ModelSelectionCommandService.InvalidModel invalidModel) {
            return CommandOutcome.success(msg("command.model.invalid.model", invalidModel.modelSpec()));
        }
        if (outcome instanceof ModelSelectionCommandService.MissingModelOverride missingModelOverride) {
            return CommandOutcome.success(msg("command.model.no.override", missingModelOverride.tier()));
        }
        if (outcome instanceof ModelSelectionCommandService.MissingReasoningSupport missingReasoningSupport) {
            return CommandOutcome
                    .success(msg("command.model.no.reasoning", missingReasoningSupport.modelSpec()));
        }
        if (outcome instanceof ModelSelectionCommandService.InvalidReasoningLevel invalidReasoningLevel) {
            return CommandOutcome.success(msg(
                    "command.model.invalid.reasoning",
                    invalidReasoningLevel.requestedLevel(),
                    String.join(", ", invalidReasoningLevel.availableLevels())));
        }
        if (outcome instanceof ModelSelectionCommandService.ModelReasoningSet modelReasoningSet) {
            return CommandOutcome.success(msg(
                    "command.model.set.reasoning",
                    modelReasoningSet.tier(),
                    modelReasoningSet.level()));
        }
        ModelSelectionCommandService.ModelOverrideReset modelOverrideReset = (ModelSelectionCommandService.ModelOverrideReset) outcome;
        return CommandOutcome.success(msg("command.model.reset", modelOverrideReset.tier()));
    }

    private String renderModelOverview(ModelSelectionCommandService.ModelSelectionOverview overview) {
        StringBuilder builder = new StringBuilder();
        builder.append("**").append(msg("command.model.show.title")).append("**\n\n");

        for (ModelSelectionCommandService.TierSelection tierSelection : overview.tiers()) {
            String model = tierSelection.model() != null ? tierSelection.model() : "—";
            String reasoning = tierSelection.reasoning() != null ? tierSelection.reasoning() : "—";
            String messageKey = tierSelection.hasOverride()
                    ? "command.model.show.tier.override"
                    : "command.model.show.tier.default";
            builder.append(msg(messageKey, tierSelection.tier(), model, reasoning)).append("\n");
        }

        return builder.toString();
    }

    private String renderAvailableModels(ModelSelectionCommandService.AvailableModels availableModels) {
        if (availableModels.modelsByProvider().isEmpty()) {
            return msg("command.model.list.title") + "\n\nNo models available.";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("**").append(msg("command.model.list.title")).append("**\n\n");

        for (Map.Entry<String, List<ModelSelectionCommandService.AvailableModelOption>> entry : availableModels
                .modelsByProvider().entrySet()) {
            builder.append(msg("command.model.list.provider", entry.getKey())).append("\n");
            for (ModelSelectionCommandService.AvailableModelOption model : entry.getValue()) {
                String reasoningInfo = model.hasReasoning()
                        ? " [reasoning: " + String.join(", ", model.reasoningLevels()) + "]"
                        : "";
                builder.append(msg("command.model.list.model", model.id(), model.displayName(), reasoningInfo))
                        .append("\n");
            }
            builder.append("\n");
        }
        return builder.toString();
    }

    private String msg(String key, Object... args) {
        return preferencesService.getMessage(key, args);
    }
}
