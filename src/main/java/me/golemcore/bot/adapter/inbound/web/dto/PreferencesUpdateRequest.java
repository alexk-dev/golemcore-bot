package me.golemcore.bot.adapter.inbound.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PreferencesUpdateRequest {
    private String language;
    private String timezone;
    private Boolean notificationsEnabled;
    private String modelTier;
    private Boolean tierForce;
}
