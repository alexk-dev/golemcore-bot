package me.golemcore.bot.domain.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.port.outbound.ToolRegistryPort;

/**
 * Runtime-owned catalog for tools contributed by built-in modules and plugins.
 */
@Slf4j
public class ToolRegistryService implements ToolRegistryPort {

    private final ConcurrentMap<String, ToolComponent> tools = new ConcurrentHashMap<>();

    public ToolRegistryService(List<ToolComponent> toolComponents) {
        if (toolComponents != null) {
            for (ToolComponent tool : toolComponents) {
                registerInitialTool(tool);
            }
        }
    }

    @Override
    public void registerTool(ToolComponent tool) {
        String toolName = requireToolName(tool);
        tools.put(toolName, tool);
    }

    private void registerInitialTool(ToolComponent tool) {
        try {
            if (tool == null) {
                log.warn("Skipping unnamed initial tool component");
                return;
            }
            String toolName = tool.getToolName();
            if (toolName == null || toolName.isBlank()) {
                log.warn("Skipping unnamed initial tool component");
                return;
            }
            tools.put(toolName, tool);
        } catch (RuntimeException exception) {
            log.warn("Skipping initial tool component that failed metadata resolution: {}", exception.getMessage());
        }
    }

    private String requireToolName(ToolComponent tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        String toolName = tool.getToolName();
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        return toolName;
    }

    @Override
    public void unregisterTools(Collection<String> toolNames) {
        if (toolNames == null) {
            return;
        }
        for (String name : toolNames) {
            if (name != null && !name.isBlank()) {
                tools.remove(name);
            }
        }
        log.debug("Unregistered tools: {}", toolNames);
    }

    public ToolComponent getTool(String name) {
        return name != null ? tools.get(name) : null;
    }

    public List<ToolComponent> listTools() {
        return tools.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
    }

    public Set<String> toolNames() {
        return new TreeSet<>(tools.keySet());
    }
}
