package me.golemcore.bot.plugin.runtime.extension;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.plugin.api.extension.spi.ToolProvider;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Adapts a plugin-contributed tool provider to the host tool contract.
 */
public class PluginToolAdapter implements ToolComponent {

    private final ToolProvider delegate;
    private final PluginExtensionApiMapper mapper;

    public PluginToolAdapter(ToolProvider delegate, PluginExtensionApiMapper mapper) {
        this.delegate = delegate;
        this.mapper = mapper;
    }

    @Override
    public ToolDefinition getDefinition() {
        return mapper.toHostToolDefinition(delegate.getDefinition());
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        Map<String, Object> copiedParameters = mapper.copyGenericMap(parameters);
        return delegate.execute(copiedParameters)
                .thenApply(mapper::toHostToolResult);
    }

    @Override
    public boolean isEnabled() {
        return delegate.isEnabled();
    }
}
