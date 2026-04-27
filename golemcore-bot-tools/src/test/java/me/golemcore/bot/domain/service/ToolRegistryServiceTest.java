package me.golemcore.bot.domain.service;

import java.util.List;
import me.golemcore.bot.domain.component.ToolComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRegistryServiceTest {

    @Test
    void shouldLoadInitialToolsAndListThemByName() {
        ToolComponent beta = tool("beta");
        ToolComponent alpha = tool("alpha");

        ToolRegistryService registry = new ToolRegistryService(List.of(beta, alpha));

        assertEquals(List.of(alpha, beta), registry.listTools());
        assertEquals(alpha, registry.getTool("alpha"));
        assertEquals(List.of("alpha", "beta"), List.copyOf(registry.toolNames()));
    }

    @Test
    void shouldRegisterAndUnregisterRuntimeTools() {
        ToolRegistryService registry = new ToolRegistryService(List.of());
        ToolComponent tool = tool("runtime_tool");

        registry.registerTool(tool);
        assertEquals(tool, registry.getTool("runtime_tool"));

        registry.unregisterTools(List.of("runtime_tool"));

        assertNull(registry.getTool("runtime_tool"));
    }

    @Test
    void shouldSkipUnnamedInitialTools() {
        ToolComponent unnamed = tool(" ");

        ToolRegistryService registry = new ToolRegistryService(List.of(unnamed));

        assertEquals(List.of(), registry.listTools());
    }

    @Test
    void shouldListToolsByStableRegistryName() {
        ToolComponent tool = mock(ToolComponent.class);
        when(tool.getToolName()).thenReturn("alpha", (String) null);

        ToolRegistryService registry = new ToolRegistryService(List.of(tool));

        assertEquals(List.of(tool), registry.listTools());
    }

    @Test
    void shouldRejectBlankToolNames() {
        ToolRegistryService registry = new ToolRegistryService(List.of());
        ToolComponent tool = tool(" ");

        assertThrows(IllegalArgumentException.class, () -> registry.registerTool(tool));
    }

    private static ToolComponent tool(String name) {
        ToolComponent tool = mock(ToolComponent.class);
        when(tool.getToolName()).thenReturn(name);
        return tool;
    }
}
