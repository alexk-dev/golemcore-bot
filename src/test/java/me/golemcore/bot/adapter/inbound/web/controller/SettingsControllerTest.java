package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.PreferencesUpdateRequest;
import me.golemcore.bot.adapter.inbound.web.dto.SettingsResponse;
import me.golemcore.bot.domain.model.MemoryPreset;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.MemoryPresetService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private MemoryPresetService memoryPresetService;
    private ApplicationEventPublisher eventPublisher;
    private SettingsController controller;

    @BeforeEach
    void setUp() {
        preferencesService = mock(UserPreferencesService.class);
        modelSelectionService = mock(ModelSelectionService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        memoryPresetService = mock(MemoryPresetService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        controller = new SettingsController(preferencesService, modelSelectionService, runtimeConfigService,
                memoryPresetService,
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
    void shouldGetMemoryPresets() {
        MemoryPreset preset = MemoryPreset.builder()
                .id("coding_balanced")
                .label("Coding Balanced")
                .comment("Универсальный дефолт для большинства разработчиков.")
                .memory(RuntimeConfig.MemoryConfig.builder()
                        .softPromptBudgetTokens(1800)
                        .maxPromptBudgetTokens(3500)
                        .workingTopK(6)
                        .episodicTopK(8)
                        .semanticTopK(6)
                        .proceduralTopK(6)
                        .promotionEnabled(true)
                        .promotionMinConfidence(0.8)
                        .decayEnabled(true)
                        .decayDays(30)
                        .retrievalLookbackDays(21)
                        .codeAwareExtractionEnabled(true)
                        .build())
                .build();
        when(memoryPresetService.getPresets()).thenReturn(List.of(preset));

        StepVerifier.create(controller.getMemoryPresets())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<MemoryPreset> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(1, body.size());
                    assertEquals("coding_balanced", body.get(0).getId());
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
    void shouldAllowInviteOnlyTelegramConfigWithRegisteredUsers() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .telegram(RuntimeConfig.TelegramConfig.builder()
                        .authMode("invite_only")
                        .allowedUsers(List.of("1001"))
                        .token(Secret.of("saved-token"))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        RuntimeConfig.TelegramConfig incoming = RuntimeConfig.TelegramConfig.builder()
                .enabled(true)
                .authMode("user")
                .allowedUsers(List.of("1001"))
                .inviteCodes(List.of())
                .build();

        StepVerifier.create(controller.updateTelegramConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        verify(runtimeConfigService).updateRuntimeConfig(runtimeConfig);
        assertEquals(List.of("1001"), runtimeConfig.getTelegram().getAllowedUsers());
        assertEquals("invite_only", runtimeConfig.getTelegram().getAuthMode());
        assertEquals("saved-token", Secret.valueOrEmpty(runtimeConfig.getTelegram().getToken()));
    }

    @Test
    void shouldRejectTelegramConfigWithMultipleAllowedUsers() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .telegram(RuntimeConfig.TelegramConfig.builder()
                        .authMode("invite_only")
                        .token(Secret.of("saved-token"))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.TelegramConfig incoming = RuntimeConfig.TelegramConfig.builder()
                .enabled(true)
                .authMode("invite_only")
                .allowedUsers(List.of("1001", "1002"))
                .inviteCodes(List.of())
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateTelegramConfig(incoming));
        assertTrue(error.getMessage().contains("supports only one invited user"));
    }

    @Test
    void shouldRejectRuntimeConfigWithMultipleTelegramAllowedUsers() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .telegram(RuntimeConfig.TelegramConfig.builder()
                        .authMode("invite_only")
                        .allowedUsers(List.of("1001", "1002"))
                        .build())
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateRuntimeConfig(incoming));
        assertTrue(error.getMessage().contains("supports only one invited user"));
    }

    @Test
    void shouldRemoveTelegramAllowedUser() {
        when(runtimeConfigService.removeTelegramAllowedUser("123")).thenReturn(true);

        StepVerifier.create(controller.removeTelegramAllowedUser("123"))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldRejectNonNumericTelegramAllowedUserId() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.removeTelegramAllowedUser("abc"));
        assertTrue(error.getMessage().contains("telegram.userId must be numeric"));
    }

    @Test
    void shouldReturnNotFoundWhenRemovingMissingTelegramAllowedUser() {
        when(runtimeConfigService.removeTelegramAllowedUser("123")).thenReturn(false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.removeTelegramAllowedUser("123"));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
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

    @Test
    void shouldCreateShellEnvironmentVariable() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable variable = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("API_TOKEN")
                .value("abc123")
                .build();

        StepVerifier.create(controller.createShellEnvironmentVariable(variable))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        assertEquals(1, runtimeConfig.getTools().getShellEnvironmentVariables().size());
        assertEquals("API_TOKEN", runtimeConfig.getTools().getShellEnvironmentVariables().get(0).getName());
        assertEquals("abc123", runtimeConfig.getTools().getShellEnvironmentVariables().get(0).getValue());
        verify(runtimeConfigService).updateRuntimeConfig(runtimeConfig);
    }

    @Test
    void shouldRejectDuplicateShellEnvironmentVariableName() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>(List.of(
                                RuntimeConfig.ShellEnvironmentVariable.builder()
                                        .name("API_TOKEN")
                                        .value("v1")
                                        .build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable duplicate = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("API_TOKEN")
                .value("v2")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.createShellEnvironmentVariable(duplicate));
        assertTrue(error.getMessage().contains("duplicate name"));
    }

    @Test
    void shouldUpdateShellEnvironmentVariable() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>(List.of(
                                RuntimeConfig.ShellEnvironmentVariable.builder()
                                        .name("API_TOKEN")
                                        .value("old")
                                        .build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable update = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("API_TOKEN")
                .value("new-value")
                .build();

        StepVerifier.create(controller.updateShellEnvironmentVariable("API_TOKEN", update))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        assertEquals("new-value", runtimeConfig.getTools().getShellEnvironmentVariables().get(0).getValue());
        verify(runtimeConfigService).updateRuntimeConfig(runtimeConfig);
    }

    @Test
    void shouldRenameShellEnvironmentVariable() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>(List.of(
                                RuntimeConfig.ShellEnvironmentVariable.builder()
                                        .name("OLD_NAME")
                                        .value("value")
                                        .build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable update = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("NEW_NAME")
                .value("value")
                .build();

        StepVerifier.create(controller.updateShellEnvironmentVariable("OLD_NAME", update))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        assertEquals("NEW_NAME", runtimeConfig.getTools().getShellEnvironmentVariables().get(0).getName());
    }

    @Test
    void shouldDeleteShellEnvironmentVariable() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>(List.of(
                                RuntimeConfig.ShellEnvironmentVariable.builder()
                                        .name("DELETE_ME")
                                        .value("value")
                                        .build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        StepVerifier.create(controller.deleteShellEnvironmentVariable("DELETE_ME"))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        assertTrue(runtimeConfig.getTools().getShellEnvironmentVariables().isEmpty());
        verify(runtimeConfigService).updateRuntimeConfig(runtimeConfig);
    }

    @Test
    void shouldReturnNotFoundWhenDeletingUnknownShellEnvironmentVariable() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.deleteShellEnvironmentVariable("MISSING_VAR"));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void shouldRejectInvalidShellEnvironmentVariableName() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable invalid = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("1INVALID")
                .value("value")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.createShellEnvironmentVariable(invalid));
        assertTrue(error.getMessage().contains("[A-Za-z_][A-Za-z0-9_]*"));
    }

    @Test
    void shouldRejectReservedShellEnvironmentVariableName() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable invalid = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("HOME")
                .value("/tmp")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.createShellEnvironmentVariable(invalid));
        assertTrue(error.getMessage().contains("reserved variable"));
    }

    @Test
    void shouldRejectDuplicateShellEnvironmentVariableWhenUpdatingToolsConfig() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.ToolsConfig toolsConfig = RuntimeConfig.ToolsConfig.builder()
                .shellEnvironmentVariables(new ArrayList<>(List.of(
                        RuntimeConfig.ShellEnvironmentVariable.builder().name("API_TOKEN").value("v1").build(),
                        RuntimeConfig.ShellEnvironmentVariable.builder().name("API_TOKEN").value("v2").build())))
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateToolsConfig(toolsConfig));
        assertTrue(error.getMessage().contains("duplicate name"));
    }

    @Test
    void shouldRejectDuplicateShellEnvironmentVariableWhenUpdatingRuntimeConfig() {
        RuntimeConfig current = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>(List.of(
                                RuntimeConfig.ShellEnvironmentVariable.builder().name("API_TOKEN").value("v1").build(),
                                RuntimeConfig.ShellEnvironmentVariable.builder().name("API_TOKEN").value("v2").build())))
                        .build())
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateRuntimeConfig(incoming));
        assertTrue(error.getMessage().contains("duplicate name"));
    }

    @Test
    void shouldAllowRuntimeConfigUpdateWhenToolsIsNull() {
        RuntimeConfig current = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder().build();
        incoming.setTools(null);

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldGetShellEnvironmentVariablesFromRuntimeConfigForApi() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>(List.of(
                                RuntimeConfig.ShellEnvironmentVariable.builder()
                                        .name("API_TOKEN")
                                        .value("abc123")
                                        .build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        StepVerifier.create(controller.getShellEnvironmentVariables())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<RuntimeConfig.ShellEnvironmentVariable> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(1, body.size());
                    assertEquals("API_TOKEN", body.get(0).getName());
                    assertEquals("abc123", body.get(0).getValue());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyShellEnvironmentVariablesWhenToolsMissingInApiConfig() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder().build();
        runtimeConfig.setTools(null);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        StepVerifier.create(controller.getShellEnvironmentVariables())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<RuntimeConfig.ShellEnvironmentVariable> body = response.getBody();
                    assertNotNull(body);
                    assertTrue(body.isEmpty());
                })
                .verifyComplete();
    }

    @Test
    void shouldNormalizeShellEnvironmentVariableNameAndNullValueOnCreate() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable variable = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("  API_TOKEN  ")
                .value(null)
                .build();

        StepVerifier.create(controller.createShellEnvironmentVariable(variable))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        RuntimeConfig.ShellEnvironmentVariable stored = runtimeConfig.getTools().getShellEnvironmentVariables().get(0);
        assertEquals("API_TOKEN", stored.getName());
        assertEquals("", stored.getValue());
    }

    @Test
    void shouldRejectNullShellEnvironmentVariablePayload() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.createShellEnvironmentVariable(null));
        assertTrue(error.getMessage().contains("item is required"));
    }

    @Test
    void shouldRejectTooLongShellEnvironmentVariableName() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        String tooLongName = "A".repeat(129);
        RuntimeConfig.ShellEnvironmentVariable variable = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name(tooLongName)
                .value("value")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.createShellEnvironmentVariable(variable));
        assertTrue(error.getMessage().contains("at most 128"));
    }

    @Test
    void shouldRejectTooLongShellEnvironmentVariableValue() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        String tooLongValue = "a".repeat(8193);
        RuntimeConfig.ShellEnvironmentVariable variable = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("API_TOKEN")
                .value(tooLongValue)
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.createShellEnvironmentVariable(variable));
        assertTrue(error.getMessage().contains("at most 8192"));
    }

    @Test
    void shouldUsePathVariableNameWhenUpdatingShellEnvironmentVariableWithBlankBodyName() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>(List.of(
                                RuntimeConfig.ShellEnvironmentVariable.builder()
                                        .name("API_TOKEN")
                                        .value("old-value")
                                        .build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.getRuntimeConfigForApi()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable update = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("   ")
                .value("new-value")
                .build();

        StepVerifier.create(controller.updateShellEnvironmentVariable("API_TOKEN", update))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        RuntimeConfig.ShellEnvironmentVariable stored = runtimeConfig.getTools().getShellEnvironmentVariables().get(0);
        assertEquals("API_TOKEN", stored.getName());
        assertEquals("new-value", stored.getValue());
    }

    @Test
    void shouldRejectShellEnvironmentVariableRenameCollisionOnUpdate() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>(List.of(
                                RuntimeConfig.ShellEnvironmentVariable.builder().name("FIRST").value("a").build(),
                                RuntimeConfig.ShellEnvironmentVariable.builder().name("SECOND").value("b").build())))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable update = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("SECOND")
                .value("updated")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateShellEnvironmentVariable("FIRST", update));
        assertTrue(error.getMessage().contains("duplicate name"));
    }

    @Test
    void shouldReturnNotFoundWhenUpdatingMissingShellEnvironmentVariable() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .tools(RuntimeConfig.ToolsConfig.builder()
                        .shellEnvironmentVariables(new ArrayList<>())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.ShellEnvironmentVariable update = RuntimeConfig.ShellEnvironmentVariable.builder()
                .name("MISSING")
                .value("value")
                .build();

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.updateShellEnvironmentVariable("MISSING", update));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void shouldUpdateMemoryConfigWhenValid() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .memory(RuntimeConfig.MemoryConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.MemoryConfig memoryConfig = RuntimeConfig.MemoryConfig.builder()
                .enabled(true)
                .softPromptBudgetTokens(1800)
                .maxPromptBudgetTokens(3500)
                .workingTopK(6)
                .episodicTopK(8)
                .semanticTopK(6)
                .proceduralTopK(4)
                .promotionEnabled(true)
                .promotionMinConfidence(0.8)
                .decayEnabled(true)
                .decayDays(30)
                .retrievalLookbackDays(21)
                .codeAwareExtractionEnabled(true)
                .build();

        StepVerifier.create(controller.updateMemoryConfig(memoryConfig))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        verify(runtimeConfigService).updateRuntimeConfig(runtimeConfig);
        assertEquals(3500, runtimeConfig.getMemory().getMaxPromptBudgetTokens());
    }

    @Test
    void shouldRejectMemoryConfigWhenMaxBudgetIsLessThanSoftBudget() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .memory(RuntimeConfig.MemoryConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.MemoryConfig memoryConfig = RuntimeConfig.MemoryConfig.builder()
                .softPromptBudgetTokens(2000)
                .maxPromptBudgetTokens(1000)
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateMemoryConfig(memoryConfig));
        assertTrue(error.getMessage().contains("memory.maxPromptBudgetTokens"));
    }

    @Test
    void shouldRejectMemoryConfigWhenTopKOutOfRange() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .memory(RuntimeConfig.MemoryConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.MemoryConfig memoryConfig = RuntimeConfig.MemoryConfig.builder()
                .semanticTopK(99)
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateMemoryConfig(memoryConfig));
        assertTrue(error.getMessage().contains("memory.semanticTopK"));
    }

    @Test
    void shouldRejectMemoryConfigWhenPromotionConfidenceOutOfRange() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .memory(RuntimeConfig.MemoryConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.MemoryConfig memoryConfig = RuntimeConfig.MemoryConfig.builder()
                .promotionMinConfidence(1.5)
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateMemoryConfig(memoryConfig));
        assertTrue(error.getMessage().contains("memory.promotionMinConfidence"));
    }

    @Test
    void shouldRejectMemoryConfigWhenRetrievalLookbackOutOfRange() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .memory(RuntimeConfig.MemoryConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.MemoryConfig memoryConfig = RuntimeConfig.MemoryConfig.builder()
                .retrievalLookbackDays(999)
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateMemoryConfig(memoryConfig));
        assertTrue(error.getMessage().contains("memory.retrievalLookbackDays"));
    }

    @Test
    void shouldRejectRuntimeConfigUpdateWhenMemoryConfigIsInvalid() {
        RuntimeConfig current = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .llm(RuntimeConfig.LlmConfig.builder().providers(new LinkedHashMap<>()).build())
                .modelRouter(RuntimeConfig.ModelRouterConfig.builder().build())
                .memory(RuntimeConfig.MemoryConfig.builder()
                        .softPromptBudgetTokens(1000)
                        .maxPromptBudgetTokens(500)
                        .build())
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateRuntimeConfig(incoming));
        assertTrue(error.getMessage().contains("memory.maxPromptBudgetTokens"));
    }

    @Test
    void shouldRejectWhisperSttWithoutUrl() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.VoiceConfig incoming = RuntimeConfig.VoiceConfig.builder()
                .sttProvider("whisper")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateVoiceConfig(incoming));
        assertTrue(error.getMessage().contains("voice.whisperSttUrl is required"));
    }

    @Test
    void shouldRejectUnsupportedTtsProvider() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.VoiceConfig incoming = RuntimeConfig.VoiceConfig.builder()
                .sttProvider("elevenlabs")
                .ttsProvider("whisper")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateVoiceConfig(incoming));
        assertTrue(error.getMessage().contains("voice.ttsProvider must be 'elevenlabs'"));
    }

    @Test
    void shouldPreserveWhisperApiKeyWhenNotProvidedOnUpdate() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .apiKey(Secret.of("eleven"))
                        .sttProvider("whisper")
                        .whisperSttUrl("http://localhost:5092")
                        .whisperSttApiKey(Secret.of("whisper-secret"))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.VoiceConfig incoming = RuntimeConfig.VoiceConfig.builder()
                .sttProvider("whisper")
                .whisperSttUrl("http://localhost:5092")
                .build();

        StepVerifier.create(controller.updateVoiceConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        RuntimeConfig.VoiceConfig saved = runtimeConfig.getVoice();
        assertEquals("whisper-secret", saved.getWhisperSttApiKey().getValue());
        assertEquals("whisper", saved.getSttProvider());
        assertEquals("elevenlabs", saved.getTtsProvider());
        verify(runtimeConfigService).updateRuntimeConfig(runtimeConfig);
    }

    @Test
    void shouldNormalizeVoiceProviderValuesOnUpdate() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .apiKey(Secret.of("eleven"))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.VoiceConfig incoming = RuntimeConfig.VoiceConfig.builder()
                .sttProvider("  WHISPER  ")
                .ttsProvider("  ELEVENLABS  ")
                .whisperSttUrl("http://localhost:5092")
                .build();

        StepVerifier.create(controller.updateVoiceConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        RuntimeConfig.VoiceConfig saved = runtimeConfig.getVoice();
        assertEquals("whisper", saved.getSttProvider());
        assertEquals("elevenlabs", saved.getTtsProvider());
    }

    @Test
    void shouldRejectUnsupportedSttProvider() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.VoiceConfig incoming = RuntimeConfig.VoiceConfig.builder()
                .sttProvider("google")
                .ttsProvider("elevenlabs")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateVoiceConfig(incoming));
        assertTrue(error.getMessage().contains("voice.sttProvider must be one of"));
    }

    @Test
    void shouldRejectWhisperSttWithInvalidUrl() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.VoiceConfig incoming = RuntimeConfig.VoiceConfig.builder()
                .sttProvider("whisper")
                .ttsProvider("elevenlabs")
                .whisperSttUrl("ftp://localhost:5092")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateVoiceConfig(incoming));
        assertTrue(error.getMessage().contains("voice.whisperSttUrl must be a valid http(s) URL"));
    }

    @Test
    void shouldNormalizeBlankWhisperUrlToNullWhenSttIsElevenLabs() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .sttProvider("elevenlabs")
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        RuntimeConfig.VoiceConfig incoming = RuntimeConfig.VoiceConfig.builder()
                .sttProvider("elevenlabs")
                .ttsProvider("elevenlabs")
                .whisperSttUrl("   ")
                .build();

        StepVerifier.create(controller.updateVoiceConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        RuntimeConfig.VoiceConfig saved = runtimeConfig.getVoice();
        assertNull(saved.getWhisperSttUrl());
        assertEquals("elevenlabs", saved.getSttProvider());
        assertEquals("elevenlabs", saved.getTtsProvider());
    }

    @Test
    void shouldPreserveWhisperApiKeyDuringRuntimeUpdateWhenNotProvided() {
        RuntimeConfig current = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .sttProvider("whisper")
                        .ttsProvider("elevenlabs")
                        .whisperSttUrl("http://localhost:5092")
                        .whisperSttApiKey(Secret.of("whisper-secret"))
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .sttProvider("whisper")
                        .whisperSttUrl("http://localhost:5092")
                        .build())
                .build();

        StepVerifier.create(controller.updateRuntimeConfig(incoming))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        assertEquals("whisper-secret", incoming.getVoice().getWhisperSttApiKey().getValue());
        verify(runtimeConfigService).updateRuntimeConfig(incoming);
    }

    @Test
    void shouldValidateVoiceConfigInRuntimeUpdate() {
        RuntimeConfig current = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder().build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);

        RuntimeConfig incoming = RuntimeConfig.builder()
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .sttProvider("elevenlabs")
                        .ttsProvider("whisper")
                        .build())
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateRuntimeConfig(incoming));
        assertTrue(error.getMessage().contains("voice.ttsProvider must be 'elevenlabs'"));
    }
}
