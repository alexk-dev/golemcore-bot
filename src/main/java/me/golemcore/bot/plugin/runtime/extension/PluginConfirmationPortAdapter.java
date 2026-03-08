package me.golemcore.bot.plugin.runtime.extension;

import me.golemcore.bot.port.outbound.ConfirmationPort;

import java.util.concurrent.CompletableFuture;

/**
 * Adapts a plugin API confirmation port into the host confirmation contract.
 */
public final class PluginConfirmationPortAdapter implements ConfirmationPort {

    private final me.golemcore.plugin.api.extension.port.outbound.ConfirmationPort delegate;

    public PluginConfirmationPortAdapter(me.golemcore.plugin.api.extension.port.outbound.ConfirmationPort delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableFuture<Boolean> requestConfirmation(String chatId, String toolName, String description) {
        return delegate.requestConfirmation(chatId, toolName, description);
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }
}
