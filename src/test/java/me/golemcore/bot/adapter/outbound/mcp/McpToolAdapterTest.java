package me.golemcore.bot.adapter.outbound.mcp;

import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for McpToolAdapter — definition, execute delegation, and isEnabled.
 */
class McpToolAdapterTest {

    private McpClientManager manager;

    @BeforeEach
    void setUp() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isMcpEnabled()).thenReturn(true);
        when(runtimeConfigService.getMcpDefaultStartupTimeout()).thenReturn(30);
        when(runtimeConfigService.getMcpDefaultIdleTimeout()).thenReturn(5);
        manager = new McpClientManager(runtimeConfigService, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void testGetDefinition() {
        ToolDefinition def = ToolDefinition.builder()
                .name("create_issue")
                .description("Create a GitHub issue")
                .inputSchema(Map.of("type", "object"))
                .build();

        McpToolAdapter adapter = new McpToolAdapter("github", def, manager);

        assertEquals("create_issue", adapter.getDefinition().getName());
        assertEquals("create_issue", adapter.getToolName());
        assertEquals("Create a GitHub issue", adapter.getDefinition().getDescription());
    }

    @Test
    void testIsEnabledReturnsFalseWhenNoClient() {
        ToolDefinition def = ToolDefinition.simple("test_tool", "Test");
        McpToolAdapter adapter = new McpToolAdapter("nonexistent", def, manager);

        assertFalse(adapter.isEnabled());
    }

    @Test
    void testExecuteReturnsFailureWhenNoClient() throws Exception {
        ToolDefinition def = ToolDefinition.simple("test_tool", "Test");
        McpToolAdapter adapter = new McpToolAdapter("nonexistent", def, manager);

        CompletableFuture<ToolResult> future = adapter.execute(Map.of());
        ToolResult result = future.get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("MCP server not running"));
    }

    @Test
    void testGetSkillName() {
        ToolDefinition def = ToolDefinition.simple("tool", "desc");
        McpToolAdapter adapter = new McpToolAdapter("my-skill", def, manager);

        assertEquals("my-skill", adapter.getSkillName());
    }

    @Test
    void testComponentType() {
        ToolDefinition def = ToolDefinition.simple("tool", "desc");
        McpToolAdapter adapter = new McpToolAdapter("skill", def, manager);

        assertEquals("tool", adapter.getComponentType());
    }
}
