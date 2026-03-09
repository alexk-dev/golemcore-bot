package me.golemcore.bot.plugin.runtime.extension;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginConfirmationPortAdapterTest {

    @Test
    void shouldForwardConfirmationRequestsAndAvailability() {
        me.golemcore.plugin.api.extension.port.outbound.ConfirmationPort delegate = mock(
                me.golemcore.plugin.api.extension.port.outbound.ConfirmationPort.class);
        when(delegate.requestConfirmation("42", "shell", "rm -rf"))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(delegate.isAvailable()).thenReturn(true);

        PluginConfirmationPortAdapter adapter = new PluginConfirmationPortAdapter(delegate);

        assertTrue(adapter.requestConfirmation("42", "shell", "rm -rf").join());
        assertTrue(adapter.isAvailable());
    }
}
