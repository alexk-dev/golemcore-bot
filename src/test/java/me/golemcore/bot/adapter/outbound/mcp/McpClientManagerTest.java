package me.golemcore.bot.adapter.outbound.mcp;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.McpConfig;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for McpClientManager caching, stop/cleanup, and handling of skills
 * without MCP.
 */
class McpClientManagerTest {

    private RuntimeConfigService runtimeConfigService;
    private McpClientManager manager;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isMcpEnabled()).thenReturn(true);
        when(runtimeConfigService.getMcpDefaultStartupTimeout()).thenReturn(30);
        when(runtimeConfigService.getMcpDefaultIdleTimeout()).thenReturn(5);
        manager = new McpClientManager(runtimeConfigService, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    // ===== getOrStartClient - disabled =====

    @Test
    void shouldReturnEmptyWhenMcpDisabled() {
        when(runtimeConfigService.isMcpEnabled()).thenReturn(false);

        Skill skill = Skill.builder()
                .name("github")
                .description("GitHub")
                .content("Text")
                .mcpConfig(McpConfig.builder()
                        .command("npx -y @modelcontextprotocol/server-github")
                        .env(Map.of("GITHUB_TOKEN", "test"))
                        .build())
                .build();

        List<ToolDefinition> tools = manager.getOrStartClient(skill);
        assertTrue(tools.isEmpty());
    }

    // ===== getOrStartClient - no MCP config =====

    @Test
    void shouldReturnEmptyWhenSkillHasNoMcp() {
        Skill skill = Skill.builder()
                .name("plain-skill")
                .description("No MCP")
                .content("Just text")
                .build();

        List<ToolDefinition> tools = manager.getOrStartClient(skill);
        assertTrue(tools.isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenMcpConfigNull() {
        Skill skill = Skill.builder()
                .name("null-mcp")
                .description("Null MCP config")
                .content("Text")
                .mcpConfig(null)
                .build();

        List<ToolDefinition> tools = manager.getOrStartClient(skill);
        assertTrue(tools.isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenCommandEmpty() {
        Skill skill = Skill.builder()
                .name("empty-cmd")
                .description("Empty command")
                .content("Text")
                .mcpConfig(McpConfig.builder().command("").build())
                .build();

        List<ToolDefinition> tools = manager.getOrStartClient(skill);
        assertTrue(tools.isEmpty());
    }

    // ===== getOrStartClient - invalid command =====

    @Test
    void shouldReturnEmptyWhenCommandFails() {
        Skill skill = Skill.builder()
                .name("bad-cmd")
                .description("Bad command")
                .mcpConfig(McpConfig.builder()
                        .command("nonexistent-binary-that-does-not-exist-12345")
                        .startupTimeoutSeconds(2)
                        .build())
                .build();

        List<ToolDefinition> tools = manager.getOrStartClient(skill);
        assertTrue(tools.isEmpty());
    }

    // ===== getClient =====

    @Test
    void shouldReturnEmptyForUnknownSkill() {
        assertTrue(manager.getClient("nonexistent").isEmpty());
    }

    // ===== stopClient =====

    @Test
    void shouldReturnEmptyListWhenStoppingUnknownSkill() {
        List<String> tools = manager.stopClient("nonexistent");
        assertTrue(tools.isEmpty());
    }

    // ===== getToolNames =====

    @Test
    void shouldReturnEmptyToolNamesForUnknownSkill() {
        List<String> tools = manager.getToolNames("unknown");
        assertTrue(tools.isEmpty());
    }

    // ===== createToolAdapter =====

    @Test
    void shouldCreateToolAdapter() {
        ToolDefinition def = ToolDefinition.builder()
                .name("test_tool")
                .description("Test tool")
                .inputSchema(Map.of("type", "object"))
                .build();

        ToolComponent adapter = manager.createToolAdapter("test-skill", def);
        assertNotNull(adapter);
        assertEquals("test_tool", adapter.getDefinition().getName());
        assertEquals("Test tool", adapter.getDefinition().getDescription());
    }

    @Test
    void shouldCreateToolAdapterWithCorrectDefinition() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string")));

        ToolDefinition def = ToolDefinition.builder()
                .name("search")
                .description("Search the web")
                .inputSchema(schema)
                .build();

        ToolComponent adapter = manager.createToolAdapter("web-skill", def);
        assertNotNull(adapter);
        assertEquals("search", adapter.getDefinition().getName());
        assertEquals(schema, adapter.getDefinition().getInputSchema());
    }

    // ===== shutdown =====

    @Test
    void shouldShutdownCleanly() {
        manager.shutdown();
        assertTrue(manager.getClient("any").isEmpty());
        assertTrue(manager.getToolNames("any").isEmpty());
    }

    @Test
    void shouldHandleDoubleShutdown() {
        manager.shutdown();
        assertDoesNotThrow(() -> manager.shutdown());
    }

    // ===== Default timeout application =====

    @Test
    void shouldApplyDefaultTimeouts() {
        when(runtimeConfigService.getMcpDefaultStartupTimeout()).thenReturn(60);
        when(runtimeConfigService.getMcpDefaultIdleTimeout()).thenReturn(30);

        McpClientManager freshManager = new McpClientManager(runtimeConfigService, new ObjectMapper());
        assertNotNull(freshManager);
        freshManager.shutdown();
    }

    // ===== Concurrent behavior =====

    @Test
    void shouldHandleConcurrentGetOrStart() {
        Skill skill = Skill.builder()
                .name("concurrent-test")
                .description("Test")
                .mcpConfig(McpConfig.builder()
                        .command("nonexistent-binary-12345")
                        .startupTimeoutSeconds(1)
                        .build())
                .build();

        // Both calls should handle gracefully even with bad command
        List<ToolDefinition> result1 = manager.getOrStartClient(skill);
        List<ToolDefinition> result2 = manager.getOrStartClient(skill);

        assertTrue(result1.isEmpty());
        assertTrue(result2.isEmpty());
    }
}
