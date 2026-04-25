package me.golemcore.bot.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import me.golemcore.bot.adapter.outbound.config.BotPropertiesSettingsAdapter;
import me.golemcore.bot.adapter.outbound.i18n.MessageLocalizationAdapter;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.i18n.MessageService;
import me.golemcore.bot.port.outbound.LocalizationPort;
import me.golemcore.bot.port.outbound.ToolRuntimeSettingsPort;
import org.junit.jupiter.api.Test;

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
                properties.getMaxLlmCalls(),
                properties.getMaxToolExecutions(),
                properties.isStopOnToolFailure(),
                properties.isStopOnConfirmationDenied(),
                properties.isStopOnToolPolicyDenied());
    }

    public static LocalizationPort localization(MessageService messageService) {
        return new MessageLocalizationAdapter(messageService);
    }

    @Test
    void shouldBuildDefaultPortsAndAdapters() {
        assertEquals(ToolRuntimeSettingsPort.defaultTurnSettings(), turn(null));
        assertEquals(ToolRuntimeSettingsPort.defaultToolLoopSettings(), toolLoop(null));
        assertEquals(BotPropertiesSettingsAdapter.class, settings(new BotProperties()).getClass());
        assertEquals(MessageLocalizationAdapter.class, localization(mock(MessageService.class)).getClass());
    }
}
