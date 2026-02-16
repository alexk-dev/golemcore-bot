package me.golemcore.bot.tools;

import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TierToolTest {

    private static final String PARAM_TIER = "tier";
    private static final String TIER_CODING = "coding";

    private UserPreferencesService preferencesService;
    private RuntimeConfigService runtimeConfigService;
    private TierTool tool;

    @BeforeEach
    void setUp() {
        preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getPreferences()).thenReturn(UserPreferences.builder().build());
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isTierToolEnabled()).thenReturn(true);
        tool = new TierTool(preferencesService, runtimeConfigService);

        // Set up AgentContextHolder
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .id("test")
                        .channelType("telegram")
                        .chatId("123")
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build())
                .messages(new ArrayList<>())
                .build();
        AgentContextHolder.set(context);
    }

    @AfterEach
    void tearDown() {
        AgentContextHolder.clear();
    }

    @Test
    void shouldReturnCorrectToolName() {
        assertEquals("set_tier", tool.getToolName());
        assertEquals("set_tier", tool.getDefinition().getName());
    }

    @Test
    void shouldBeEnabledByDefault() {
        assertTrue(tool.isEnabled());
    }

    @ParameterizedTest
    @ValueSource(strings = { "balanced", "smart", "coding", "deep" })
    void shouldAcceptValidTiers(String tier) {
        ToolResult result = tool.execute(Map.of(PARAM_TIER, tier)).join();

        assertNotNull(result.getOutput());
        assertNull(result.getError());
        assertTrue(result.getOutput().contains(tier));
        assertEquals(tier, AgentContextHolder.get().getModelTier());
    }

    @Test
    void shouldRejectInvalidTier() {
        ToolResult result = tool.execute(Map.of(PARAM_TIER, "turbo")).join();

        assertNotNull(result.getError());
        assertTrue(result.getError().contains("Invalid tier"));
    }

    @Test
    void shouldRejectEmptyTier() {
        ToolResult result = tool.execute(Map.of(PARAM_TIER, "")).join();

        assertNotNull(result.getError());
        assertTrue(result.getError().contains("required"));
    }

    @Test
    void shouldRejectMissingTier() {
        ToolResult result = tool.execute(Map.of()).join();

        assertNotNull(result.getError());
        assertTrue(result.getError().contains("required"));
    }

    @Test
    void shouldRejectWhenTierForceEnabled() {
        when(preferencesService.getPreferences()).thenReturn(
                UserPreferences.builder().modelTier("smart").tierForce(true).build());

        ToolResult result = tool.execute(Map.of(PARAM_TIER, TIER_CODING)).join();

        assertNotNull(result.getError());
        assertTrue(result.getError().contains("locked"));
    }

    @Test
    void shouldFailWhenNoContext() {
        AgentContextHolder.clear();

        ToolResult result = tool.execute(Map.of(PARAM_TIER, TIER_CODING)).join();

        assertNotNull(result.getError());
        assertTrue(result.getError().contains("No agent context"));
    }
}
