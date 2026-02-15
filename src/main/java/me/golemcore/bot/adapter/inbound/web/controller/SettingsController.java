package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.dto.PreferencesUpdateRequest;
import me.golemcore.bot.adapter.inbound.web.dto.SettingsResponse;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings and preferences management endpoints.
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final UserPreferencesService preferencesService;
    private final ModelSelectionService modelSelectionService;

    @GetMapping
    public Mono<ResponseEntity<SettingsResponse>> getSettings() {
        UserPreferences prefs = preferencesService.getPreferences();
        Map<String, SettingsResponse.TierOverrideDto> overrideDtos = new LinkedHashMap<>();
        if (prefs.getTierOverrides() != null) {
            prefs.getTierOverrides()
                    .forEach((tier, override) -> overrideDtos.put(tier, SettingsResponse.TierOverrideDto.builder()
                            .model(override.getModel())
                            .reasoning(override.getReasoning())
                            .build()));
        }
        SettingsResponse response = SettingsResponse.builder()
                .language(prefs.getLanguage())
                .timezone(prefs.getTimezone())
                .notificationsEnabled(prefs.isNotificationsEnabled())
                .modelTier(prefs.getModelTier())
                .tierForce(prefs.isTierForce())
                .tierOverrides(overrideDtos)
                .build();
        return Mono.just(ResponseEntity.ok(response));
    }

    @PutMapping("/preferences")
    public Mono<ResponseEntity<SettingsResponse>> updatePreferences(
            @RequestBody PreferencesUpdateRequest request) {
        UserPreferences prefs = preferencesService.getPreferences();
        if (request.getLanguage() != null) {
            prefs.setLanguage(request.getLanguage());
        }
        if (request.getTimezone() != null) {
            prefs.setTimezone(request.getTimezone());
        }
        if (request.getNotificationsEnabled() != null) {
            prefs.setNotificationsEnabled(request.getNotificationsEnabled());
        }
        if (request.getModelTier() != null) {
            prefs.setModelTier(request.getModelTier());
        }
        if (request.getTierForce() != null) {
            prefs.setTierForce(request.getTierForce());
        }
        preferencesService.savePreferences(prefs);
        return getSettings();
    }

    @GetMapping("/models")
    public Mono<ResponseEntity<Map<String, List<ModelDto>>>> getModels() {
        Map<String, List<ModelSelectionService.AvailableModel>> grouped = modelSelectionService
                .getAvailableModelsGrouped();
        Map<String, List<ModelDto>> result = new LinkedHashMap<>();
        grouped.forEach((provider, models) -> result.put(provider, models.stream()
                .map(m -> new ModelDto(m.id(), m.displayName(), m.hasReasoning(), m.reasoningLevels()))
                .toList()));
        return Mono.just(ResponseEntity.ok(result));
    }

    @PutMapping("/tier-overrides")
    public Mono<ResponseEntity<SettingsResponse>> updateTierOverrides(
            @RequestBody Map<String, SettingsResponse.TierOverrideDto> overrides) {
        UserPreferences prefs = preferencesService.getPreferences();
        Map<String, UserPreferences.TierOverride> tierOverrides = new LinkedHashMap<>();
        overrides.forEach((tier, dto) -> tierOverrides.put(tier,
                new UserPreferences.TierOverride(dto.getModel(), dto.getReasoning())));
        prefs.setTierOverrides(tierOverrides);
        preferencesService.savePreferences(prefs);
        return getSettings();
    }

    private record ModelDto(String id, String displayName, boolean hasReasoning, List<String> reasoningLevels) {
    }
}
