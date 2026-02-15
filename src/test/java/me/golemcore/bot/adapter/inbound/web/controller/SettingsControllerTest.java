package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.PreferencesUpdateRequest;
import me.golemcore.bot.adapter.inbound.web.dto.SettingsResponse;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import reactor.test.StepVerifier;

class SettingsControllerTest {

    private UserPreferencesService preferencesService;
    private ModelSelectionService modelSelectionService;
    private SettingsController controller;

    @BeforeEach
    void setUp() {
        preferencesService = mock(UserPreferencesService.class);
        modelSelectionService = mock(ModelSelectionService.class);
        controller = new SettingsController(preferencesService, modelSelectionService);
    }

    @Test
    void shouldGetSettings() {
        UserPreferences prefs = new UserPreferences();
        prefs.setLanguage("en");
        prefs.setTimezone("UTC");
        prefs.setNotificationsEnabled(true);
        prefs.setModelTier("standard");
        when(preferencesService.getPreferences()).thenReturn(prefs);

        StepVerifier.create(controller.getSettings())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SettingsResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("en", body.getLanguage());
                    assertEquals("UTC", body.getTimezone());
                    assertEquals("standard", body.getModelTier());
                })
                .verifyComplete();
    }

    @Test
    void shouldGetSettingsWithTierOverrides() {
        UserPreferences prefs = new UserPreferences();
        prefs.setLanguage("en");
        Map<String, UserPreferences.TierOverride> overrides = new LinkedHashMap<>();
        overrides.put("standard", new UserPreferences.TierOverride("gpt-4o", "medium"));
        prefs.setTierOverrides(overrides);
        when(preferencesService.getPreferences()).thenReturn(prefs);

        StepVerifier.create(controller.getSettings())
                .assertNext(response -> {
                    SettingsResponse body = response.getBody();
                    assertNotNull(body);
                    assertNotNull(body.getTierOverrides());
                    assertEquals("gpt-4o", body.getTierOverrides().get("standard").getModel());
                })
                .verifyComplete();
    }

    @Test
    void shouldUpdatePreferences() {
        UserPreferences prefs = new UserPreferences();
        when(preferencesService.getPreferences()).thenReturn(prefs);

        PreferencesUpdateRequest request = new PreferencesUpdateRequest();
        request.setLanguage("fr");
        request.setTimezone("Europe/Paris");

        StepVerifier.create(controller.updatePreferences(request))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        verify(preferencesService).savePreferences(prefs);
        assertEquals("fr", prefs.getLanguage());
        assertEquals("Europe/Paris", prefs.getTimezone());
    }

    @Test
    void shouldUpdateTierOverrides() {
        UserPreferences prefs = new UserPreferences();
        when(preferencesService.getPreferences()).thenReturn(prefs);

        Map<String, SettingsResponse.TierOverrideDto> overrides = Map.of(
                "standard", SettingsResponse.TierOverrideDto.builder()
                        .model("gpt-4o")
                        .reasoning("medium")
                        .build());

        StepVerifier.create(controller.updateTierOverrides(overrides))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        verify(preferencesService).savePreferences(prefs);
        assertNotNull(prefs.getTierOverrides());
        assertEquals("gpt-4o", prefs.getTierOverrides().get("standard").getModel());
    }
}
