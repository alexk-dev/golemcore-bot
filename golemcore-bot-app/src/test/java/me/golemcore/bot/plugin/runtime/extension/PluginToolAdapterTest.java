package me.golemcore.bot.plugin.runtime.extension;

import me.golemcore.bot.domain.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginToolAdapterTest {

    @Test
    void shouldMapToolDefinitionExecutionAndEnabledFlag() {
        me.golemcore.plugin.api.extension.spi.ToolProvider delegate = mock(
                me.golemcore.plugin.api.extension.spi.ToolProvider.class);
        PluginToolAdapter adapter = new PluginToolAdapter(delegate, new PluginExtensionApiMapper());

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        when(delegate.getDefinition()).thenReturn(me.golemcore.plugin.api.extension.model.ToolDefinition.builder()
                .name("browser")
                .description("Open pages")
                .inputSchema(schema)
                .build());
        when(delegate.execute(anyMap())).thenReturn(CompletableFuture.completedFuture(
                me.golemcore.plugin.api.extension.model.ToolResult.success(
                        "ok",
                        Map.of("nested", Map.of("status", "ok")))));
        when(delegate.isEnabled()).thenReturn(false);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("url", "https://example.com");
        parameters.put("headers", new ArrayList<>(List.of("x-trace-id")));

        assertEquals("browser", adapter.getDefinition().getName());
        assertEquals("Open pages", adapter.getDefinition().getDescription());
        assertNotSame(schema, adapter.getDefinition().getInputSchema());

        ToolResult result = adapter.execute(parameters).join();
        assertEquals("ok", result.getOutput());
        assertEquals("ok", ((Map<?, ?>) ((Map<?, ?>) result.getData()).get("nested")).get("status"));
        assertFalse(adapter.isEnabled());

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> parametersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(delegate).execute(parametersCaptor.capture());
        assertNotSame(parameters, parametersCaptor.getValue());
        assertNotSame(parameters.get("headers"), parametersCaptor.getValue().get("headers"));
    }
}
