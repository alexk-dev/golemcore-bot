package me.golemcore.bot.adapter.outbound.mcp;

import me.golemcore.bot.domain.model.McpConfig;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.infrastructure.config.BotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for McpClientManager caching, stop/cleanup, and handling of skills
 * without MCP.
 */
class McpClientManagerTest {

    private BotProperties properties;
    private McpClientManager manager;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        properties.getMcp().setEnabled(true);
        manager = new McpClientManager(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void testSkillWithoutMcpReturnsEmptyList() {
        Skill skill = Skill.builder()
                .name("plain-skill")
                .description("No MCP")
                .content("Just text")
                .build();

        List<ToolDefinition> tools = manager.getOrStartClient(skill);
        assertTrue(tools.isEmpty());
    }

    @Test
    void testSkillWithNullMcpConfigReturnsEmptyList() {
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
    void testSkillWithEmptyCommandReturnsEmptyList() {
        Skill skill = Skill.builder()
                .name("empty-cmd")
                .description("Empty command")
                .content("Text")
                .mcpConfig(McpConfig.builder().command("").build())
                .build();

        List<ToolDefinition> tools = manager.getOrStartClient(skill);
        assertTrue(tools.isEmpty());
    }

    @Test
    void testGetClientForUnknownSkillReturnsEmpty() {
        assertTrue(manager.getClient("nonexistent").isEmpty());
    }

    @Test
    void testStopClientForUnknownSkillReturnsEmptyList() {
        List<String> tools = manager.stopClient("nonexistent");
        assertTrue(tools.isEmpty());
    }

    @Test
    void testGetToolNamesForUnknownSkillReturnsEmptyList() {
        List<String> tools = manager.getToolNames("nonexistent");
        assertTrue(tools.isEmpty());
    }

    @Test
    void testMcpDisabledReturnsEmptyList() {
        properties.getMcp().setEnabled(false);

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

    @Test
    void testShutdownClearsClients() {
        manager.shutdown();
        assertTrue(manager.getClient("any").isEmpty());
        assertTrue(manager.getToolNames("any").isEmpty());
    }
}
