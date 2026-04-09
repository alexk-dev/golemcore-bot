package me.golemcore.bot.application.command;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ModelSelectionCommandService {

    private static final String SUBCMD_LIST = "list";
    private static final String SUBCMD_RESET = "reset";
    private static final String SUBCMD_REASONING = "reasoning";
    private static final int MIN_REASONING_ARGS = 2;
    private static final String ERR_PROVIDER_NOT_CONFIGURED = "provider.not.configured";
    private static final String ERR_NO_REASONING = "no.reasoning";

    private final UserPreferencesService preferencesService;
    private final ModelSelectionService modelSelectionService;
    private final RuntimeConfigService runtimeConfigService;

    public CommandOutcome handleTier(List<String> args) {
        UserPreferences preferences = preferencesService.getPreferences();

        if (args.isEmpty()) {
            String tier = preferences.getModelTier() != null ? preferences.getModelTier() : "balanced";
            String force = preferences.isTierForce() ? "on" : "off";
            return CommandOutcome.success(msg("command.tier.current", tier, force));
        }

        String tierArg = ModelTierCatalog.normalizeTierId(args.get(0));
        if (!ModelTierCatalog.isExplicitSelectableTier(tierArg)) {
            return CommandOutcome.success(msg("command.tier.invalid"));
        }

        boolean force = args.size() > 1 && "force".equalsIgnoreCase(args.get(1));
        preferences.setModelTier(tierArg);
        preferences.setTierForce(force);
        preferencesService.savePreferences(preferences);

        if (force) {
            return CommandOutcome.success(msg("command.tier.set.force", tierArg));
        }
        return CommandOutcome.success(msg("command.tier.set", tierArg));
    }

    public CommandOutcome handleModel(List<String> args) {
        if (args.isEmpty()) {
            return handleModelShow();
        }

        String subcommand = ModelTierCatalog.normalizeTierId(args.get(0));
        if (SUBCMD_LIST.equals(subcommand)) {
            return handleModelList();
        }
        if (!ModelTierCatalog.isExplicitSelectableTier(subcommand)) {
            return CommandOutcome.success(msg("command.model.invalid.tier"));
        }

        String tier = subcommand;
        List<String> subArgs = args.subList(1, args.size());
        if (subArgs.isEmpty()) {
            return CommandOutcome.success(msg("command.model.usage"));
        }

        String action = subArgs.get(0).toLowerCase(Locale.ROOT);
        if (SUBCMD_RESET.equals(action)) {
            return handleModelReset(tier);
        }
        if (SUBCMD_REASONING.equals(action)) {
            if (subArgs.size() < MIN_REASONING_ARGS) {
                return CommandOutcome.success(msg("command.model.usage"));
            }
            return handleModelSetReasoning(tier, subArgs.get(1).toLowerCase(Locale.ROOT));
        }

        return handleModelSet(tier, subArgs.get(0));
    }

    private CommandOutcome handleModelShow() {
        UserPreferences preferences = preferencesService.getPreferences();
        StringBuilder builder = new StringBuilder();
        builder.append("**").append(msg("command.model.show.title")).append("**\n\n");

        for (String tier : ModelTierCatalog.orderedExplicitTiers()) {
            ModelSelectionService.ModelSelection selection = modelSelectionService.resolveForTier(tier);
            String model = selection.model() != null ? selection.model() : "—";
            String reasoning = selection.reasoning() != null ? selection.reasoning() : "—";
            boolean hasOverride = preferences.getTierOverrides() != null
                    && preferences.getTierOverrides().containsKey(tier);
            String messageKey = hasOverride ? "command.model.show.tier.override" : "command.model.show.tier.default";
            builder.append(msg(messageKey, tier, model, reasoning)).append("\n");
        }

        return CommandOutcome.success(builder.toString());
    }

    private CommandOutcome handleModelList() {
        Map<String, List<ModelSelectionService.AvailableModel>> grouped = modelSelectionService
                .getAvailableModelsGrouped();
        if (grouped.isEmpty()) {
            return CommandOutcome.success(msg("command.model.list.title") + "\n\nNo models available.");
        }

        StringBuilder builder = new StringBuilder();
        builder.append("**").append(msg("command.model.list.title")).append("**\n\n");

        for (Map.Entry<String, List<ModelSelectionService.AvailableModel>> entry : grouped.entrySet()) {
            builder.append(msg("command.model.list.provider", entry.getKey())).append("\n");
            for (ModelSelectionService.AvailableModel model : entry.getValue()) {
                String reasoningInfo = model.hasReasoning()
                        ? " [reasoning: " + String.join(", ", model.reasoningLevels()) + "]"
                        : "";
                builder.append(msg("command.model.list.model", model.id(), model.displayName(), reasoningInfo))
                        .append("\n");
            }
            builder.append("\n");
        }

        return CommandOutcome.success(builder.toString());
    }

    private CommandOutcome handleModelSet(String tier, String modelSpec) {
        ModelSelectionService.ValidationResult validation = modelSelectionService.validateModel(modelSpec);
        if (!validation.valid()) {
            if (ERR_PROVIDER_NOT_CONFIGURED.equals(validation.error())) {
                String configuredProviders = String.join(", ", runtimeConfigService.getConfiguredLlmProviders());
                return CommandOutcome.success(
                        msg("command.model.invalid.provider", modelSpec, configuredProviders));
            }
            return CommandOutcome.success(msg("command.model.invalid.model", modelSpec));
        }

        UserPreferences preferences = preferencesService.getPreferences();
        String defaultReasoning = findDefaultReasoning(modelSpec);
        UserPreferences.TierOverride override = new UserPreferences.TierOverride(modelSpec, defaultReasoning);
        preferences.getTierOverrides().put(tier, override);
        preferencesService.savePreferences(preferences);

        String displayReasoning = defaultReasoning != null ? " (reasoning: " + defaultReasoning + ")" : "";
        return CommandOutcome.success(msg("command.model.set", tier, modelSpec) + displayReasoning);
    }

    private CommandOutcome handleModelSetReasoning(String tier, String level) {
        UserPreferences preferences = preferencesService.getPreferences();
        UserPreferences.TierOverride existing = preferences.getTierOverrides() != null
                ? preferences.getTierOverrides().get(tier)
                : null;
        if (existing == null || existing.getModel() == null) {
            return CommandOutcome.success(msg("command.model.no.override", tier));
        }

        ModelSelectionService.ValidationResult validation = modelSelectionService.validateReasoning(
                existing.getModel(), level);
        if (!validation.valid()) {
            if (ERR_NO_REASONING.equals(validation.error())) {
                return CommandOutcome.success(msg("command.model.no.reasoning", existing.getModel()));
            }
            List<String> available = modelSelectionService.getAvailableModels().stream()
                    .filter(model -> model.id().equals(existing.getModel())
                            || existing.getModel().endsWith("/" + model.id()))
                    .flatMap(model -> model.reasoningLevels().stream())
                    .toList();
            return CommandOutcome.success(
                    msg("command.model.invalid.reasoning", level, String.join(", ", available)));
        }

        existing.setReasoning(level);
        preferencesService.savePreferences(preferences);
        return CommandOutcome.success(msg("command.model.set.reasoning", tier, level));
    }

    private CommandOutcome handleModelReset(String tier) {
        UserPreferences preferences = preferencesService.getPreferences();
        if (preferences.getTierOverrides() != null) {
            preferences.getTierOverrides().remove(tier);
            preferencesService.savePreferences(preferences);
        }
        return CommandOutcome.success(msg("command.model.reset", tier));
    }

    private String findDefaultReasoning(String modelSpec) {
        List<ModelSelectionService.AvailableModel> models = modelSelectionService.getAvailableModels();
        for (ModelSelectionService.AvailableModel model : models) {
            if (model.id().equals(modelSpec) || modelSpec.endsWith("/" + model.id())) {
                if (model.hasReasoning() && !model.reasoningLevels().isEmpty()) {
                    List<String> levels = model.reasoningLevels();
                    return levels.contains("medium") ? "medium" : levels.get(0);
                }
            }
        }
        return null;
    }

    private String msg(String key, Object... args) {
        return preferencesService.getMessage(key, args);
    }

    public record CommandOutcome(
            boolean success,
            String output
    ) {
        public static CommandOutcome success(String output) {
            return new CommandOutcome(true, output);
        }

        public static CommandOutcome failure(String output) {
            return new CommandOutcome(false, output);
        }
    }
}
