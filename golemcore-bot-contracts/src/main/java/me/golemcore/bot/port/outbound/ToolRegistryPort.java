package me.golemcore.bot.port.outbound;

import java.util.Collection;
import me.golemcore.bot.domain.component.ToolComponent;

/**
 * Dynamic tool registry used by capability modules that contribute tools at
 * runtime.
 */
public interface ToolRegistryPort {

    void registerTool(ToolComponent tool);

    void unregisterTools(Collection<String> toolNames);
}
