package me.golemcore.bot.tools;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.McpConfig;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.DynamicSkillFactory;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscoverMcpServerToolTest {

    @Mock
    private RuntimeConfigService runtimeConfigService;
    @Mock
    private SkillComponent skillComponent;

    private DynamicSkillFactory dynamicSkillFactory;
    private DiscoverMcpServerTool tool;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        dynamicSkillFactory = new DynamicSkillFactory();
        tool = new DiscoverMcpServerTool(runtimeConfigService, dynamicSkillFactory, skillComponent);

        when(runtimeConfigService.isMcpEnabled()).thenReturn(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        AgentContextHolder.clear();
        mocks.close();
    }

    @Test
    void shouldSearchCatalogByQuery() {
        List<RuntimeConfig.McpCatalogEntry> catalog = List.of(
                RuntimeConfig.McpCatalogEntry.builder()
                        .name("github")
                        .description("GitHub API")
                        .command("npx github")
                        .enabled(true)
                        .build(),
                RuntimeConfig.McpCatalogEntry.builder()
                        .name("slack")
                        .description("Slack integration")
                        .command("npx slack")
                        .enabled(true)
                        .build());
        when(runtimeConfigService.getMcpCatalog()).thenReturn(catalog);

        ToolResult result = tool.execute(Map.of("action", "search", "query", "github")).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("github"));
        assertFalse(result.getOutput().contains("slack"));
    }

    @Test
    void shouldReturnAllServersWhenNoQuery() {
        List<RuntimeConfig.McpCatalogEntry> catalog = List.of(
                RuntimeConfig.McpCatalogEntry.builder()
                        .name("github")
                        .command("npx github")
                        .enabled(true)
                        .build(),
                RuntimeConfig.McpCatalogEntry.builder()
                        .name("slack")
                        .command("npx slack")
                        .enabled(true)
                        .build());
        when(runtimeConfigService.getMcpCatalog()).thenReturn(catalog);

        ToolResult result = tool.execute(Map.of("action", "search")).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("github"));
        assertTrue(result.getOutput().contains("slack"));
        assertTrue(result.getOutput().contains("Found 2"));
    }

    @Test
    void shouldHandleEmptyCatalog() {
        when(runtimeConfigService.getMcpCatalog()).thenReturn(List.of());

        ToolResult result = tool.execute(Map.of("action", "search", "query", "test")).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("empty"));
    }

    @Test
    void shouldActivateServerFromCatalog() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().chatId("test").build())
                .messages(new ArrayList<>())
                .build();
        AgentContextHolder.set(context);

        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .description("GitHub API")
                .command("npx github")
                .enabled(true)
                .build();
        when(runtimeConfigService.getMcpCatalog()).thenReturn(List.of(entry));
        when(skillComponent.findByName("mcp-github")).thenReturn(Optional.empty());
        when(skillComponent.getAvailableSkills()).thenReturn(List.of());
        when(skillComponent.registerDynamicSkill(any(Skill.class))).thenReturn(true);

        ToolResult result = tool.execute(Map.of("action", "activate", "server_name", "github")).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Activating"));
        assertNotNull(context.getSkillTransitionRequest());
        verify(skillComponent).registerDynamicSkill(any(Skill.class));
    }

    @Test
    void shouldReuseExistingSkill() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().chatId("test").build())
                .messages(new ArrayList<>())
                .build();
        AgentContextHolder.set(context);

        Skill existingSkill = Skill.builder()
                .name("mcp-github")
                .available(true)
                .build();
        when(skillComponent.findByName("mcp-github")).thenReturn(Optional.of(existingSkill));

        ToolResult result = tool.execute(Map.of("action", "activate", "server_name", "github")).join();

        assertTrue(result.isSuccess());
        verify(skillComponent, never()).registerDynamicSkill(any());
    }

    @Test
    void shouldFailWhenServerNotInCatalog() {
        when(skillComponent.findByName("mcp-nonexistent")).thenReturn(Optional.empty());
        when(skillComponent.getAvailableSkills()).thenReturn(List.of());
        when(runtimeConfigService.getMcpCatalog()).thenReturn(List.of());

        ToolResult result = tool.execute(Map.of("action", "activate", "server_name", "nonexistent")).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("not found"));
    }

    @Test
    void shouldFailWithoutServerName() {
        ToolResult result = tool.execute(Map.of("action", "activate")).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("server_name is required"));
    }

    @Test
    void shouldFailWithInvalidAction() {
        ToolResult result = tool.execute(Map.of("action", "invalid")).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Invalid action"));
    }

    @Test
    void shouldFailWithoutContext() {
        when(skillComponent.findByName("mcp-github")).thenReturn(Optional.empty());
        when(skillComponent.getAvailableSkills()).thenReturn(List.of());
        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .command("npx github")
                .enabled(true)
                .build();
        when(runtimeConfigService.getMcpCatalog()).thenReturn(List.of(entry));
        when(skillComponent.registerDynamicSkill(any(Skill.class))).thenReturn(true);

        ToolResult result = tool.execute(Map.of("action", "activate", "server_name", "github")).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("No agent context"));
    }

    @Test
    void shouldBeDisabledWhenMcpDisabled() {
        when(runtimeConfigService.isMcpEnabled()).thenReturn(false);
        assertFalse(tool.isEnabled());
    }

    @Test
    void shouldActivateManualSkillCoveringServer() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().chatId("test").build())
                .messages(new ArrayList<>())
                .build();
        AgentContextHolder.set(context);

        Skill manualSkill = Skill.builder()
                .name("github-assistant")
                .available(true)
                .mcpConfig(McpConfig.builder().command("npx github-mcp-server").build())
                .build();
        when(skillComponent.findByName("mcp-github")).thenReturn(Optional.empty());
        when(skillComponent.getAvailableSkills()).thenReturn(List.of(manualSkill));
        when(runtimeConfigService.getMcpCatalog()).thenReturn(List.of());

        ToolResult result = tool.execute(Map.of("action", "activate", "server_name", "github")).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Activating"));
        verify(skillComponent, never()).registerDynamicSkill(any());
    }

    @Test
    void shouldHandleCaseInsensitiveServerName() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().chatId("test").build())
                .messages(new ArrayList<>())
                .build();
        AgentContextHolder.set(context);

        RuntimeConfig.McpCatalogEntry entry = RuntimeConfig.McpCatalogEntry.builder()
                .name("github")
                .command("npx github")
                .enabled(true)
                .build();
        when(runtimeConfigService.getMcpCatalog()).thenReturn(List.of(entry));
        when(skillComponent.findByName("mcp-github")).thenReturn(Optional.empty());
        when(skillComponent.getAvailableSkills()).thenReturn(List.of());
        when(skillComponent.registerDynamicSkill(any(Skill.class))).thenReturn(true);

        ToolResult result = tool.execute(Map.of("action", "activate", "server_name", "GitHub")).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Activating"));
    }

    @Test
    void shouldReturnToolNameAndDefinition() {
        assertEquals("discover_mcp_server", tool.getToolName());
        assertNotNull(tool.getDefinition());
        assertEquals("discover_mcp_server", tool.getDefinition().getName());
    }

    @Test
    void shouldSearchByDescription() {
        List<RuntimeConfig.McpCatalogEntry> catalog = List.of(
                RuntimeConfig.McpCatalogEntry.builder()
                        .name("server1")
                        .description("Manages pull requests and issues")
                        .command("npx server1")
                        .enabled(true)
                        .build());
        when(runtimeConfigService.getMcpCatalog()).thenReturn(catalog);

        ToolResult result = tool.execute(Map.of("action", "search", "query", "pull requests")).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("server1"));
    }

    @Test
    void shouldSearchByCommand() {
        List<RuntimeConfig.McpCatalogEntry> catalog = List.of(
                RuntimeConfig.McpCatalogEntry.builder()
                        .name("myserver")
                        .command("npx @special/unique-server")
                        .enabled(true)
                        .build());
        when(runtimeConfigService.getMcpCatalog()).thenReturn(catalog);

        ToolResult result = tool.execute(Map.of("action", "search", "query", "unique-server")).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("myserver"));
    }

    @Test
    void shouldShowAvailableServersWhenNoMatch() {
        List<RuntimeConfig.McpCatalogEntry> catalog = List.of(
                RuntimeConfig.McpCatalogEntry.builder()
                        .name("github")
                        .command("npx github")
                        .enabled(true)
                        .build());
        when(runtimeConfigService.getMcpCatalog()).thenReturn(catalog);

        ToolResult result = tool.execute(Map.of("action", "search", "query", "nonexistent")).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("github"));
        assertTrue(result.getOutput().contains("No servers match"));
    }

    @Test
    void shouldHandleBlankAction() {
        ToolResult result = tool.execute(Map.of("action", "  ")).join();

        assertFalse(result.isSuccess());
    }

    @Test
    void shouldHandleBlankServerName() {
        ToolResult result = tool.execute(Map.of("action", "activate", "server_name", "  ")).join();

        assertFalse(result.isSuccess());
    }

    @Test
    void shouldFilterDisabledEntries() {
        List<RuntimeConfig.McpCatalogEntry> catalog = List.of(
                RuntimeConfig.McpCatalogEntry.builder()
                        .name("enabled-server")
                        .command("npx enabled")
                        .enabled(true)
                        .build(),
                RuntimeConfig.McpCatalogEntry.builder()
                        .name("disabled-server")
                        .command("npx disabled")
                        .enabled(false)
                        .build());
        when(runtimeConfigService.getMcpCatalog()).thenReturn(catalog);

        ToolResult result = tool.execute(Map.of("action", "search")).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("enabled-server"));
        assertFalse(result.getOutput().contains("disabled-server"));
    }
}
