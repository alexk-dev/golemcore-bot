package me.golemcore.bot.support;

import me.golemcore.bot.adapter.outbound.config.BotPropertiesSettingsAdapter;
import me.golemcore.bot.adapter.outbound.i18n.MessageLocalizationAdapter;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.i18n.MessageService;
import me.golemcore.bot.port.outbound.LocalizationPort;
import me.golemcore.bot.port.outbound.ToolRuntimeSettingsPort;

public final class TestPorts {

    private TestPorts() {
    }

    public static BotPropertiesSettingsAdapter settings(BotProperties properties) {
        return new BotPropertiesSettingsAdapter(properties);
    }

    public static ToolRuntimeSettingsPort.TurnSettings turn(BotProperties.TurnProperties properties) {
        if (properties == null) {
            return ToolRuntimeSettingsPort.defaultTurnSettings();
        }
        return new ToolRuntimeSettingsPort.TurnSettings(
                properties.getMaxLlmCalls(),
                properties.getMaxToolExecutions(),
                properties.getDeadline());
    }

    public static ToolRuntimeSettingsPort.ToolLoopSettings toolLoop(BotProperties.ToolLoopProperties properties) {
        if (properties == null) {
            return ToolRuntimeSettingsPort.defaultToolLoopSettings();
        }
        return new ToolRuntimeSettingsPort.ToolLoopSettings(
                properties.isStopOnToolFailure(),
                properties.isStopOnConfirmationDenied(),
                properties.isStopOnToolPolicyDenied());
    }

    public static LocalizationPort localization(MessageService messageService) {
        return new MessageLocalizationAdapter(messageService);
    }
}
