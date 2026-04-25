package me.golemcore.bot.client.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload used by the dashboard settings API to update persisted user
 * preferences.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PreferencesUpdateRequest {
    /** Preferred UI language code. */
    private String language;
    /** Preferred IANA timezone identifier. */
    private String timezone;
    /** Whether interactive notifications remain enabled for the user. */
    private Boolean notificationsEnabled;
    /** Optional explicit model tier selected in the dashboard. */
    private String modelTier;
    /** Whether the selected tier is forced and should ignore dynamic overrides. */
    private Boolean tierForce;
    /** Optional memory preset id used by interactive dashboard chat. */
    private String memoryPreset;
}
