package me.golemcore.bot.support;

import me.golemcore.bot.adapter.outbound.config.BotPropertiesSettingsAdapter;
import me.golemcore.bot.adapter.outbound.i18n.MessageLocalizationAdapter;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.i18n.MessageService;
import me.golemcore.bot.port.outbound.BotSettingsPort;
import me.golemcore.bot.port.outbound.LocalizationPort;

public final class TestPorts {

    private TestPorts() {
    }

    public static BotSettingsPort settings(BotProperties properties) {
        return new BotPropertiesSettingsAdapter(properties);
    }

    public static BotSettingsPort.TurnSettings turn(BotProperties.TurnProperties properties) {
        if (properties == null) {
            return BotSettingsPort.defaultTurnSettings();
        }
        return new BotSettingsPort.TurnSettings(
                properties.getMaxLlmCalls(),
                properties.getMaxToolExecutions(),
                properties.getDeadline());
    }

    public static BotSettingsPort.ToolLoopSettings toolLoop(BotProperties.ToolLoopProperties properties) {
        if (properties == null) {
            return BotSettingsPort.defaultToolLoopSettings();
        }
        return new BotSettingsPort.ToolLoopSettings(
                properties.isStopOnToolFailure(),
                properties.isStopOnConfirmationDenied(),
                properties.isStopOnToolPolicyDenied());
    }

    public static LocalizationPort localization(MessageService messageService) {
        return new MessageLocalizationAdapter(messageService);
    }
}
