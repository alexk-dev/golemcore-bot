package me.golemcore.bot.plugin.runtime;

import me.golemcore.bot.port.outbound.ConfirmationPort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Host-facing confirmation port delegating to any loaded plugin port.
 */
@Component
@Primary
public class PluginBackedConfirmationPort implements ConfirmationPort {

    private final Map<String, List<ConfirmationPort>> pluginPorts = new LinkedHashMap<>();

    public synchronized void replacePluginPorts(String pluginId, Collection<ConfirmationPort> ports) {
        pluginPorts.put(pluginId, List.copyOf(ports));
    }

    public synchronized void removePluginPorts(String pluginId) {
        pluginPorts.remove(pluginId);
    }

    @Override
    public synchronized CompletableFuture<Boolean> requestConfirmation(String chatId, String toolName,
            String description) {
        return pluginPorts.values().stream()
                .flatMap(List::stream)
                .filter(ConfirmationPort::isAvailable)
                .findFirst()
                .map(port -> port.requestConfirmation(chatId, toolName, description))
                .orElseGet(() -> CompletableFuture.completedFuture(true));
    }

    @Override
    public synchronized boolean isAvailable() {
        return pluginPorts.values().stream()
                .flatMap(List::stream)
                .anyMatch(ConfirmationPort::isAvailable);
    }
}
