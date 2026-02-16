package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.PreferencesUpdateRequest;
import me.golemcore.bot.adapter.inbound.web.dto.SettingsResponse;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.RuntimeModuleConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import reactor.test.StepVerifier;

class SettingsControllerTest {

    private UserPreferencesService preferencesService;
    private ModelSelectionService modelSelectionService;
    private RuntimeConfigService runtimeConfigService;
    private RuntimeModuleConfigService runtimeModuleConfigService;
    private ApplicationEventPublisher eventPublisher;
    private SettingsController controller;

    @BeforeEach
    void setUp() {
        preferencesService = mock(UserPreferencesService.class);
        modelSelectionService = mock(ModelSelectionService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        runtimeModuleConfigService = mock(RuntimeModuleConfigService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        controller = new SettingsController(preferencesService, modelSelectionService, runtimeConfigService,
                runtimeModuleConfigService, eventPublisher);
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
    void shouldUpdatePartialPreferences() {
        UserPreferences prefs = new UserPreferences();
        prefs.setLanguage("en");
        prefs.setModelTier("standard");
        when(preferencesService.getPreferences()).thenReturn(prefs);

        PreferencesUpdateRequest request = new PreferencesUpdateRequest();
        request.setNotificationsEnabled(true);
        request.setModelTier("premium");
        request.setTierForce(true);

        StepVerifier.create(controller.updatePreferences(request))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        assertEquals("en", prefs.getLanguage()); // unchanged
        assertEquals("premium", prefs.getModelTier());
        assertTrue(prefs.isTierForce());
        assertTrue(prefs.isNotificationsEnabled());
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

    @Test
    void shouldMaskSecretsInRuntimeConfigResponse() {
        me.golemcore.bot.domain.model.RuntimeConfig cfg = me.golemcore.bot.domain.model.RuntimeConfig.builder().build();
        cfg.getTelegram().setToken("telegram-secret");
        cfg.getTools().setBraveSearchApiKey("brave-key");
        cfg.getTools().getImap().setPassword("imap-pass");
        cfg.getTools().getSmtp().setPassword("smtp-pass");
        cfg.getVoice().setApiKey("voice-key");
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(cfg);

        StepVerifier.create(controller.getRuntimeConfig())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    var body = response.getBody();
                    assertNotNull(body);
                    assertEquals("***", body.getTelegram().getToken());
                    assertEquals("***", body.getTools().getBraveSearchApiKey());
                    assertEquals("***", body.getTools().getImap().getPassword());
                    assertEquals("***", body.getTools().getSmtp().getPassword());
                    assertEquals("***", body.getVoice().getApiKey());
                })
                .verifyComplete();
    }

    @Test
    void shouldMaskSecretsAfterRuntimeUpdate() {
        me.golemcore.bot.domain.model.RuntimeConfig cfg = me.golemcore.bot.domain.model.RuntimeConfig.builder().build();
        cfg.getTelegram().setToken("telegram-secret");
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(cfg);

        StepVerifier.create(controller.updateRuntimeConfig(cfg))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    var body = response.getBody();
                    assertNotNull(body);
                    assertEquals("***", body.getTelegram().getToken());
                })
                .verifyComplete();

        verify(runtimeConfigService).updateRuntimeConfig(cfg);
    }

    @Test
    void shouldPreserveTelegramTokenWhenMaskedValueSent() {
        me.golemcore.bot.domain.model.RuntimeConfig current = me.golemcore.bot.domain.model.RuntimeConfig.builder()
                .build();
        current.getTelegram().setToken("real-token");

        me.golemcore.bot.domain.model.RuntimeConfig incoming = me.golemcore.bot.domain.model.RuntimeConfig.builder()
                .build();
        incoming.getTelegram().setToken("***");

        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current, current);

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        verify(runtimeConfigService).updateRuntimeConfig(incoming);
        assertEquals("real-token", incoming.getTelegram().getToken());
    }

    @Test
    void shouldPreserveToolSecretsWhenMaskedValueSent() {
        me.golemcore.bot.domain.model.RuntimeConfig current = me.golemcore.bot.domain.model.RuntimeConfig.builder()
                .build();
        current.getTools().setBraveSearchApiKey("real-brave");
        current.getTools().getImap().setPassword("real-imap");
        current.getTools().getSmtp().setPassword("real-smtp");

        me.golemcore.bot.domain.model.RuntimeConfig incoming = me.golemcore.bot.domain.model.RuntimeConfig.builder()
                .build();
        incoming.getTools().setBraveSearchApiKey("***");
        incoming.getTools().getImap().setPassword("***");
        incoming.getTools().getSmtp().setPassword("***");

        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current, current);

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        assertEquals("real-brave", incoming.getTools().getBraveSearchApiKey());
        assertEquals("real-imap", incoming.getTools().getImap().getPassword());
        assertEquals("real-smtp", incoming.getTools().getSmtp().getPassword());
    }

    @Test
    void shouldHaveDedicatedRuntimeSecretsEndpointMethod() throws Exception {
        var methods = SettingsController.class.getDeclaredMethods();
        boolean found = false;
        for (var m : methods) {
            if (m.getName().equals("updateRuntimeSecrets")) {
                found = true;
                break;
            }
        }
        assertTrue(found);
    }

}
