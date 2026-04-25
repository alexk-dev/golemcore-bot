package me.golemcore.bot.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.golemcore.bot.domain.model.UserPreferences;

import java.util.Map;

/**
 * Combined dashboard response with persisted user preferences and resolved
 * per-tier override settings.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettingsResponse {
    /** Preferred UI language code. */
    private String language;
    /** Preferred IANA timezone identifier. */
    private String timezone;
    /** Whether dashboard notifications are enabled. */
    private boolean notificationsEnabled;
    /** Selected model tier, if the user pinned one explicitly. */
    private String modelTier;
    /** Whether the selected tier should override dynamic tier resolution. */
    private boolean tierForce;
    /** Optional interactive chat memory preset id. */
    private String memoryPreset;
    /** Optional per-tier provider/model overrides keyed by tier id. */
    private Map<String, TierOverrideDto> tierOverrides;
    /** Persisted webhook preferences exposed in the settings dashboard. */
    private UserPreferences.WebhookConfig webhooks;

    /**
     * DTO describing an explicit provider/model override for a single model tier.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TierOverrideDto {
        /** Fully qualified model id, for example {@code openai/gpt-5.2}. */
        private String model;
        /** Optional reasoning level required by the selected model. */
        private String reasoning;
    }
}
