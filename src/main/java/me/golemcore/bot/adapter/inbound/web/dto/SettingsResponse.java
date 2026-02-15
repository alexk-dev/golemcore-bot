package me.golemcore.bot.adapter.inbound.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettingsResponse {
    private String language;
    private String timezone;
    private boolean notificationsEnabled;
    private String modelTier;
    private boolean tierForce;
    private Map<String, TierOverrideDto> tierOverrides;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TierOverrideDto {
        private String model;
        private String reasoning;
    }
}
