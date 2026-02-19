package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.PreferencesUpdateRequest;
import me.golemcore.bot.adapter.inbound.web.dto.SettingsResponse;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import reactor.test.StepVerifier;

class SettingsControllerTest {

    private UserPreferencesService preferencesService;
    private ModelSelectionService modelSelectionService;
    private RuntimeConfigService runtimeConfigService;
    private ApplicationEventPublisher eventPublisher;
    private SettingsController controller;

    @BeforeEach
    void setUp() {
        preferencesService = mock(UserPreferencesService.class);
        modelSelectionService = mock(ModelSelectionService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        controller = new SettingsController(preferencesService, modelSelectionService, runtimeConfigService,
                eventPublisher);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(RuntimeConfig.builder().build());
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
    void shouldRejectLlmProviderRemovalWhenUsedByModelRouter() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder()
                        .balancedModel("openai/gpt-5.1")
                        .build())
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(new LinkedHashMap<>(Map.of(
                                "openai", RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("x")).build(),
                                "anthropic", RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("y")).build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmConfig update = RuntimeConfig.LlmConfig.builder()
                .providers(new LinkedHashMap<>(Map.of(
                        "anthropic", RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("y")).build())))
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateLlmConfig(update));
        assertTrue(error.getMessage().contains("Cannot remove provider 'openai'"));
    }

    @Test
    void shouldRejectInvalidLlmProviderName() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder().build())
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmConfig update = RuntimeConfig.LlmConfig.builder()
                .providers(new LinkedHashMap<>(Map.of(
                        "Bad Name", RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("x")).build())))
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateLlmConfig(update));
        assertTrue(error.getMessage().contains("llm.providers keys must be lowercase"));
    }

    @Test
    void shouldRejectInvalidApiType() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("x"))
                .apiType("invalid")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.addLlmProvider("test", providerConfig));
        assertTrue(error.getMessage().contains("apiType must be one of"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "openai", "anthropic", "gemini" })
    void shouldAcceptValidApiTypes(String apiType) {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("x"))
                .apiType(apiType)
                .build();

        assertDoesNotThrow(() -> controller.addLlmProvider("test", providerConfig));
    }

    @ParameterizedTest
    @ValueSource(strings = { "OPENAI", "Anthropic", "GEMINI" })
    void shouldAcceptCaseInsensitiveApiTypes(String apiType) {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("x"))
                .apiType(apiType)
                .build();

        assertDoesNotThrow(() -> controller.addLlmProvider("test", providerConfig));
    }

    @Test
    void shouldAcceptNullApiType() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("x"))
                .apiType(null)
                .build();

        assertDoesNotThrow(() -> controller.addLlmProvider("test", providerConfig));
    }

    @Test
    void shouldAcceptBlankApiType() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmProviderConfig providerConfig = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("x"))
                .apiType("   ")
                .build();

        assertDoesNotThrow(() -> controller.addLlmProvider("test", providerConfig));
    }

    @Test
    void shouldRejectInvalidApiTypeInLlmConfigUpdate() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder().build())
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmConfig update = RuntimeConfig.LlmConfig.builder()
                .providers(new LinkedHashMap<>(Map.of(
                        "openai", RuntimeConfig.LlmProviderConfig.builder()
                                .apiKey(Secret.of("x"))
                                .apiType("invalid")
                                .build())))
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateLlmConfig(update));
        assertTrue(error.getMessage().contains("apiType must be one of"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "OPENAI", "Anthropic", "GEMINI", "   " })
    void shouldAcceptCaseInsensitiveAndBlankApiTypeInLlmConfigUpdate(String apiType) {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder().build())
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmConfig update = RuntimeConfig.LlmConfig.builder()
                .providers(new LinkedHashMap<>(Map.of(
                        "openai", RuntimeConfig.LlmProviderConfig.builder()
                                .apiKey(Secret.of("x"))
                                .apiType(apiType)
                                .build())))
                .build();

        assertDoesNotThrow(() -> controller.updateLlmConfig(update));
    }

    @Test
    void shouldRejectInvalidLlmProviderTimeout() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder().build())
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.LlmConfig update = RuntimeConfig.LlmConfig.builder()
                .providers(new LinkedHashMap<>(Map.of(
                        "openai",
                        RuntimeConfig.LlmProviderConfig.builder().apiKey(Secret.of("x"))
                                .requestTimeoutSeconds(0).build())))
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateLlmConfig(update));
        assertTrue(error.getMessage().contains("requestTimeoutSeconds must be between 1 and 3600"));
    }
}
