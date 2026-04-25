package me.golemcore.bot.port.outbound;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRuntimeSettingsPortTest {

    @Test
    void shouldDefaultTurnDeadlineWhenMissing() {
        ToolRuntimeSettingsPort.TurnSettings settings = new ToolRuntimeSettingsPort.TurnSettings(5, 10, null);

        assertEquals(Duration.ofHours(1), settings.deadline());
    }

    @Test
    void shouldKeepLegacyToolLoopPolicyConstructorDefaults() {
        ToolRuntimeSettingsPort.ToolLoopSettings settings = new ToolRuntimeSettingsPort.ToolLoopSettings(
                true, false, true);

        assertEquals(20, settings.maxLlmCalls());
        assertEquals(80, settings.maxToolExecutions());
        assertTrue(settings.stopOnToolFailure());
        assertFalse(settings.stopOnConfirmationDenied());
        assertTrue(settings.stopOnToolPolicyDenied());
    }
}
