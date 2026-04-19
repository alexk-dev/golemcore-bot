package me.golemcore.bot.plugin.runtime;

import me.golemcore.bot.port.outbound.ConfirmationPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginBackedConfirmationPortTest {

    @Test
    void shouldAllowByDefaultWhenNoAvailablePortsExist() {
        PluginBackedConfirmationPort port = new PluginBackedConfirmationPort();

        Boolean allowed = port.requestConfirmation("chat-1", "shell", "Run shell").join();

        assertTrue(allowed);
        assertFalse(port.isAvailable());
    }

    @Test
    void shouldDelegateToFirstAvailablePortInInsertionOrder() {
        ConfirmationPort unavailable = mock(ConfirmationPort.class);
        ConfirmationPort firstAvailable = mock(ConfirmationPort.class);
        ConfirmationPort secondAvailable = mock(ConfirmationPort.class);
        when(unavailable.isAvailable()).thenReturn(false);
        when(firstAvailable.isAvailable()).thenReturn(true);
        when(secondAvailable.isAvailable()).thenReturn(true);
        when(firstAvailable.requestConfirmation(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(false));

        PluginBackedConfirmationPort port = new PluginBackedConfirmationPort();
        port.replacePluginPorts("plugin-a", List.of(unavailable, firstAvailable));
        port.replacePluginPorts("plugin-b", List.of(secondAvailable));

        Boolean allowed = port.requestConfirmation("chat-1", "shell", "Run shell").join();

        assertFalse(allowed);
        verify(firstAvailable).requestConfirmation("chat-1", "shell", "Run shell");
    }

    @Test
    void shouldReflectAvailabilityAcrossRegisteredPorts() {
        ConfirmationPort available = mock(ConfirmationPort.class);
        when(available.isAvailable()).thenReturn(true);

        PluginBackedConfirmationPort port = new PluginBackedConfirmationPort();
        port.replacePluginPorts("plugin-a", List.of(available));

        assertTrue(port.isAvailable());

        port.removePluginPorts("plugin-a");
        assertFalse(port.isAvailable());
    }
}
