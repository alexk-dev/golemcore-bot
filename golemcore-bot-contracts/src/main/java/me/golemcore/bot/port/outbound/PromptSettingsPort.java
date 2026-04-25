package me.golemcore.bot.port.outbound;

import java.util.Map;

/**
 * Domain-facing access to prompt section settings.
 */
public interface PromptSettingsPort {

    PromptSettings prompts();

    record PromptSettings(boolean enabled, String botName, Map<String, String> customVars) {
        public PromptSettings {
            customVars = customVars != null ? Map.copyOf(customVars) : Map.of();
        }
    }
}
